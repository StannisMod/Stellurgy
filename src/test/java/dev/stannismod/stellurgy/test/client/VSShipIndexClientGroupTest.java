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
