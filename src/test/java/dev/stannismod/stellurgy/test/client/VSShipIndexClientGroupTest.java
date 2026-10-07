package dev.stannismod.stellurgy.test.client;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Plot;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.RocketFixture;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Which ship-index packets a client applies to the world he is in.
 *
 * <p>NEW-GROUP: the client's copy of the ship index — the packet that tells a client which ships of a
 * world to load and unload, and the world it is addressed to. No VS group holds it: every other VS class
 * observes what a ship does — its flight, its crossing, its crew — with the index arriving as it always
 * does, never which index a client takes.</p>
 *
 * <p><b>How an index is sent here, and what that cannot see.</b> The server sends one only when a
 * player starts or stops watching a ship, so {@code stellurgytest client vs-index} sends the connected
 * player one carrying exactly the load or unload the scenario names, on the ship-index channel, through
 * the same network wrapper the server's ship loading uses. What the server's loading decides to send is
 * not exercised.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSShipIndexClientGroupTest extends AbstractSharedVsClientTest {

    private static final int OVERWORLD = 0;
    /** A world the client is not in: the nether, which every server has. */
    private static final int NETHER = -1;

    /** The tier-2 fixture that assembles into a ship (the flight computer and a pilot seat). */
    private static final String SHIP = "with-pilot-seat";
    /**
     * The fixture's working volume: its launchpad is five square and its structure tower stands at a
     * corner of it and rises six above the pad ({@code stellurgytest fixture rocket}'s own description),
     * so one block out and seven up covers everything it lays.
     */
    private static final int SHIP_HALO = 1;
    private static final int SHIP_HEIGHT = 7;
    /** Where the second craft stands from the first, along X: clear of the first's working volume. */
    private static final int SECOND_CRAFT_DX = 16;
    /** Where the player stands, inside the plot and south of both craft, well within VS's watch distance. */
    private static final int PLAYER_DX = 28;
    private static final int PLAYER_DZ = 40;
    /**
     * A link's deadline for a record the client writes after a packet: it bounds a failure and never
     * decides a pass.
     */
    private static final int LINK_TICKS = 200;

    @Override
    protected String subsystem() {
        return "vs-ship-index";
    }

    /**
     * A ship-index packet addressed to another world is not applied to the world the client is in: the
     * ship of this world it names stays loaded, while an index addressed to this world, sent right after
     * it, is applied.
     *
     * <p>Contract: fails if {@code ShipIndexDataMessageHandler#onMessage} applies an index to the
     * client's world without comparing the world it is addressed to. The server sends exactly that — an
     * unload to a player who has just LEFT a world — and a ship keeps its uuid when it crosses between
     * worlds, so an unload meant for the world he left would drop the very ship that arrived with him.</p>
     *
     * <p><b>How the window is closed.</b> A client queues every load and unload an index carries and its
     * ship manager carries them out in the order queued, in one pass. The index for another world is sent
     * first and an index for this world unloading a SECOND craft right behind it, on the same channel: when
     * the second craft's unload is recorded, the first index has been handled and anything it queued has
     * been carried out ahead of it.</p>
     *
     * <p>red-witnessed: with {@code ShipIndexDataMessageHandler#onMessage} at {@code if (world == null || world.provider.getDimension() != message.dimensionID)} made {@code if (world == null)}, fails: "an index addressed to another world unloaded a ship of the world the client is in: [(seq 920, ship_unloaded, the first craft, dim 0)] (this world's unload: (seq 923, the second craft))" (2026-10-07).</p>
     * <p>red-witnessed: with {@code ShipIndexDataMessageHandler#onMessage} at {@code physObjectWorld.queueShipUnload(unloadID);} never reached, the wait on this world's unload fails: "an index addressed to the client's own world must be applied — or nothing below can say the other was refused rather than never handled — no `ship_unloaded` carrying vsShip = … was recorded within 200 ticks" (2026-10-07).</p>
     * <p>Not witnessed: the two waits for the client to LOAD the craft. They are the arrangement — a craft
     * the client never loaded has nothing to unload — and a break of the handler's loading fails them as
     * an arrangement, which is what they are.</p>
     */
    @Test
    public void aShipIndexForAnotherWorldLeavesThisWorldsShipsAlone() throws Exception {
        FixtureSite first = plot().siteAt(Plot.FIXTURE_INSET, Plot.FIXTURE_INSET);
        FixtureSite second = plot().siteAt(Plot.FIXTURE_INSET + SECOND_CRAFT_DX, Plot.FIXTURE_INSET);
        int px = plot().x(PLAYER_DX), pz = plot().z(PLAYER_DZ);
        Reply floor = Reply.of(exec("stellurgytest fill " + OVERWORLD + " " + px + " " + first.y + " " + pz
                + " " + px + " " + first.y + " " + pz + " minecraft:stone"));
        scenario().requireArranged("the player's floor must be laid into air: " + floor,
                floor.ok() && floor.integer("placed") == 1);
        standOnFloorTheClientHolds(px + 0.5, first.y + 1, pz + 0.5, 0, 0,
                "the player stands where he watches both craft");

        long clientMark = clientEvents().mark();
        String kept = assemble(first, "the craft whose unload is addressed to another world");
        String fence = assemble(second, "the craft whose unload is addressed to this world");
        clientEvents().awaitRecordWithFields(clientMark, "ship_loaded",
                "the client must have loaded the first craft", LINK_TICKS, "vsShip", kept, "remote", "true");
        clientEvents().awaitRecordWithFields(clientMark, "ship_loaded",
                "the client must have loaded the second craft", LINK_TICKS, "vsShip", fence, "remote", "true");

        long mark = clientEvents().mark();
        sendIndex(NETHER, "unload", kept);
        sendIndex(OVERWORLD, "unload", fence);
        String applied = clientEvents().awaitRecordWithFields(mark, "ship_unloaded",
                "an index addressed to the client's own world must be applied — or nothing below can say the"
                        + " other was refused rather than never handled", LINK_TICKS, "vsShip", fence);
        String window = clientEvents().since(mark, "ship_unloaded");
        Events.assertInstrumentRan(window, "physics_object_events", "which ships the client unloaded");
        List<String> wrongly = Events.recordsWhere(window, "vsShip", kept);
        assertTrue("an index addressed to another world unloaded a ship of the world the client is in: "
                + wrongly + " (this world's unload: " + applied + ")", wrongly.isEmpty());
    }

    /**
     * A ship built on the shipyard claim of a ship that no longer exists can be flown from its seat: the
     * client's pilot gate opens for the pilot seated on it.
     *
     * <p>Contract: fails if {@code ShipIndexDataMessageHandler#onMessage} keeps the record of the ship
     * that held a claim before. The server never tells a client a ship was forgotten, only which ships it
     * indexes; once the claim is handed again the client would hold two records on its chunks, its lookup
     * by position fails on two, and every seat aboard reads as no ship's — the gate stays shut.</p>
     *
     * <p>Arranged: a craft the client has loaded, destroyed through the substrate ({@code vs destroy-ship},
     * which gives its claim back), and a second craft that is then given THAT claim — read off both
     * {@code ship_spawned} records, so the scenario refuses to run on any other claim. The pilot is seated
     * by probe ({@code vs seat-mount}, {@code player mount-entity}); the verdict is the client's own gate
     * record. What it does not see: a pilot's right-click on the seat, and the server's own choice of
     * which claim to hand out beyond this one case.</p>
     *
     * <p>red-witnessed: with {@code ShipIndexDataMessageHandler#onMessage} at
     * {@code if (holder.isPresent() && !holder.get().getUuid().equals(shipData.getUuid()))} made never
     * true, this fails at the load verdict: "the client must load the craft built on a claim given back — no
     * `ship_loaded` carrying vsShip = … was recorded within 200 ticks" (2026-10-07).</p>
     * <p>red-witnessed: NOT YET for the gate verdict after it, with {@code ShipIndexDataMessageHandler#onMessage}
     * at {@code worldData.removeShip(holder.get());} — the break above silences the load first, and a
     * break leaving the craft loaded but the seat unresolved would have to split one position lookup in
     * two; the gate is the player-visible half, the load the one a break of this handler reaches.</p>
     */
    @Test
    public void aShipBuiltOnADestroyedShipsClaimCanBeFlownFromItsSeat() throws Exception {
        FixtureSite first = plot().siteAt(Plot.FIXTURE_INSET, Plot.FIXTURE_INSET);
        FixtureSite second = plot().siteAt(Plot.FIXTURE_INSET + SECOND_CRAFT_DX, Plot.FIXTURE_INSET);
        int px = plot().x(PLAYER_DX), pz = plot().z(PLAYER_DZ);
        Reply floor = Reply.of(exec("stellurgytest fill " + OVERWORLD + " " + px + " " + first.y + " " + pz
                + " " + px + " " + first.y + " " + pz + " minecraft:stone"));
        scenario().requireArranged("the player's floor must be laid into air: " + floor,
                floor.ok() && floor.integer("placed") == 1);
        standOnFloorTheClientHolds(px + 0.5, first.y + 1, pz + 0.5, 0, 0,
                "the player stands where he watches both craft");

        long clientMark = clientEvents().mark();
        long serverMark = serverEvents().mark();
        String gone = assemble(first, "the craft whose claim is given back");
        String goneClaim = claimOf(serverMark, gone);
        clientEvents().awaitRecordWithFields(clientMark, "ship_loaded",
                "the client must hold the first craft's record before it is destroyed", LINK_TICKS,
                "vsShip", gone, "remote", "true");

        long removedMark = serverEvents().mark();
        Reply destroyed = Reply.of(exec("stellurgytest vs destroy-ship " + OVERWORLD + " " + gone));
        scenario().requireArranged("the first craft must be marked for collection: " + destroyed,
                destroyed.bool("marked"));
        serverEvents().awaitRecordWithFields(removedMark, "ship_removed",
                "the substrate must collect the first craft and give its claim back", LINK_TICKS, "vsShip", gone);

        long secondMark = serverEvents().mark();
        String flown = assemble(second, "the craft built on the claim given back");
        String flownClaim = claimOf(secondMark, flown);
        scenario().requireArranged("the second craft must be given the first craft's claim, or nothing here is"
                + " about a claim handed again: " + flownClaim + " against " + goneClaim, goneClaim.equals(flownClaim));
        // VERDICT, not arrangement: a client still holding the destroyed craft's record on this claim
        // cannot take the new craft at all.
        clientEvents().awaitRecordWithFields(clientMark, "ship_loaded",
                "the client must load the craft built on a claim given back", LINK_TICKS, "vsShip", flown,
                "remote", "true");

        Reply seat = Reply.of(exec("stellurgytest vs seat-mount " + OVERWORLD + " id " + flown));
        scenario().requireArranged("the second craft's seat must give a mount: " + seat, seat.bool("seatFound"));
        long seatedMark = clientEvents().mark();
        Reply mounted = Reply.of(exec("stellurgytest player mount-entity " + seat.integer("dummyId")));
        scenario().requireArranged("the player must be put on the seat's mount: " + mounted, !mounted.refused());
        awaitClientMount(seatedMark, "the client must seat the pilot on the second craft", LINK_TICKS,
                "seat " + seat);
        clientEvents().awaitRecordWithFields(seatedMark, "ship_pilot_gate_decided",
                "the pilot gate must open for the pilot of a craft built on a claim given back", LINK_TICKS,
                "open", "true");
    }

    /** The shipyard claim, as {@code x,z} of its centre chunk, that {@code ship}'s spawn record names. */
    private String claimOf(long mark, String ship) throws Exception {
        String spawned = serverEvents().awaitRecordWithFields(mark, "ship_spawned",
                "the spawn of " + ship + " must be recorded", LINK_TICKS, "vsShip", ship);
        return Events.text(spawned, "claim");
    }

    /** Lay and assemble one tier-2 craft at {@code site}; answer its ship uuid. */
    private String assemble(FixtureSite site, String what) throws Exception {
        long mark = serverEvents().mark();
        RocketFixture.assembleAt(site, this::exec, SHIP, SHIP_HALO, SHIP_HEIGHT, what);
        return awaitShipSpawned(serverEvents(), mark, what + " must become a ship");
    }

    private void sendIndex(int dim, String loadOrUnload, String ship) throws Exception {
        Reply sent = Reply.of(exec("stellurgytest client vs-index " + dim + " " + loadOrUnload + " " + ship));
        scenario().requireArranged("the index must have been sent: " + sent, sent.ok());
    }
}
