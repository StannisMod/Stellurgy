package dev.stannismod.stellurgy.test.client;

import dev.stannismod.stellurgy.entity.EntityRocket;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Plot;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.RocketInfo;
import dev.stannismod.stellurgy.test.RocketList;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Which rocket packets the server takes from a player's client: what a rocket accepts from a player
 * the server is showing it to, beyond the channel's own checks.
 *
 * <p>NEW-GROUP: a craft's own rule for client packets — which of a rocket's packet ids a client may send
 * at all, and which only from a passenger or from within the range its screen stays open at; the same
 * rule reached from a rocket subclass's own packet; and a hovercraft's, whose keys are its driver's. No
 * client group holds it: every other craft class drives a craft through its keys and screens and
 * observes the flight, never a packet the craft must refuse.</p>
 *
 * <p><b>Why the client tier, and how a packet is sent.</b> A rocket takes an entity packet only from a
 * player the server's entity tracker is showing it to — the one way a client learns an entity's id —
 * and only a connected player is ever tracked; the headless test player of the server tier never is.
 * The packet itself cannot come from this client: each scenario is a packet our own client never
 * writes. So {@code stellurgytest packet entity} hands the bytes to the packet class as the connected
 * player's, encoded by the rocket's own writer with the client's choice of compound laid in, and runs
 * it for him on the server thread — the whole server half of the channel. Netty's framing and FML's
 * dispatch by discriminator are not exercised. Delivery is synchronous, so nothing here waits on it.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class RocketPacketSenderClientGroupTest extends AbstractSharedClientE2ETest {

    /** The overworld, where every plot of this class lies. */
    private static final int OVERWORLD = 0;

    /**
     * Plots wide enough for a sender to stand 50 blocks from a rocket on both axes and still be on
     * this scenario's own ground: the default 64 is not.
     */
    private static final int PLOT_SIZE = 96;

    /** The full rocket of {@code stellurgytest fixture rocket}: engines, tanks, guidance and a seat. */
    private static final String ROCKET = "simple";
    /**
     * The fixture's working volume: its structure tower stands one block west of the pad and its
     * builder one block north, and the tower reaches six blocks above the pad (the fixture's own
     * {@code towerTop}), so seven up covers everything it lays.
     */
    private static final int FIXTURE_HALO = 1;
    private static final int FIXTURE_HEIGHT = 7;

    /**
     * {@code EntityRocket#CONTAINER_RANGE}: how far from the rocket its screen stays open, and so how
     * far a screen's packet is taken from.
     */
    private static final double SCREEN_RANGE = 64;
    /**
     * How far, on each horizontal axis, the server shows an entity to a player in this harness: the
     * rocket registers a tracking range of 64, and vanilla caps it at the server's view distance less a
     * chunk — {@code view-distance=4} in the harness's {@code server.properties}, so 4 * 16 - 16 = 48.
     */
    private static final double SHOWN_WITHIN = 48;
    /**
     * Where the far sender stands, on both axes, from the pad's corner: 50. The rocket stands about
     * three and a half blocks in from that corner, which puts him about 46.5 from it on each axis —
     * inside {@link #SHOWN_WITHIN} — and about 66 in a straight line — beyond {@link #SCREEN_RANGE}.
     * The scenario measures both rather than trusting this sum.
     */
    private static final int FAR_OFFSET = 50;

    /**
     * The unmanned-vehicle fixture ({@code stellurgytest fixture uv-rocket}): its builder stands at the
     * centre of a row five wide and its tower rises six above the builder, so with the builder one block
     * above the site and two blocks in, it fills the site's footprint and seven blocks up.
     */
    private static final int UV_BUILDER_IN = 2;
    private static final int UV_HEIGHT = 7;
    /**
     * Where the far sender stands from the deployed rocket, on both axes: 46 — inside {@link #SHOWN_WITHIN}
     * on each axis, and about 65 in a straight line, beyond {@link #SCREEN_RANGE}. Measured, not trusted.
     */
    private static final int DEPLOYED_FAR = 46;
    /** Where the near sender stands from the deployed rocket, along Z: well inside {@link #SCREEN_RANGE}. */
    private static final int DEPLOYED_NEAR = 8;
    /** The gas a client asks a deployed rocket to harvest: any index; the rocket wraps it into its list. */
    private static final int SOME_GAS = 1;

    /** The hovercraft's entity name, as its registration lowercases it. */
    private static final String HOVERCRAFT = "stellurgy:stellurgyhovercraft";

    @Override
    protected String subsystem() {
        return "mod-network";
    }

    @Override
    protected Plot.Lane lane() {
        return new Plot.Lane(Plot.Lane.DEFAULT.originX, Plot.Lane.DEFAULT.originZ, PLOT_SIZE, PLOT_SIZE);
    }

    /**
     * A rocket left standing by a previous scenario moves, and so does a hovercraft; each scenario
     * starts with neither. The hovercraft goes by vanilla's own command, whose reply is text and is not
     * read: a scenario that made none has none to remove.
     */
    @Override
    protected void resetFamilyStateBeforeTeleport() throws Exception {
        RocketList.clearFrom(this::exec, OVERWORLD);
        exec("kill @e[type=" + HOVERCRAFT + "]");
    }

    /**
     * A rocket takes its state and its blocks from the server only: a client's packet carrying them is
     * refused, even from a player the rocket takes other packets from.
     *
     * <p>Contract: fails if {@code EntityRocket#acceptsFromClient} stops refusing
     * {@code RECIEVENBT} from a client — the packet that rewrites a rocket's orbit, fuel, destination
     * and blocks.</p>
     * <p>red-witnessed: with {@code EntityRocket#acceptsFromClient} at {@code default: return false;} made to return true, fails: "a client's packet carrying a rocket's state set it in orbit: the state of a rocket is the server's to send, never a client's" (2026-10-07).</p>
     */
    @Test
    public void aRocketTakesItsStateOnlyFromTheServer() throws Exception {
        FixtureSite site = site();
        int id = RocketFixture.rocketEntityId(RocketFixture.assembleAt(site, this::exec, ROCKET,
                FIXTURE_HALO, FIXTURE_HEIGHT, "the rocket is built in this volume"));
        String uuid = RocketInfo.byId(this::exec, id).requireUuid();
        standBesideThePad(site);
        scenario().requireArranged("the rocket must not start in orbit, or a refused write of"
                + " orbit=true could not be told from one that went through",
                !RocketInfo.byId(this::exec, id).inOrbit);
        requireShownTheRocket(id);

        Reply sent = sendToRocket(id, EntityRocket.PacketType.RECIEVENBT, "bool:orbit=true");
        scenario().requireArranged("the rocket's own writer must have encoded the packet, so its blocks"
                + " ride along as a real one's would: " + sent, "entity".equals(sent.text("encodedBy")));
        assertFalse("a client's packet carrying a rocket's state set it in orbit: the state of a rocket is"
                + " the server's to send, never a client's",
                RocketInfo.byId(this::exec, id).inOrbit);

        sendToRocket(id, EntityRocket.PacketType.DECONSTRUCT);
        assertTrue("the same player, beside the rocket, must be believed when he dismantles it — or the"
                + " refusal above says nothing about the packet: " + findByUuid(uuid),
                dismantled(uuid));
    }

    /**
     * A packet of the rocket's screen is taken from a player within the range the screen stays open
     * at, and not from one beyond it — even while the server is showing him the rocket.
     *
     * <p>Contract: fails if {@code EntityRocket#acceptsFromClient} stops refusing a screen packet
     * ({@code DECONSTRUCT}) from a player who neither rides the rocket nor stands within its screen's
     * range.</p>
     * <p>red-witnessed: with {@code EntityRocket#acceptsFromClient} at {@code case DECONSTRUCT:} answering true for anyone, fails: "a dismantle sent from beyond the rocket's screen range took the rocket apart: ("error":"rocket not found by uuid","uuid":"45e09bb7-9419-4f65-99c2-818a315f740e")" (2026-10-07).</p>
     */
    @Test
    public void aRocketScreenPacketFromBeyondItsScreenRangeIsRefused() throws Exception {
        FixtureSite site = site();
        int fx = site.x + FAR_OFFSET, fz = site.z + FAR_OFFSET;
        layFloor(fx, fz, fx, fz);
        // He stands far BEFORE the rocket exists: an entity is shown to every player in range at the
        // moment it is tracked, so he is shown it from its first tick.
        standOnFloorTheClientHolds(fx + 0.5, site.y + 1, fz + 0.5, 0, 0,
                "the sender stands where the rocket will be shown to him but its screen out of reach");
        int id = RocketFixture.rocketEntityId(RocketFixture.assembleAt(site, this::exec, ROCKET,
                FIXTURE_HALO, FIXTURE_HEIGHT, "the rocket is built in this volume"));
        RocketInfo rocket = RocketInfo.byId(this::exec, id);
        String uuid = rocket.requireUuid();
        Reply sender = Reply.of(exec("stellurgytest packet sender"));
        double dx = Math.abs(sender.number("x") - rocket.posX);
        double dz = Math.abs(sender.number("z") - rocket.posZ);
        scenario().requireArranged("the sender must stand beyond the rocket's screen range (" + SCREEN_RANGE
                        + ") and within the distance it is shown at (" + SHOWN_WITHIN + " per axis):"
                        + " dx=" + dx + " dz=" + dz + " " + sender,
                Math.sqrt(dx * dx + dz * dz) > SCREEN_RANGE && Math.max(dx, dz) <= SHOWN_WITHIN);
        requireShownTheRocket(id);

        sendToRocket(id, EntityRocket.PacketType.DECONSTRUCT);
        Reply after = findByUuid(uuid);
        assertTrue("a dismantle sent from beyond the rocket's screen range took the rocket apart: " + after,
                after.ok() && !after.bool("isDead"));

        standBesideThePad(site);
        sendToRocket(id, EntityRocket.PacketType.DECONSTRUCT);
        assertTrue("the same player, beside the rocket, must be believed when he dismantles it — or the"
                + " refusal above says nothing about the range: " + findByUuid(uuid), dismantled(uuid));
    }

    /**
     * A station-deployed rocket's gas selection, which only its screen sends, is decided by the rocket's
     * own client rule: refused from a player who neither rides it nor stands within its screen's range,
     * taken from one who does.
     *
     * <p>Contract: fails if {@code EntityStationDeployedRocket} handles {@code MENU_CHANGE} on a path that
     * does not ask {@code EntityRocket#acceptsFromClient}, or if that rule takes it from beyond the
     * screen's range.</p>
     * <p>Why the verdict, not the gas, is read: a deployed rocket changes its gas only above a gas giant,
     * and the harness's universe has none, so the gas this rocket would harvest reads the same whether a
     * selection was taken or refused. The verdict is recorded where the rocket gives it
     * ({@code rocket_client_gate}).</p>
     * <p>red-witnessed: with {@code EntityRocket#useNetworkData} at {@code if (!world.isRemote && !acceptsFromClient(player, id))} letting {@code MENU_CHANGE} past without asking — the path the subclass took before it handled the packet only after the rule — fails: "a gas selection from a player beyond the screen's range must be weighed by the rocket's client rule, once, and refused (no verdict at all: nobody weighed it): … expected:&lt;[false]&gt; but was:&lt;[]&gt;" (2026-10-07).</p>
     * <p>red-witnessed: with {@code EntityRocket#acceptsFromClient} at {@code case MENU_CHANGE:} removed, fails: "a gas selection from beside the rocket must be weighed once and taken — or the refusal above says nothing about the range: … expected:&lt;[true]&gt; but was:&lt;[false]&gt;" (2026-10-07).</p>
     */
    @Test
    public void aDeployedRocketsGasSelectionIsWeighedByTheRocketsRule() throws Exception {
        FixtureSite site = site();
        site.makeRoom(this::exec, 0, UV_HEIGHT, "the unmanned-vehicle assembler and its rocket stand here");
        Reply fixture = Reply.of(exec("stellurgytest fixture uv-rocket " + OVERWORLD + " "
                + (site.x + UV_BUILDER_IN) + " " + (site.y + 1) + " " + site.z));
        scenario().requireArranged("the unmanned-vehicle fixture must be laid: " + fixture,
                fixture.ok() && fixture.has("builderPos"));
        int id = RocketFixture.rocketEntityId(RocketFixture.assembleBuilt(site, this::exec,
                fixture.requireBlockPos("builderPos")));
        RocketInfo rocket = RocketInfo.byId(this::exec, id);

        int fx = (int) Math.floor(rocket.posX) + DEPLOYED_FAR, fz = (int) Math.floor(rocket.posZ) + DEPLOYED_FAR;
        layFloor(fx, fz, fx, fz);
        standOnFloorTheClientHolds(fx + 0.5, site.y + 1, fz + 0.5, 0, 0,
                "the sender stands where the rocket is shown to him but its screen out of reach");
        Reply far = Reply.of(exec("stellurgytest packet sender"));
        double dx = Math.abs(far.number("x") - rocket.posX), dy = far.number("y") - rocket.posY;
        double dz = Math.abs(far.number("z") - rocket.posZ);
        scenario().requireArranged("the sender must stand beyond the rocket's screen range (" + SCREEN_RANGE
                        + ") and within the distance it is shown at (" + SHOWN_WITHIN + " per axis):"
                        + " dx=" + dx + " dy=" + dy + " dz=" + dz + " " + far,
                Math.sqrt(dx * dx + dy * dy + dz * dz) > SCREEN_RANGE && Math.max(dx, dz) <= SHOWN_WITHIN);
        requireShownTheRocket(id);
        String sender = String.valueOf(far.integer("entityId"));

        long mark = serverEvents().markInstrumented();
        sendRaw(id, EntityRocket.PacketType.REQUESTNBT.ordinal());
        sendRaw(id, EntityRocket.PacketType.MENU_CHANGE.ordinal(), "short:" + SOME_GAS);
        String gate = serverEvents().since(mark, "rocket_client_gate");
        Events.assertInstrumentRan(gate, "rocket_client_gate", "which client packets the rocket weighed");
        scenario().requireArranged("the rocket's own rule must have weighed the request for its data, which"
                + " every player it is shown to may send — or the gate's silence below means nothing: " + gate,
                !Events.recordsWhereAll(gate, "e", String.valueOf(id),
                        "id", String.valueOf(EntityRocket.PacketType.REQUESTNBT.ordinal())).isEmpty());
        assertEquals("a gas selection from a player beyond the screen's range must be weighed by the rocket's"
                        + " client rule, once, and refused (no verdict at all: nobody weighed it): " + gate,
                Collections.singletonList("false"), gasSelectionVerdicts(gate, id, sender));

        int nz = (int) Math.floor(rocket.posZ) + DEPLOYED_NEAR, nx = (int) Math.floor(rocket.posX);
        layFloor(nx, nz, nx, nz);
        standOnFloorTheClientHolds(nx + 0.5, site.y + 1, nz + 0.5, 0, 0, "the sender stands beside the rocket");
        long nearMark = serverEvents().markInstrumented();
        sendRaw(id, EntityRocket.PacketType.MENU_CHANGE.ordinal(), "short:" + SOME_GAS);
        String nearGate = serverEvents().since(nearMark, "rocket_client_gate");
        assertEquals("a gas selection from beside the rocket must be weighed once and taken — or the refusal"
                        + " above says nothing about the range: " + nearGate,
                Collections.singletonList("true"), gasSelectionVerdicts(nearGate, id, sender));
    }

    /** The rocket's verdicts, in order, on gas selections {@code sender} sent rocket {@code id}. */
    private static List<String> gasSelectionVerdicts(String gate, int id, String sender) {
        List<String> verdicts = new ArrayList<>();
        for (String record : Events.recordsWhereAll(gate, "e", String.valueOf(id),
                "id", String.valueOf(EntityRocket.PacketType.MENU_CHANGE.ordinal()), "sender", sender)) {
            verdicts.add(Events.text(record, "accepted"));
        }
        return verdicts;
    }

    /**
     * A hovercraft's climb and descent keys are taken from its driver only: a player beside it, whom the
     * server shows it to, does not steer it.
     *
     * <p>Contract: fails if {@code EntityHoverCraft#useNetworkData} applies {@code TURNUPDATE} from a sender
     * who is not the craft's controlling passenger.</p>
     * <p>red-witnessed: with {@code EntityHoverCraft#useNetworkData} at {@code if (!world.isRemote && (player == null || getControllingPassenger() != player))} made {@code if (false)}, fails: "a climb key from a player who is not driving the hovercraft reached it expected:&lt;0.0&gt; but was:&lt;1.0&gt;" (2026-10-07).</p>
     * <p>red-witnessed: with {@code EntityHoverCraft#useNetworkData} at {@code if (!world.isRemote && (player == null || getControllingPassenger() != player))} made {@code if (!world.isRemote)}, fails: "a climb key from the hovercraft's driver must be taken — or the refusal above says nothing about who drives expected:&lt;1.0&gt; but was:&lt;0.0&gt;" (2026-10-07).</p>
     */
    @Test
    public void aHovercraftTakesItsClimbKeyOnlyFromItsDriver() throws Exception {
        FixtureSite site = site();
        site.makeRoom(this::exec, 0, 2, "the hovercraft and the player beside it stand here");
        layFloor(site.x, site.z, site.x + 5, site.z + 2);
        Reply spawned = Reply.of(exec("stellurgytest entity spawn " + OVERWORLD + " " + (site.x + 1.5) + " "
                + (site.y + 1) + " " + (site.z + 1.5) + " " + HOVERCRAFT));
        scenario().requireArranged("the hovercraft must be spawned: " + spawned,
                spawned.ok() && spawned.bool("spawned"));
        int id = spawned.integer("entityId");
        standOnFloorTheClientHolds(site.x + 4.5, site.y + 1, site.z + 1.5, 0, 0,
                "the sender stands beside the hovercraft, not riding it");
        Reply sees = Reply.of(exec("stellurgytest packet sees " + id));
        scenario().requireArranged("the server must be showing the hovercraft to the sender, and he must not"
                + " ride it: " + sees, sees.ok() && sees.bool("tracked") && !sees.bool("riding"));
        scenario().requireArranged("the hovercraft must start with its climb key up, or a refused press could"
                + " not be told from a taken one", climbKeyDown(id) == 0d);

        sendRaw(id, EntityRocket.PacketType.TURNUPDATE.ordinal(), "bool:true", "bool:false");
        assertEquals("a climb key from a player who is not driving the hovercraft reached it",
                0d, climbKeyDown(id), 0d);

        Reply mounted = Reply.of(exec("stellurgytest player mount-entity " + id));
        scenario().requireArranged("the sender must be put in the driver's seat: " + mounted,
                mounted.ok() && mounted.integer("ridingEntityId") == id);
        sendRaw(id, EntityRocket.PacketType.TURNUPDATE.ordinal(), "bool:true", "bool:false");
        assertEquals("a climb key from the hovercraft's driver must be taken — or the refusal above says"
                + " nothing about who drives", 1d, climbKeyDown(id), 0d);
        sendRaw(id, EntityRocket.PacketType.TURNUPDATE.ordinal(), "bool:false", "bool:false");
        exec("stellurgytest player dismount");
    }

    // ---- instruments --------------------------------------------------------------------------

    /**
     * One entity packet from the connected player whose payload is exactly {@code words}, with no
     * compound; refuses unless it was delivered.
     */
    private void sendRaw(int id, int packetId, String... words) throws Exception {
        StringBuilder command = new StringBuilder("stellurgytest client entity " + OVERWORLD + " " + id + " "
                + packetId);
        for (String word : words) {
            command.append(' ').append(word);
        }
        Reply sent = Reply.of(exec(command.toString()));
        scenario().requireArranged("the packet must have been delivered: " + sent, sent.ok());
    }

    /** The hovercraft's climb key as its own writer reports it to a client: 1 down, 0 up. */
    private double climbKeyDown(int id) throws Exception {
        Reply state = Reply.of(exec("stellurgytest client entity-state " + OVERWORLD + " " + id + " "
                + EntityRocket.PacketType.TURNUPDATE.ordinal()));
        scenario().requireArranged("the hovercraft's key state must be readable: " + state, state.ok());
        return Reply.of(state.object("read")).number("up");
    }

    /**
     * Lay a stone floor at the open-air band over the box {@code (x1, z1)..(x2, z2)}, and refuse unless
     * every cell went into air.
     */
    private void layFloor(int x1, int z1, int x2, int z2) throws Exception {
        int y = site().y;
        Reply laid = Reply.of(exec("stellurgytest fill " + OVERWORLD + " " + x1 + " " + y + " " + z1
                + " " + x2 + " " + y + " " + z2 + " minecraft:stone"));
        int cells = (x2 - x1 + 1) * (z2 - z1 + 1);
        scenario().requireArranged("the floor must be laid into air, all " + cells + " cells: " + laid,
                laid.ok() && laid.integer("placed") == cells);
    }

    /**
     * Stand the player on a floor just south of the pad, a few blocks from the rocket: clear of the
     * pad's six-block square, which the rocket stands on.
     */
    private void standBesideThePad(FixtureSite site) throws Exception {
        int z = site.z + 6;
        layFloor(site.x + 2, z, site.x + 4, z + 1);
        standOnFloorTheClientHolds(site.x + 3.5, site.y + 1, z + 0.5, 0, 0,
                "the sender stands beside the rocket");
    }

    /** Refuse unless the server's tracker is showing the rocket to the sender. */
    private void requireShownTheRocket(int id) throws Exception {
        Reply sees = Reply.of(exec("stellurgytest packet sees " + id));
        scenario().requireArranged("the server must be showing the rocket to the sender, or the rocket"
                + " never weighs his packet at all: " + sees, sees.ok() && sees.bool("tracked"));
    }

    /**
     * One rocket packet from the connected player, delivered; refuses unless it was. The packet id is
     * the type's ordinal, which is what the rocket's packets carry on the wire.
     */
    private Reply sendToRocket(int id, EntityRocket.PacketType type, String... compound) throws Exception {
        StringBuilder command = new StringBuilder("stellurgytest packet entity " + OVERWORLD + " " + id + " "
                + type.ordinal());
        for (String entry : compound) {
            command.append(' ').append(entry);
        }
        Reply sent = Reply.of(exec(command.toString()));
        scenario().requireArranged("the packet must have been delivered: " + sent, sent.ok());
        return sent;
    }

    private Reply findByUuid(String uuid) throws Exception {
        return Reply.of(exec("stellurgytest rocket find-by-uuid " + uuid));
    }

    /**
     * Whether the rocket is gone: either still held by its world but dead (it is removed on the next
     * world tick) or already removed.
     */
    private boolean dismantled(String uuid) throws Exception {
        Reply found = findByUuid(uuid);
        return found.refusedWith("rocket not found by uuid") || (found.ok() && found.bool("isDead"));
    }
}
