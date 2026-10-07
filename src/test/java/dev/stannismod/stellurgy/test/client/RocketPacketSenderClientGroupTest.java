package dev.stannismod.stellurgy.test.client;

import dev.stannismod.stellurgy.entity.EntityRocket;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Plot;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.RocketInfo;
import dev.stannismod.stellurgy.test.RocketList;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Which rocket packets the server takes from a player's client: what a rocket accepts from a player
 * the server is showing it to, beyond the channel's own checks.
 *
 * <p>NEW-GROUP: the rocket's own rule for client packets — which of its packet ids a client may send at
 * all, and which only from a passenger or from within the range its screen stays open at. No client
 * group holds it: every other rocket class drives a rocket through its keys and screens and observes
 * the flight, never a packet the rocket must refuse.</p>
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

    @Override
    protected String subsystem() {
        return "mod-network";
    }

    @Override
    protected Plot.Lane lane() {
        return new Plot.Lane(Plot.Lane.DEFAULT.originX, Plot.Lane.DEFAULT.originZ, PLOT_SIZE, PLOT_SIZE);
    }

    /** A rocket left standing by a previous scenario moves; each scenario starts with none. */
    @Override
    protected void resetFamilyStateBeforeTeleport() throws Exception {
        RocketList.clearFrom(this::exec, OVERWORLD);
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

    // ---- instruments --------------------------------------------------------------------------

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
