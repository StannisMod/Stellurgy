package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.GameTicks;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

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
 * flight controller + entry state machine in a live world. Gated on {@code -PwithVS}; skips otherwise.</p>
 */
public class VSShipAutoTakeoffE2ETest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    private static final int SRC_X = 6500, SRC_Y = 80, SRC_Z = 6500;
    /** A short hop below the default orbit ceiling (1000), so the diagonal climb crosses it quickly. */
    private static final int NEAR_CEILING_Y = 985;

    /**
     * Budgets in SERVER TICKS, none fork-scaled: 100 for the autopilot's raycast to run on the AFC's
     * own tick and decline (the old 20 x 250 ms), 800 for the diagonal climb under force plus the
     * async entry (the old 160 x 250 ms), 200 for a ship becoming loadable.
     */
    private static final int DECLINE_TICKS = 100;
    private static final int CLIMB_TICKS = 800;
    private static final int LOAD_TICKS = 200;

    @Test
    public void autoTakeoffDeclinesWhenBlockedAndClimbsIntoSpaceWhenClear() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath (run with -PwithVS)", serverHasVs());

        exec("artest vs permaload true");
        String setup = exec("artest space entry-setup 2");
        assertTrue("entry setup failed: " + setup, setup.contains("\"ok\":true"));

        // Build + assemble a piloted ship in the overworld.
        clearArea(SRC_X, SRC_Z);
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("AFC build must route to a ship: " + asm, asm.contains("\"rocketCount\":0"));
        assertTrue("source ship never loaded", waitForLoadedShip(0) >= 1);

        String srcInfo = exec("artest vs ship-info 0 " + SRC_X + " " + SRC_Y + " " + SRC_Z);
        double sx = extractDouble(srcInfo, "posX"), sy = extractDouble(srcInfo, "posY"),
                sz = extractDouble(srcInfo, "posZ");

        // ---- DECLINE leg: put a solid slab directly overhead, engage, expect a self-disengage. ----
        // The corridor is a 45-degree diagonal, so it moves ~1 block sideways per block of climb; a
        // blocking slab must be CLOSE overhead and WIDE enough to intercept it before it exits the span.
        int slabY = (int) sy + 5;
        assertTrue("slab fill failed", exec("artest fill 0 " + ((int) sx - 20) + " " + slabY + " " + ((int) sz - 20)
                + " " + ((int) sx + 20) + " " + (slabY + 2) + " " + ((int) sz + 20) + " minecraft:stone")
                .contains("\"ok\":true"));
        // No manual FF input: the autopilot alone drives (its branch requires in == null). entry-setup
        // cleared any stale static input channel.
        String engaged = exec("artest space auto-takeoff 0");
        assertTrue("auto-takeoff did not engage: " + engaged, engaged.contains("\"engaged\":true"));

        // The raycast runs on the AFC's OWN tick, so this is a wait for that tick to happen a few
        // times - which is a number of ticks, not a number of seconds.
        final String[] status = {""};
        boolean declined = GameTicks.until(client(), GameTicks.server(), DECLINE_TICKS, () -> {
            status[0] = exec("artest space auto-takeoff 0 status");
            return status[0].contains("\"engaged\":false");
        });
        assertTrue("auto-takeoff did not decline a blocked corridor (still engaged): " + status[0],
                declined);

        // ---- CLIMB + ENTER leg: clear the slab, hop the ship just below the ceiling, engage, enter. ----
        assertTrue("slab clear failed", exec("artest fill 0 " + ((int) sx - 20) + " " + slabY + " " + ((int) sz - 20)
                + " " + ((int) sx + 20) + " " + (slabY + 2) + " " + ((int) sz + 20) + " minecraft:air")
                .contains("\"ok\":true"));
        String tp = exec("artest vs teleport-ship 0 " + (int) sx + " " + (int) sy + " " + (int) sz
                + " " + (int) sx + " " + NEAR_CEILING_Y + " " + (int) sz);
        assertTrue("hop teleport failed: " + tp, tp.contains("\"ok\":true"));
        exec("artest vs unpark 0 " + (int) sx + " " + NEAR_CEILING_Y + " " + (int) sz);
        String reEngage = exec("artest space auto-takeoff 0");
        assertTrue("auto-takeoff did not re-engage over a clear corridor: " + reEngage,
                reEngage.contains("\"engaged\":true"));

        boolean settled = false;
        final String[] entry = {""};
        settled = GameTicks.until(client(), GameTicks.server(), CLIMB_TICKS,
                () -> {
                    entry[0] = exec("artest space entry-status");
                    return extractInt(entry[0], "ships") >= 1
                            && "SETTLED".equals(extractString(entry[0], "state"));
                },
                () -> loadAllEntrySlots(setup));
        assertTrue("auto-takeoff never climbed the ship into space (not SETTLED); last=" + entry[0],
                settled);
    }

    @After
    public void cleanup() throws Exception {
        if (serverHasVs()) {
            exec("artest space entry-clear");
            exec("artest vs permaload false");
        }
    }

    // --- helpers ------------------------------------------------------------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private void loadAllEntrySlots(String setup) throws Exception {
        Matcher m = Pattern.compile("\"dims\":\\[(-?\\d+),(-?\\d+)]").matcher(setup);
        if (m.find()) {
            exec("artest vs load-ships " + m.group(1));
            exec("artest vs load-ships " + m.group(2));
        }
    }

    private int waitForLoadedShip(int dim) throws Exception {
        return GameTicks.until(client(), GameTicks.server(), LOAD_TICKS, () -> {
            if (extractInt(exec("artest vs ship-count-all " + dim), "count") < 1) {
                return false;
            }
            exec("artest vs load-ships " + dim);
            return extractInt(exec("artest vs ship-count " + dim), "count") >= 1;
        }) ? 1 : 0;
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
