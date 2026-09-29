package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.ShipReadiness;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.EntrySlots;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.ShipIdentity;
import zmaster587.advancedRocketry.test.ShipInfo;

import org.junit.After;
import org.junit.Test;


import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.awaitEnteredSpace;

/**
 * E2E: the tier-2 AUTO-TAKEOFF autopilot — the AUTOMATED half of the entry on-ramp. Two legs on one
 * assembled ship:
 *
 * <ul>
 *   <li><b>Decline</b> (the falsifiable leg): with a solid slab of terrain directly overhead, engaging
 *       auto-takeoff must self-DISENGAGE within a tick — a NORMAL surfaced outcome (fall back to
 *       manual), not a crash and not a silent stuck-on climb.</li>
 *   <li><b>Climb + enter</b>: with a CLEAR corridor and the ship a short hop below the orbit ceiling,
 *       engaging auto-takeoff drives a diagonal climb that crosses the ceiling and hands off to the
 *       entry on-ramp — the ship ends SETTLED in the ledger via the same production path the manual
 *       entry e2e proves.</li>
 * </ul>
 *
 * <p>The autopilot's pure geometry (diagonal slope, corridor length, obstruction test) is pinned
 * deterministically by {@code AutoTakeoffPlannerTest}; this e2e proves it composes with the real force
 * flight controller + entry state machine in a live world. Gated on; skips otherwise.</p>
 */
public class VSShipAutoTakeoffE2ETest extends AbstractSharedServerTest {


    private static final int SRC_X = 6500, SRC_Y = FixtureSite.OPEN_AIR_Y, SRC_Z = 6500;
    /** A short hop below the default orbit ceiling (1000), so the diagonal climb crosses it quickly. */
    private static final int NEAR_CEILING_Y = 985;

    /**
     * Budgets in SERVER TICKS, none fork-scaled: 100 for the autopilot's raycast to run on the AFC's
     * own tick and decline (the old 20 x 250 ms), 800 for the diagonal climb under force plus the
     * async entry (the old 160 x 250 ms), 200 for a ship becoming loadable.
     */
    private static final int DECLINE_TICKS = 100;
    private static final int CLIMB_TICKS = 800;

