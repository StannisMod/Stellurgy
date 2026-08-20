package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.GameTicks;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.HYPERSPACE_JUMP_SPEED;
import static org.junit.Assert.assertTrue;

/**
 * E2E: does the transit subsystem move a live VS ship between bubble cells? A ship assembled in a fresh
 * origin cell (pool slot world) departs into the shared hyperspace world, {@code ShipTransit} advances
 * its coordinate, and on arrival it crosses into a fresh target cell and re-VSes there. Proves the wiring
 * - the two per-ship crossings, the hyperspace hosting, and the origin&rarr;target refcount handoff -
 * composes in real, dynamically-registered worlds (not just the pure state machine's unit tests). Builds
 * on the proven per-ship crossing ({@code VSShipCrossingSpikeTest}) and on VS surviving in a pool-slot
 * world; the state machine itself is pinned deterministically by {@code ShipTransitManagerTest}.
 *
 * <p>Gated on the server's real VS presence (run with {@code -PwithVS}); skips cleanly otherwise.</p>
 */
public class VSShipTransitE2ETest extends AbstractSharedServerTest {

    /**
     * Budgets in SERVER TICKS — 400 is the twenty seconds the old {@code 80 x 250 ms} meant on an idle
     * box, 200 the ten seconds of {@code 40 x 250 ms}. Neither carries a fork multiplier: how much of
     * the machine this test shares says nothing about how much world an arrival needs.
     */
    private static final int ARRIVAL_TICKS = 400;
    private static final int LOAD_TICKS = 200;

    @Test
    public void aVsShipTransitsFromOneCellToAnotherThroughHyperspace() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath (run with -PwithVS)", serverHasVs());

        // Headless: pin ships loaded so a freshly assembled ship does not auto-unload between probe calls.
        exec("artest vs permaload true");

        // Build a VS ship in a fresh origin cell (a pool slot world) + the whole transit stack.
        String setup = exec("artest space transit-setup");
        assertTrue("transit setup failed: " + setup, setup.contains("\"ok\":true"));
        int originDim = extractInt(setup, "originDim");
        int ax = extractInt(setup, "anchorX"), ay = extractInt(setup, "anchorY"), az = extractInt(setup, "anchorZ");

        // The origin ship must exist + load before we depart it (the departure crossing snapshots it).
        assertTrue("origin ship never assembled/loaded in the pool-slot cell (dim " + originDim + ")",
                waitForLoadedShip(originDim) >= 1);

        // Depart: begin the jump. The ship leaves the origin cell for hyperspace — at a speed that
        // makes it a real flight, because a fast enough jump is performed as a single crossing instead
        // and this test is about the hyperspace path. (This fixture could not take the other path
        // anyway: its bare cube has no flight computer, so it has no durable id to be crossed under.)
        String begin = exec("artest space transit-begin " + originDim + " " + ax + " " + ay + " " + az
                + " " + HYPERSPACE_JUMP_SPEED);
        assertTrue("transit did not begin (departure crossing failed): " + begin, begin.contains("\"began\":true"));

        // Advance the transit until it arrives (arrival retries while the async hyperspace ship assembles).
        // The pump and the reading are one call, so both live in the condition; what the budget buys is
        // the WORLD in which the async assembly the arrival retries against can finish.
        final String[] lastTick = {""};
        GameTicks.until(client(), GameTicks.server(), ARRIVAL_TICKS, () -> {
            lastTick[0] = exec("artest space transit-tick 10");
            return extractInt(lastTick[0], "inTransit") == 0;
        });
        int targetDim = extractInt(lastTick[0], "inTransit") == 0
                ? extractInt(lastTick[0], "targetDim") : -1;
        assertTrue("ship never arrived (still in transit after " + ARRIVAL_TICKS + " ticks of world);"
                + " last tick=" + lastTick[0], targetDim >= 0);

        // The re-assembled ship must load + be VS-managed in the TARGET cell (arrival pastes near 0,200,0).
        assertTrue("transited ship never (re)loaded in the target cell (dim " + targetDim + "); countAll="
                + exec("artest vs ship-count-all " + targetDim), waitForLoadedShip(targetDim) >= 1);
        String dstInfo = exec("artest vs ship-info " + targetDim + " 0 200 0");
        assertTrue("arrived ship is not VS-managed in the target cell (transit did not re-VS): " + dstInfo,
                dstInfo.contains("\"managed\":true"));
    }

    @org.junit.After
    public void resetPermaload() throws Exception {
        if (serverHasVs()) {
            exec("artest vs permaload false");
        }
    }

    // --- helpers (mirror VSShipCrossingSpikeTest) ---------------------------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    /**
     * Poll for a loaded VS ship in {@code dim} (assembly is async; a headless server forces a load).
     *
     * <p>Budgeted on the SERVER's clock rather than {@code dim}'s: the world being asked about is
     * exactly the one that may not have started ticking, so budgeting against it would measure the
     * wait with the thing the wait is waiting for.</p>
     */
    private int waitForLoadedShip(int dim) throws Exception {
        final int[] loaded = {0};
        GameTicks.until(client(), GameTicks.server(), LOAD_TICKS, () -> {
            if (extractInt(exec("artest vs ship-count-all " + dim), "count") < 1) {
                return false;
            }
            exec("artest vs load-ships " + dim);
            loaded[0] = extractInt(exec("artest vs ship-count " + dim), "count");
            return loaded[0] >= 1;
        });
        return loaded[0];
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }
}
