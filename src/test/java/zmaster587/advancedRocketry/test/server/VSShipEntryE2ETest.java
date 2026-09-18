package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.SubsystemStatus;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.ShipReadiness;
import zmaster587.advancedRocketry.space.CellSeam;
import zmaster587.advancedRocketry.space.CellWorldMapper;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.EntrySlots;
import zmaster587.advancedRocketry.test.EntryStatus;
import zmaster587.advancedRocketry.test.ShipIdentity;
import zmaster587.advancedRocketry.test.ShipInfo;

import org.junit.After;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.awaitWithinTicks;

/**
 * E2E: does the tier-2 ENTRY ON-RAMP take a piloted ship from a planet dimension into space through the
 * REAL gameplay path? A {@code with-pilot-seat} ship is assembled in the overworld, the entry stack is
 * installed, and a pilot presence + a climb PAST the dimension's orbit ceiling are arranged. The
 * <b>flight computer's own server tick</b> then detects the crossing and calls
 * {@code SpaceSubsystem.entry().requestEntry()} — production code, not the probe — which materializes the
 * launch body's cell, crosses the ship into it, and settles it in the {@code ShipLedger}.
 *
 * <p>Witnesses: the ship becomes ledgered as {@code SETTLED} at the SAME cell the production launch-coord
 * resolver answers for the launch dimension (gen-agnostic — no pinned coordinates), and its settled cell
 * world is live. CONTROL: {@code entry-status} reports zero ledgered ships before the climb, proving a
 * later "settled" is a real observation. This is the "real gameplay path calls materialize" acceptance of
 * the entry design; it composes the proven per-ship crossing with the entry state machine (pinned
 * deterministically by {@code ShipEntryControllerTest}).</p>
 *
 * <p>Gated on the server's real VS presence (run with); skips cleanly otherwise.</p>
 */