    @Test
    public void autoTakeoffDeclinesWhenBlockedAndClimbsIntoSpaceWhenClear() throws Exception {

        String setup = exec("artest space entry-setup 2");
        assertTrue("entry setup failed: " + setup, Reply.of(setup).ok());

        // Build + assemble a piloted ship in the overworld.
        String coords = placeFixture(FixtureSite.openAir(0, SRC_X, SRC_Z), "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("AFC build must route to a ship: " + asm, (Reply.of(asm).integer("rocketCount") == 0));
        assertTrue("source ship never loaded", loadedShips(0) >= 1);

        // WHICH ship this scenario is about, taken from the moment that CREATED it: the assembler
        // mints the durable id on the pad and hands it back, and `vs ship-uuid` crosses from that
        // name to the physics id every `vs` verb is keyed on. Nothing here asks the world what is
        // standing at a coordinate — the shared overworld holds every other scenario's craft too,
        // and a proximity answer is indistinguishable from the right one.
        String durableId = ShipIdentity.nameFromAssembly(asm);
        String shipId = ShipIdentity.physicsIdOf(this::exec, 0, durableId);

        ShipInfo src = ShipInfo.byId(this::exec, 0, shipId);
        double sx = src.x, sy = src.y, sz = src.z;

        // ---- DECLINE leg: put a solid slab directly overhead, engage, expect a self-disengage. ----
        // The corridor is a 45-degree diagonal, so it moves ~1 block sideways per block of climb; a
        // blocking slab must be CLOSE overhead and WIDE enough to intercept it before it exits the span.
        int slabY = (int) sy + 5;
        assertTrue("slab fill failed", Reply.of(exec("artest fill 0 " + ((int) sx - 20) + " " + slabY + " " + ((int) sz - 20)
                + " " + ((int) sx + 20) + " " + (slabY + 2) + " " + ((int) sz + 20) + " minecraft:stone")
                ).ok());
        // No manual FF input: the autopilot alone drives (its branch requires in == null). entry-setup
        // cleared any stale static input channel.
        // Marked before the engage, because the refusal below is announced once, on the first AFC
        // tick that raycasts the blocked corridor — which can be the tick the engage itself lands on.
        long declineMark = events.mark();
        String engaged = exec("artest space auto-takeoff 0 id " + shipId);
        assertTrue("auto-takeoff did not engage: " + engaged, Reply.of(engaged).bool("engaged"));

        // Linked on the autopilot's own refusal, narrowed to THIS craft's ship id. The raycast runs
        // on the AFC's own tick, and `auto_takeoff_declined` is written from the RETURN of the
        // method that clears the engaged latch — so the wait ends on the decision rather than on a
        // status read that happened to be taken after it. The poll it replaces asked for `engaged`
        // and treated its ABSENCE as a decline, so a status the autopilot had nothing to say about
        // yet was indistinguishable from a corridor it had refused.
        events.awaitField(declineMark, "auto_takeoff_declined", "ship", shipId,
                "auto-takeoff must decline a blocked corridor", DECLINE_TICKS);
        String status = exec("artest space auto-takeoff 0 id " + shipId + " status");
        assertFalse("the autopilot announced a decline, so it must no longer be engaged: " + status,
                Reply.of(status).boolOr("engaged", false));

        // ---- CLIMB + ENTER leg: clear the slab, hop the ship just below the ceiling, engage, enter. ----
        assertTrue("slab clear failed", Reply.of(exec("artest fill 0 " + ((int) sx - 20) + " " + slabY + " " + ((int) sz - 20)
                + " " + ((int) sx + 20) + " " + (slabY + 2) + " " + ((int) sz + 20) + " minecraft:air")
                ).ok());
        String tp = exec("artest vs teleport-ship-by-id 0 " + shipId + " "
                + (int) sx + " " + NEAR_CEILING_Y + " " + (int) sz);
        assertTrue("hop teleport failed: " + tp, Reply.of(tp).ok());
        exec("artest vs unpark-by-id 0 " + shipId);
        // Marked before the re-engage is allowed to carry the craft up: the arrival is announced
        // once, and a mark taken afterwards would wait for a second climb.
        long climbMark = events.mark();
        String reEngage = exec("artest space auto-takeoff 0 id " + shipId);
        assertTrue("auto-takeoff did not re-engage over a clear corridor: " + reEngage,
                Reply.of(reEngage).bool("engaged"));

        // Linked on the record the entry publishes at its settle, and narrowed to THIS craft's
        // durable id — "somebody settled" is a different question, and a slot the entry stack has
        // ledgered twice used to answer it with a neighbour's row.
        awaitEnteredSpace(events, climbMark, durableId,
                "auto-takeoff must climb the ship into space", CLIMB_TICKS,
                () -> loadAllEntrySlots(setup));
    }

    @After
    public void cleanup() throws Exception {
        exec("artest space entry-clear");
    }

    // --- helpers ------------------------------------------------------------------------------------

    /** This class's reader of the server's ordered event log. */
    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advanceWorld(client(), 0, ticks));

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    /** Keep every slot world's ships load-queued while a wait runs. See {@link EntrySlots}. */
    private void loadAllEntrySlots(String setup) throws Exception {
        EntrySlots.loadAll(this::exec, setup);
    }

    /** How many ships are LOADED in {@code dim} right now. A read, not a wait: measured across this
     *  tier at one and at six forks, the ship is already loaded whenever a scenario asks. */
    private int loadedShips(int dim) throws Exception {
        return ShipReadiness.loadedCount(this::exec, dim);
    }


    /**
     * WHERE this scenario's craft stands, and the first link that says the volume is empty.
     *
     * <p>What stood here was a pair: a {@code clearArea} that ran a chunk warmup and an air fill
     * over {@code y-2 .. y+12}, and a {@code placeFixture} that laid the blocks. The fill DUG
     * rather than asked, and threw away its own answer — {@code placed}, the count of blocks that
     * were standing in the volume. The shared builder asks instead, and on an open-air site
     * anything found is an arrangement failure that names itself. The warmup went with it: the
     * fill force-loads every chunk in its own box, so the first link was already doing that job.</p>
     *
     * <p>HALO 4 and HEIGHT 12 are the old volume's own numbers, kept rather than re-derived:
     * they are what this scenario's green runs were taken over.</p>
     */
    private String placeFixture(FixtureSite site, String variant) throws Exception {
        int[] bp = RocketFixture.placeAt(site, this::exec, variant, 4, 12,
                "the craft this scenario builds stands in this volume");
        return bp[0] + " " + bp[1] + " " + bp[2];
    }

    private static int extractInt(String json, String key) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).integerOr(key, Integer.MIN_VALUE);
    }

    private static double extractDouble(String json, String key) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).numberOr(key, 0.0);
    }

    private static String extractString(String json, String key) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).textOr(key, null);
    }
}
