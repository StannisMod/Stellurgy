package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.test.GameTicks;

import org.junit.After;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.awaitWithinTicks;
import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.SHIP_CAPTURE_RADIUS_BLOCKS;

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

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

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
    private static final int LOAD_TICKS = 200;

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
    private static final int SRC_X = 6000, SRC_Y = 80, SRC_Z = 6000;
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
        exec("artest vs permaload true");
        // Install the entry stack into SpaceSubsystem so the PRODUCTION trigger path runs under the harness.
        String setup = exec("artest space entry-setup 2");
        assertTrue("entry setup failed: " + setup, setup.contains("\"ok\":true"));

        // CONTROL: nothing is ledgered before the climb — a later "settled" is then a real observation.
        String control = exec("artest space entry-status");
        assertEquals("witness sensitivity control — no ship must be ledgered before the climb: " + control,
                0, extractInt(control, "ships"));

        // Build a piloted tier-2 ship in the overworld and assemble it into a VS ship.
        clearArea(SRC_X, SRC_Z);
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                asm.contains("\"rocketCount\":0"));
        assertTrue("the source VS ship never loaded", waitForLoadedShip(0) >= 1);

        // The cell the production resolver answers for the launch dimension — the entry MUST land here.
        String launch = exec("artest space launch-cell 0");
        assertTrue("launch-cell resolve failed: " + launch, launch.contains("\"ok\":true"));
        String expectedCell = extractString(launch, "cellKey");
        assertTrue("launch dim resolved to no cell: " + launch, expectedCell != null);

        // Locate the ship, then arrange the entry preconditions: a pilot (the static FF input channel makes
        // the AFC tick see "someone is flying") and a climb PAST the ceiling (rigid-teleport to Y=1200).
        String srcInfo = exec("artest vs ship-info 0 " + SRC_X + " " + SRC_Y + " " + SRC_Z
                + " " + SHIP_CAPTURE_RADIUS_BLOCKS);
        assertTrue("source ship not managed by VS: " + srcInfo, srcInfo.contains("\"managed\":true"));
        double sx = extractDouble(srcInfo, "posX"), sy = extractDouble(srcInfo, "posY"),
                sz = extractDouble(srcInfo, "posZ");

        // A held throttle on THIS ship's own flight computer => a pilot is flying. Addressed by ship,
        // and the resolution is asserted: an input that reached nothing would leave the climb below
        // reading as unpiloted while claiming to be the piloted leg.
        String heldInput = exec("artest vs ff-input-by-id 0 " + extractString(srcInfo, "id") + " 0 1 0 0 0 0");
        assertTrue("the held input must reach this ship's flight computer: " + heldInput,
                heldInput.contains("\"afcResolved\":true"));
        String tp = exec("artest vs teleport-ship 0 " + (int) sx + " " + (int) sy + " " + (int) sz
                + " " + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz);
        assertTrue("climb teleport failed: " + tp, tp.contains("\"ok\":true"));
        exec("artest vs unpark 0 " + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz);
        // Keep the crossed ship loadable in its new slot while the async re-assembly settles.
        // (The Ticker drives ShipEntryController.tick() every server tick once the stack is installed.)

        // Wait on the ledger: the flight-computer tick fires the entry, the entry crosses + settles
        // the ship. The window is ARRANGEMENT, not the contract — what is asserted is that the ship
        // settles, not that it settles inside any particular stretch of anybody's afternoon. Budgeted
        // in the server's own ticks so that a busy machine buys the crossing exactly as much world as
        // an idle one does.
        boolean settled = awaitWithinTicks(SETTLE_TICKS,
                () -> {
                    String seen = exec("artest space entry-status");
                    return extractInt(seen, "ships") >= 1
                            && "SETTLED".equals(extractString(seen, "state"));
                },
                // Keep the destination slots' ships load-queued (headless has no player to auto-load
                // them). This is work the wait has to keep doing, not part of what is being waited for.
                () -> loadAllEntrySlots(setup));
        String status = exec("artest space entry-status");
        assertTrue("ship never entered space via the flight-computer tick (not SETTLED); last status="
                + status, settled);

        // The entry landed in the launch body's OWN cell — the C-1 resolution, matched gen-agnostically.
        assertEquals("entry settled in a different cell than the launch resolver answers", expectedCell,
                extractString(status, "cellKey"));
        int slotDim = extractInt(status, "slotDim");
        assertTrue("settled slot dim not reported: " + status, slotDim > Integer.MIN_VALUE);
        assertTrue("the settled ship's cell world is not live in a slot; status=" + status
                + " countAll=" + exec("artest vs ship-count-all " + slotDim),
                waitForLoadedShip(slotDim) >= 1);
    }

    /**
     * The leg AFTER the on-ramp: a ship that reached space through the production entry path can then JUMP
     * to another cell on the SAME live stack, driven only by the server tick.
     *
     * <p>Why this is not already covered: {@code VSShipTransitE2ETest} proves the transit state machine on
     * an ISOLATED stack — its own {@code SpaceManager}, its own hard-coded origin/target, advanced by manual
     * {@code transit-tick} calls. Nothing joined the two halves, so a ship that actually FLEW into space had
     * never been jumped. The join is exactly where the previous hands-on tier-2 session found its blockers.</p>
     *
     * <p>The contract asserted here is the join, not the transit internals: a ship SETTLED by the entry path
     * departs its cell when a jump begins, and settles in the requested target cell without anything pumping
     * the manager by hand. CONTROL: the pre-jump cell is read from the ledger and asserted DIFFERENT from
     * the target, so "settled at the target" cannot pass by never having moved.</p>
     */
    @Test
    public void aShipThatEnteredSpaceCanJumpToAnotherCellOnTheLiveStack() throws Exception {

        exec("artest vs permaload true");
        String setup = exec("artest space entry-setup 2");
        assertTrue("entry setup failed: " + setup, setup.contains("\"ok\":true"));

        clearArea(JUMP_SRC_X, JUMP_SRC_Z);
        String coords = placeFixture(JUMP_SRC_X, SRC_Y, JUMP_SRC_Z, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                asm.contains("\"rocketCount\":0"));
        assertTrue("the source VS ship never loaded", waitForLoadedShip(0) >= 1);

        String srcInfo = exec("artest vs ship-info 0 " + JUMP_SRC_X + " " + SRC_Y + " " + JUMP_SRC_Z
                + " " + SHIP_CAPTURE_RADIUS_BLOCKS);
        assertTrue("source ship not managed by VS: " + srcInfo, srcInfo.contains("\"managed\":true"));
        double sx = extractDouble(srcInfo, "posX"), sy = extractDouble(srcInfo, "posY"),
                sz = extractDouble(srcInfo, "posZ");
        String heldInput = exec("artest vs ff-input-by-id 0 " + extractString(srcInfo, "id") + " 0 1 0 0 0 0");
        assertTrue("the held input must reach this ship's flight computer: " + heldInput,
                heldInput.contains("\"afcResolved\":true"));
        assertTrue("climb teleport failed", exec("artest vs teleport-ship 0 " + (int) sx + " " + (int) sy
                + " " + (int) sz + " " + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz)
                .contains("\"ok\":true"));
        exec("artest vs unpark 0 " + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz);

        boolean settled = awaitWithinTicks(SETTLE_TICKS,
                () -> {
                    String seen = exec("artest space entry-status");
                    return extractInt(seen, "ships") >= 1
                            && "SETTLED".equals(extractString(seen, "state"));
                },
                () -> loadAllEntrySlots(setup));
        String status = exec("artest space entry-status");
        assertTrue("precondition: the ship never entered space, so there is nothing to jump; last status="
                + status, settled);
        int slotDim = extractInt(status, "slotDim");
        String originCell = extractString(status, "cellKey");
        assertTrue("the entered ship's cell world is not live in a slot; status=" + status,
                waitForLoadedShip(slotDim) >= 1);

        // Jump it ONE sector over. The slot dim is passed explicitly: the console sender's own world is
        // the overworld, and the default would read that instead of the cell the ship is in.
        Matcher origin = CELL_KEY.matcher(originCell == null ? "" : originCell);
        assertTrue("entry-status reported no decodable origin cell key: " + status, origin.matches());
        String jump = exec("artest space jump " + (Integer.parseInt(origin.group(1)) + 1)
                + " " + origin.group(2) + " " + origin.group(3) + " " + slotDim);
        assertTrue("the jump probe found no settled ship to move: " + jump, jump.contains("\"began\":true"));
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
        exec("artest vs permaload false");

        // Nothing below pumps the manager either: the live Ticker advances the transit every tick.
        boolean done = awaitWithinTicks(SETTLE_TICKS,
                () -> {
                    String seen = exec("artest space entry-status");
                    return "SETTLED".equals(extractString(seen, "state"))
                            && targetCell.equals(extractString(seen, "cellKey"));
                },
                null);
        String arrived = exec("artest space entry-status");
        assertTrue("the ship never arrived at the cell the jump was aimed at; origin=" + originCell
                + " requested=" + targetCell + " last status=" + arrived
                + " subsystem=" + exec("artest space subsystem-status"), done);

        // A ledger row written by the arrival itself proves only what the arrival BELIEVES. The
        // player's reading comes later, when he walks up to his ship: his presence loads it, its
        // flight computer starts ticking, and the address he sees is inverted from the ship's REAL
        // pose. An arrival that gave up in the paste lane still stamps the requested cell above and
        // only slips a sector once that first tick lands. So bring the observer in now — after the
        // jump, never during it — and read the address again.
        int arrivedSlot = extractInt(arrived, "slotDim");
        exec("artest vs load-ships " + arrivedSlot);
        final String[] pose = {""};
        // Sampled on the SLOT WORLD's clock, not the server's. What drifts is the ship, and the ship
        // drifts because its own flight computer ticks in that world - so the window has to be
        // measured in the ticks the subject runs on. The two clocks are not interchangeable here: a
        // slot world that had stopped ticking would let a server-clock window run its whole length
        // and report stability about a ship that was never asked to move.
        GameTicks.observe(client(), GameTicks.world(arrivedSlot),
                DRIFT_SAMPLES, DRIFT_TICKS_BETWEEN_SAMPLES, () -> {
                    exec("artest vs load-ships " + arrivedSlot);
                    // The pose is DIAGNOSTIC — it appears only in the failure message below, while
                    // the assertion is about the cell key. So the loaded-ship count travels WITH it
                    // rather than gating on it: a nearest-ship answer is attributable only while the
                    // cell holds one ship, and this loop samples a cell whose ship VS unloads and
                    // reloads between samples (measured: count 0 mid-observation on a healthy run).
                    // Asserting here would have turned that into a red about nothing.
                    // THE POSE FIRST, with nothing between it and the load-ships above. Reading
                    // the count before it inserted one probe round-trip into that gap, and the
                    // ship unloaded inside the gap often enough to red a healthy run (measured).
                    // A diagnostic that changes what it is measuring is worse than none.
                    String poseNow = exec("artest vs ship-info " + arrivedSlot + " 0 200 0");
                    // The count travels WITH the pose rather than gating on it: a nearest-ship
                    // answer is attributable only while the cell holds one ship, and the reader of
                    // a failure needs to know which case he is looking at.
                    pose[0] = poseNow + " loadedShipsInCell="
                            + extractInt(exec("artest vs ship-count " + arrivedSlot), "count");
                    String held = exec("artest space entry-status");
                    assertEquals("the arrived ship's address drifted out of the cell it flew to once"
                                    + " its flight computer began self-reporting its position;"
                                    + " status=" + held + " ship=" + pose[0],
                            targetCell, extractString(held, "cellKey"));
                });
        // And the same fact read off the ship rather than off the ledger: a cell realizes its
        // contents in the pose band, while an arrival that never settled is left in the paste lane's
        // ordinary block Y. The band floor is the discriminator — no exact pose is pinned.
        assertTrue("the arrived ship was never loaded in its destination cell: " + pose[0],
                pose[0].contains("\"managed\":true"));
        assertTrue("the arrived ship is not in its cell's pose band, so it never reached the"
                        + " coordinate the jump was aimed at (band floor " + GalacticCoord.HALF_CELL
                        + ", the paste lane sits near ordinary block Y): " + pose[0],
                extractDouble(pose[0], "posY") > GalacticCoord.HALF_CELL);
        assertEquals("nothing may still be in transit once the ledger reports arrival", 0,
                extractInt(exec("artest space subsystem-status"), "transits"));
    }

    @After
    public void cleanup() throws Exception {
        exec("artest space entry-clear");
        exec("artest vs permaload false");
    }

    // --- helpers (mirror VSShipCrossingSpikeTest / VSShipTransitE2ETest) -----------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private void loadAllEntrySlots(String setup) throws Exception {
        Matcher m = Pattern.compile("\"dims\":\\[(-?\\d+),(-?\\d+)]").matcher(setup);
        if (m.find()) {
            exec("artest vs load-ships " + m.group(1));
            exec("artest vs load-ships " + m.group(2));
        }
    }

    /**
      * How many ships are loaded in this slot, once at least one is — budgeted in server ticks, so a
      * loaded machine is given the same number of chunk-load ticks as an idle one rather than the
      * same number of seconds.
      */
    private int waitForLoadedShip(int dim) throws Exception {
        boolean loaded = awaitWithinTicks(LOAD_TICKS,
                () -> {
                    if (extractInt(exec("artest vs ship-count-all " + dim), "count") < 1) {
                        return false;
                    }
                    exec("artest vs load-ships " + dim);
                    return extractInt(exec("artest vs ship-count " + dim), "count") >= 1;
                },
                null);
        return loaded ? extractInt(exec("artest vs ship-count " + dim), "count") : 0;
    }

    private void clearArea(int baseX, int baseZ) throws Exception {
        int cx1 = (baseX - 4) >> 4, cz1 = (baseZ - 4) >> 4;
        int cx2 = (baseX + 20) >> 4, cz2 = (baseZ + 20) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed", exec("artest fill 0 " + (baseX - 4) + " " + (SRC_Y - 2) + " " + (baseZ - 4)
                + " " + (baseX + 20) + " " + (SRC_Y + 12) + " " + (baseZ + 20) + " minecraft:air").contains("\"ok\":true"));
    }

    private String placeFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp.find());
        return bp.group(1) + " " + bp.group(2) + " " + bp.group(3);
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