public class VSShipEntryE2ETest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";

    /**
     * How much WORLD an async crossing is allowed in order to finish settling, in server ticks.
     * <p>
     * Thirty seconds of game time. Deliberately NOT scaled by the build's fork count: the number of
     * forks says how much of the machine this test is sharing, and the crossing does not care — it
     * needs a certain number of controller ticks and gets them whenever the server runs them. A
     * budget in seconds DID care, which is why this used to carry that multiplier and still turned
     * red under load.
     */
    private static final int SETTLE_TICKS = 600;

    /** The same, for waiting on a ship to become loadable in its slot. Ten seconds of game time. */

    /**
     * How long the arrived ship's address is watched for drift, and how far apart the readings are.
     *
     * <p>In ticks of the ship's OWN SLOT WORLD, not of the server: what drifts is driven by the
     * ship's flight computer, which ticks in that world, so those are the ticks the window has to be
     * measured in. Sampling on a wall clock would quietly shrink the window on a busy machine and let
     * a drift through unseen — and a server-clock window would run its whole length even if the slot
     * world had stopped ticking, reporting stability about a ship nothing ever asked to move.</p>
     */
    private static final int DRIFT_SAMPLES = 8;
    private static final int DRIFT_TICKS_BETWEEN_SAMPLES = 5;

    /** Where the piloted ship is built (a loaded overworld region, well clear of other tests). */
    private static final int SRC_X = 6000, SRC_Y = FixtureSite.OPEN_AIR_Y, SRC_Z = 6000;
    /** A world Y comfortably above the default orbit ceiling (ARConfiguration.orbit = 1000). */
    private static final int ABOVE_CEILING_Y = 1200;
    /** The jump leg builds its own ship, well clear of the entry leg's region (shared server, both run). */
    private static final int JUMP_SRC_X = 6400, JUMP_SRC_Z = 6400;
    /**
     * A cell key is {@code sx_sy_sz}. The jump target is derived from the ORIGIN, one sector over: the
     * integrator steps {@code speed} blocks per tick, so the time in hyperspace is distance/speed, and a
     * sector is enormous. An absolute far-away target (first attempt: sector 9001 from an origin at 25)
     * leaves the ship legitimately IN_TRANSIT for thousands of ticks and the test reds on its own poll
     * window while production is working correctly.
     */
    private static final Pattern CELL_KEY = Pattern.compile("^(-?\\d+)_(-?\\d+)_(-?\\d+)$");

    @Test
    public void aPilotedShipClimbingPastTheCeilingEntersSpaceViaTheFlightComputerTick() throws Exception {

        // Headless: pin ships loaded so a freshly assembled/crossed ship does not auto-unload between calls.
        // Install the entry stack into SpaceSubsystem so the PRODUCTION trigger path runs under the harness.
        String setup = exec("artest space entry-setup 2");
        assertTrue("entry setup failed: " + setup, Reply.of(setup).ok());

        // CONTROL: nothing is ledgered before the climb — a later "settled" is then a real observation.
        EntryStatus control = EntryStatus.wholeLedger(this::exec);
        assertEquals("witness sensitivity control — no ship must be ledgered before the climb: " + control,
                0, control.ships);

        // Build a piloted tier-2 ship in the overworld and assemble it into a VS ship.
        clearArea(SRC_X, SRC_Z);
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                (Reply.of(asm).integerOr("rocketCount", Integer.MIN_VALUE) == 0));
        assertTrue("the source VS ship never loaded", loadedShips(0) >= 1);

        // The cell the production resolver answers for the launch dimension — the entry MUST land here.
        String launch = exec("artest space launch-cell 0");
        assertTrue("launch-cell resolve failed: " + launch, Reply.of(launch).ok());
        String expectedCell = extractString(launch, "cellKey");
        assertTrue("launch dim resolved to no cell: " + launch, expectedCell != null);

        // NAME the ship, then arrange the entry preconditions: a pilot (the static FF input channel makes
        // the AFC tick see "someone is flying") and a climb PAST the ceiling (rigid-teleport to Y=1200).
        // The name comes from the assembler, which minted it — not from asking the shared overworld what
        // stands near the pad, which answers with a neighbouring scenario's craft just as readily.
        String durableId = ShipIdentity.nameFromAssembly(asm);
        String shipId = ShipIdentity.physicsIdOf(this::exec, 0, durableId);

        ShipInfo src = ShipInfo.byId(this::exec, 0, shipId);
        double sx = src.x, sy = src.y, sz = src.z;

        // A held throttle on THIS ship's own flight computer => a pilot is flying. Addressed by ship,
        // and the resolution is asserted: an input that reached nothing would leave the climb below
        // reading as unpiloted while claiming to be the piloted leg.
        String heldInput = exec("artest vs ff-input-by-id 0 " + shipId + " 0 1 0 0 0 0");
        assertTrue("the held input must reach this ship's flight computer: " + heldInput,
                Reply.of(heldInput).bool("afcResolved", false));
        String tp = exec("artest vs teleport-ship-by-id 0 " + shipId + " "
                + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz);
        assertTrue("climb teleport failed: " + tp, Reply.of(tp).ok());
        exec("artest vs unpark-by-id 0 " + shipId);
        // Keep the crossed ship loadable in its new slot while the async re-assembly settles.
        // (The Ticker drives ShipEntryController.tick() every server tick once the stack is installed.)

        // Wait on the ledger: the flight-computer tick fires the entry, the entry crosses + settles
        // the ship. The window is ARRANGEMENT, not the contract — what is asserted is that the ship
        // settles, not that it settles inside any particular stretch of anybody's afternoon. Budgeted
        // in the server's own ticks so that a busy machine buys the crossing exactly as much world as
        // an idle one does.
        boolean settled = awaitWithinTicks(SETTLE_TICKS,
                () -> {
                    EntryStatus seen = EntryStatus.forShip(this::exec, durableId);
                    return seen.found && seen.settled();
                },
                // Keep the destination slots' ships load-queued (headless has no player to auto-load
                // them). This is work the wait has to keep doing, not part of what is being waited for.
                () -> loadAllEntrySlots(setup));
        EntryStatus status = EntryStatus.forShip(this::exec, durableId);
        assertTrue("ship never entered space via the flight-computer tick (not SETTLED); last status="
                + status, settled);

        // The entry landed in the launch body's OWN cell — the C-1 resolution, matched gen-agnostically.
        assertEquals("entry settled in a different cell than the launch resolver answers", expectedCell,
                status.cellKey);
        int slotDim = status.slotDim;
        assertTrue("settled slot dim not reported: " + status, slotDim > Integer.MIN_VALUE);
        assertTrue("the settled ship's cell world is not live in a slot; status=" + status
                + " countAll=" + exec("artest vs ship-count-all " + slotDim),
                loadedShips(slotDim) >= 1);
    }

    /**
     * The leg AFTER the on-ramp: a ship that reached space through the production entry path can then JUMP
     * to another cell on the SAME live stack, driven only by the server tick.
     *
     * <p>Why this is not already covered: {@code VSShipTransitE2ETest} proves the transit state machine
     * on a FIXTURE cell pair of its own — hard-coded origin and target it never flew to by entering
     * space. Nothing joined the two halves, so a ship that actually FLEW into space had never been
     * jumped. The join is exactly where the previous hands-on tier-2 session found its blockers.</p>
     *
     * <p><i>This said that stack was ISOLATED — "its own {@code SpaceManager}", "advanced by manual
     * {@code transit-tick} calls" — until 2026-09-08, when the fixture moved onto the server's own
     * subsystem and the manual driving went with it. Both halves of that sentence had become false,
     * and a reason a test exists is the worst place to leave a stale one.</i></p>
     *
     * <p>The contract asserted here is the join, not the transit internals: a ship SETTLED by the entry path
     * departs its cell when a jump begins, and settles in the requested target cell without anything pumping
     * the manager by hand. CONTROL: the pre-jump cell is read from the ledger and asserted DIFFERENT from
     * the target, so "settled at the target" cannot pass by never having moved.</p>
     */
    @Test
    public void aShipThatEnteredSpaceCanJumpToAnotherCellOnTheLiveStack() throws Exception {

        String setup = exec("artest space entry-setup 2");
        assertTrue("entry setup failed: " + setup, Reply.of(setup).ok());

        clearArea(JUMP_SRC_X, JUMP_SRC_Z);
        String coords = placeFixture(JUMP_SRC_X, SRC_Y, JUMP_SRC_Z, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                (Reply.of(asm).integerOr("rocketCount", Integer.MIN_VALUE) == 0));
        assertTrue("the source VS ship never loaded", loadedShips(0) >= 1);

        String durableId = ShipIdentity.nameFromAssembly(asm);
        String shipId = ShipIdentity.physicsIdOf(this::exec, 0, durableId);

        ShipInfo src = ShipInfo.byId(this::exec, 0, shipId);
        double sx = src.x, sy = src.y, sz = src.z;
        String heldInput = exec("artest vs ff-input-by-id 0 " + shipId + " 0 1 0 0 0 0");
        assertTrue("the held input must reach this ship's flight computer: " + heldInput,
                Reply.of(heldInput).bool("afcResolved", false));
        assertTrue("climb teleport failed", Reply.of(exec("artest vs teleport-ship-by-id 0 " + shipId + " "
                + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz)).ok());
        exec("artest vs unpark-by-id 0 " + shipId);

        boolean settled = awaitWithinTicks(SETTLE_TICKS,
                () -> {
                    EntryStatus seen = EntryStatus.forShip(this::exec, durableId);
                    return seen.found && seen.settled();
                },
                () -> loadAllEntrySlots(setup));
        EntryStatus status = EntryStatus.forShip(this::exec, durableId);
        assertTrue("precondition: the ship never entered space, so there is nothing to jump; last status="
                + status, settled);
        int slotDim = status.slotDim;
        String originCell = status.cellKey;
        assertTrue("the entered ship's cell world is not live in a slot; status=" + status,
                loadedShips(slotDim) >= 1);

        // Jump it ONE sector over. The slot dim is passed explicitly: the console sender's own world is
        // the overworld, and the default would read that instead of the cell the ship is in.
        Matcher origin = CELL_KEY.matcher(originCell == null ? "" : originCell);
        assertTrue("entry-status reported no decodable origin cell key: " + status, origin.matches());
        String jump = exec("artest space jump id " + durableId + " "
                + (Integer.parseInt(origin.group(1)) + 1)
                + " " + origin.group(2) + " " + origin.group(3) + " " + slotDim);
        assertTrue("the jump probe found no settled ship to move: " + jump, Reply.of(jump).bool("began", false));
        // THIS ship departed. Without the id the verb jumps the cell's first settled row, so a cell
        // holding a second craft would carry that one away and report a successful jump.
        assertEquals("the jump named a different ship: " + jump,
                durableId, extractString(jump, "shipId"));
        String targetCell = extractString(jump, "toCell");
        assertTrue("jump reported no target cell: " + jump, targetCell != null);
        // CONTROL: a target equal to the origin would make the arrival assert vacuous.
        assertTrue("the jump target must differ from the origin cell, else arrival proves nothing: "
                + originCell + " -> " + targetCell, !targetCell.equals(originCell));

        // A completed jump leaves the ship AT the cell it was aimed at — the WHOLE key, not just the
        // axis the jump asked to move. Everything downstream of a jump reads that address: the
        // descent proximity check looks up the bodies of the ship's own cell, so an address in the
        // wrong cell means the destination system is not there at all.
        // Nobody rides a jump today, so the destination cell has NO observer — and VS loads a ship
        // only for one. Drop the headless keep-loaded crutch for the whole arrival: with it on, the
        // pasted ship becomes live for free and the arrival's own readiness gate is never tested,
        // which is how this leg stayed green while the same jump, flown by hand, gave up in the
        // paste lane. From here the arrival must make its own ship loadable.

        // Nothing below pumps the manager either: the live Ticker advances the transit every tick.
        boolean done = awaitWithinTicks(SETTLE_TICKS,
                () -> {
                    String seen = exec("artest space entry-status id " + durableId);
                    return "SETTLED".equals(extractString(seen, "state"))
                            && targetCell.equals(extractString(seen, "cellKey"));
                },
                null);
        String arrived = exec("artest space entry-status id " + durableId);
        assertTrue("the ship never arrived at the cell the jump was aimed at; origin=" + originCell
                + " requested=" + targetCell + " last status=" + arrived
                + " subsystem=" + SubsystemStatus.read(this::exec).raw(), done);

        // A ledger row written by the arrival itself proves only what the arrival BELIEVES. The
        // player's reading comes later, when he walks up to his ship: his presence loads it, its
        // flight computer starts ticking, and the address he sees is inverted from the ship's REAL
        // pose. An arrival that gave up in the paste lane still stamps the requested cell above and
        // only slips a sector once that first tick lands. So bring the observer in now — after the
        // jump, never during it — and read the address again.
        int arrivedSlot = extractInt(arrived, "slotDim");
        exec("artest vs load-ships " + arrivedSlot);
        // The jump RE-ASSEMBLED the hull, so the physics id it had in the origin cell names nothing
        // here; the durable id is the one thing that crossed. Ask the ledger's own bridge for the new
        // one, and refuse to go on without it — the alternative below was "whatever ship is nearest
        // (0,200,0) in the arrival slot", which is an address no craft was ever put at.
        String arrivedVsId = ShipIdentity.physicsIdOf(this::exec, arrivedSlot, durableId);
        final String[] pose = {""};
        /** How many ships the cell held at the instant the pose was sampled — for a failure only. */
        final int[] loadedInCell = {-1};
        // The ledger's own coordinate for this craft, kept from the last sample: the pose witness
        // below compares the ship against WHERE THE LEDGER SAYS IT IS, and both are read inside the
        // same window.
        final String[] ledgerRow = {""};
        // Sampled on the SLOT WORLD's clock, not the server's. What drifts is the ship, and the ship
        // drifts because its own flight computer ticks in that world - so the window has to be
        // measured in the ticks the subject runs on. The two clocks are not interchangeable here: a
        // slot world that had stopped ticking would let a server-clock window run its whole length
        // and report stability about a ship that was never asked to move.
        GameTicks.observe(client(), GameTicks.world(arrivedSlot),
                DRIFT_SAMPLES, DRIFT_TICKS_BETWEEN_SAMPLES, () -> {
                    exec("artest vs load-ships " + arrivedSlot);
                    // THE POSE FIRST, with nothing between it and the load-ships above. Reading
                    // the count before it inserted one probe round-trip into that gap, and the
                    // ship unloaded inside the gap often enough to red a healthy run (measured).
                    // A diagnostic that changes what it is measuring is worse than none.
                    //
                    // Asked BY NAME, so the reply is about this craft or about nothing: the cell's
                    // ship is unloaded and reloaded between samples (measured: count 0 mid-
                    // observation on a healthy run), and a nearest-ship lookup answers such a
                    // sample with whatever else is loaded rather than with a miss.
                    String poseNow = exec("artest vs ship-info " + arrivedSlot + " id " + arrivedVsId);
                    // The count travels WITH the pose rather than gating on it: the reader of a
                    // failure needs to know whether he is looking at an unloaded sample. It is kept
                    // BESIDE the reply and not appended to it — a reply with a word glued onto the
                    // end is no longer the JSON it claims to be, and every reader of it then fails
                    // with a parse error about the world.
                    pose[0] = poseNow;
                    loadedInCell[0] = extractInt(exec("artest vs ship-count " + arrivedSlot),
                            "count");
                    String held = exec("artest space entry-status id " + durableId);
                    ledgerRow[0] = held;
                    assertEquals("the arrived ship's address drifted out of the cell it flew to once"
                                    + " its flight computer began self-reporting its position;"
                                    + " status=" + held + " ship=" + pose[0]
                                    + " loadedShipsInCell=" + loadedInCell[0],
                            targetCell, extractString(held, "cellKey"));
                });
        // And the same fact read off the SHIP rather than off the ledger — the two compared where
        // they must agree: the ship's realized pose against the realization of the coordinate the
        // ledger holds for it.
        //
        // This used to ask whether the ship's Y had cleared HALF_CELL, on the reasoning that a cell
        // realized its contents megablocks up while an unsettled arrival was left at the staging
        // band's ordinary block Y. That discriminator was an artefact of the Y shift: centring the
        // pose band on 2026-09-11 put a settled ship at its cell's centre on world Y 0, which no
        // magnitude test can tell from anything. Comparing against the ledger's own coordinate is
        // what the check meant all along, and it survives the next change of mapping too.
        assertTrue("the arrived ship was never loaded in its destination cell: " + pose[0]
                        + " loadedShipsInCell=" + loadedInCell[0],
                ShipInfo.isLoaded(pose[0]));
        ShipInfo arrivedPose = ShipInfo.of(pose[0]);
        double[] expected = CellWorldMapper.poseWorldOf(GalacticCoord.ofSectorLocal(0L, 0L, 0L,
                (long) extractDouble(ledgerRow[0], "lx"),
                (long) extractDouble(ledgerRow[0], "ly"),
                (long) extractDouble(ledgerRow[0], "lz")));
        double[] actual = {arrivedPose.x, arrivedPose.y, arrivedPose.z};
        for (int axis = 0; axis < 3; axis++) {
            // CARRY_MARGIN as the tolerance, because it is production's OWN answer to "still at this
            // coordinate as far as the cell is concerned" — the distance a craft may sit past a face
            // before the seam carries it. A settle that landed further off than that has not landed.
            assertTrue("the arrived ship is not where its own ledger row says it is, so it never"
                            + " reached the coordinate the jump was aimed at. axis " + axis
                            + ": ship " + actual[axis] + " vs ledger " + expected[axis]
                            + " (tolerance " + CellSeam.CARRY_MARGIN + "). ship=" + pose[0]
                            + " loadedShipsInCell=" + loadedInCell[0]
                            + " ledger=" + ledgerRow[0],
                    Math.abs(actual[axis] - expected[axis]) <= CellSeam.CARRY_MARGIN);
        }
        assertEquals("nothing may still be in transit once the ledger reports arrival", 0,
                SubsystemStatus.read(this::exec).transits);
    }

    @After
    public void cleanup() throws Exception {
        exec("artest space entry-clear");
    }

    // --- helpers (mirror VSShipCrossingSpikeTest / VSShipTransitE2ETest) -----------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    /** Keep every slot world's ships load-queued while a wait runs. See {@link EntrySlots}. */
    private void loadAllEntrySlots(String setup) throws Exception {
        EntrySlots.loadAll(this::exec, setup);
    }

    /**
      * How many ships are loaded in this slot, once at least one is — budgeted in server ticks, so a
      * loaded machine is given the same number of chunk-load ticks as an idle one rather than the
      * same number of seconds.
      */
    /** How many ships are LOADED in {@code dim} right now. A read, not a wait: measured across this
     *  tier at one and at six forks, the ship is already loaded whenever a scenario asks. */
    private int loadedShips(int dim) throws Exception {
        return ShipReadiness.loadedCount(this::exec, dim);
    }

    private void clearArea(int baseX, int baseZ) throws Exception {
        int cx1 = (baseX - 4) >> 4, cz1 = (baseZ - 4) >> 4;
        int cx2 = (baseX + 20) >> 4, cz2 = (baseZ + 20) >> 4;
        assertTrue("chunk warmup failed",
                Reply.of(exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)).ok());
        assertTrue("pre-clear failed", Reply.of(exec("artest fill 0 " + (baseX - 4) + " " + (SRC_Y - 2) + " " + (baseZ - 4)
                + " " + (baseX + 20) + " " + (SRC_Y + 12) + " " + (baseZ + 20) + " minecraft:air")).ok());
    }

    private String placeFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, Reply.of(fixture).ok());
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp != null);
        return bp[0] + " " + bp[1] + " " + bp[2];
    }

    private static int extractInt(String json, String key) {
        return Reply.of(json).integerOr(key, Integer.MIN_VALUE);
    }

    private static double extractDouble(String json, String key) {
        return Reply.of(json).numberOr(key, 0.0);
    }

    private static String extractString(String json, String key) {
        return Reply.of(json).text(key);
    }
}
