package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.GameTicks;

import org.junit.Ignore;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.SHIP_CAPTURE_RADIUS_BLOCKS;

/**
 * Substrate checkpoint — proves Valkyrien Skies physics is LIVE in the test
 * harness: a bare AR-assembled tier-2 ship, given a direct linear-velocity
 * setpoint (flight-control model A), actually translates through VS's physics
 * loop. This is the load-bearing precondition for any pilotable-ship test — if a
 * setpoint did not move the ship here, model A ({@code ShipPhysicsData} velocity
 * setpoint) would be invalid and a flight/piloting e2e built on it would be
 * false-green.
 *
 *
 * <p>The push is horizontal (+Z) to keep gravity out of the measured delta, and is
 * re-applied every step — mirroring the per-tick setpoint the Advanced Flight
 * Computer will issue, and defeating VS's linear damping between steps. Ships are
 * addressed by nearest-to-build-site, so a shared server carrying the tier-gate
 * test's ship does not contaminate this one.</p>
 */
@Ignore("A Valkyrien Skies ship assembled on a HEADLESS server never becomes loaded — "
        + "with no client/observer near it, VS leaves it in the registry but never pulls its "
        + "chunks in, so the server tier can neither drive nor observe it (queryable count goes "
        + "to 1, loaded count stays 0). Kept as executable documentation of that limit; the "
        + "ship load + drive path is exercised at the client tier instead. Re-enable only if a "
        + "server-side force-load of a bare ship is added.")
public class VSShipMotionServerTest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");

    private static final String VARIANT = "with-advanced-flight-computer";

    // Distinct from AdvancedFlightComputerTierGateTest's sites (1200 / 1600) so the
    // nearest-ship probe never picks up that test's lingering ship on the shared server.
    private static final int BX = 2200, BY = 64, BZ = 2200;

    /**
     * Budgets in SERVER TICKS. The assembly relocation and the force-load are both driven by the
     * server's own tick loop, so that is the clock they are asked for - and none of these carries a
     * fork multiplier, because how much of the machine this test shares says nothing about how many
     * ticks the work needs.
     */
    private static final int REGISTER_TICKS = 400;
    private static final int LOAD_TICKS = 400;

    /** How many velocity setpoints are applied, and how much world separates them. */
    private static final int PUSHES = 25;
    private static final int TICKS_BETWEEN_PUSHES = 1;

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    @Test
    public void directVelocitySetpointTranslatesTheAssembledShip() throws Exception {

        // Assemble the tier-2 ship — with VS this routes to a ship (no rocket) and
        // queues an async VS relocation.
        String assemble = assembleFixture(BX, BY, BZ, VARIANT);
        assertTrue("with VS, the AFC build must route to a ship (no rocket): " + assemble,
                assemble.contains("\"rocketCount\":0"));

        // 1) Wait for the ship to appear in the queryable registry (VS relocates blocks
        //    into a ship on its own thread).
        final int[] all = {0};
        GameTicks.until(client(), GameTicks.server(), REGISTER_TICKS, () -> {
            all[0] = shipCount("ship-count-all");
            return all[0] >= 1;
        });
        assertTrue("assembly must create a VS ship in the queryable registry (all=" + all[0] + ")",
                all[0] >= 1);

        // 2) A headless server has no player near the ship to auto-load it, so it stays
        //    unloaded/dormant. Force it loaded + physics-enabled (a nearby client does
        //    this itself in real play).
        String load = exec("artest vs load-ships 0");
        assertTrue("load-ships must request the ship: " + load, load.contains("\"requested\":1"));

        // 3) Wait for it to become loaded, then take its IDENTITY and snapshot its position. The
        //    positional lookup is used exactly once, here, while this ship is provably the only one
        //    at the build spot; everything below asks by id, which cannot start answering for a
        //    neighbour once this one has been pushed ten blocks away.
        double zBefore = Double.NaN;
        final String[] shipId = {null};
        final StringBuilder loadTrace = new StringBuilder();
        final double[] z = {Double.NaN};
        GameTicks.until(client(), GameTicks.server(), LOAD_TICKS, () -> {
            int loaded = shipCount("ship-count");
            loadTrace.append(loaded).append(' ');
            if (loaded < 1) {
                return false;
            }
            String info = exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ
                    + " " + SHIP_CAPTURE_RADIUS_BLOCKS);
            if (!info.contains("\"managed\":true")) {
                return false;
            }
            z[0] = shipPosZ(info);
            shipId[0] = shipIdOf(info);
            return !Double.isNaN(z[0]);
        });
        zBefore = z[0];
        assertTrue("ship must become loaded after force-load — loaded over time: ["
                        + loadTrace.toString().trim() + "], all=" + all[0],
                !Double.isNaN(zBefore));

        // Command a steady +Z velocity each step, re-applying every step: that mirrors the AFC's
        // per-tick setpoint and defeats VS damping. The gap between pushes is a TICK, because what
        // decays the velocity between them is the physics step, not the passage of a millisecond —
        // on a loaded box the old 60 ms gap let a variable number of steps damp it.
        double vz = 10.0; // blocks/second
        GameTicks.observe(client(), GameTicks.server(), PUSHES, TICKS_BETWEEN_PUSHES, () -> {
            String push = exec("artest vs push-ship-by-id 0 " + shipId[0] + " 0 0 " + vz);
            assertTrue("push-ship-by-id must find the ship: " + push, push.contains("\"pushed\":true"));
        });
        double zAfter = shipPosZ(exec("artest vs ship-info 0 id " + shipId[0]));

        // Model A holds: a velocity setpoint moves a bare AR-assembled ship. A strict
        // displacement (not merely "changed") pins that VS integrated the commanded
        // velocity into position — a substrate that ignored the setpoint (recompute
        // from forces, or damp to zero) would leave the ship put.
        assertTrue("commanded +Z velocity must translate the ship through VS physics "
                        + "(zBefore=" + zBefore + " zAfter=" + zAfter + ")",
                zAfter - zBefore > 1.0);
    }

    private int shipCount(String sub) throws Exception {
        Matcher m = Pattern.compile("\"count\":(-?\\d+)").matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private double shipPosZ(String shipInfoJson) {
        Matcher m = POS_Z.matcher(shipInfoJson);
        assertTrue("ship-info must carry posZ: " + shipInfoJson, m.find());
        return Double.parseDouble(m.group(1));
    }

    /** The ship's own identity out of a {@code ship-info} reply — captured once, used thereafter. */
    private String shipIdOf(String shipInfoJson) {
        Matcher m = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(shipInfoJson);
        assertTrue("ship-info must name WHICH ship answered: " + shipInfoJson, m.find());
        return m.group(1);
    }

    /** Place the fixture on a pad and run scan+assemble; returns the raw assemble JSON. */
    private String assembleFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        int bx = Integer.parseInt(bp.group(1)),
                by = Integer.parseInt(bp.group(2)),
                bz = Integer.parseInt(bp.group(3));
        String assemble = exec("artest rocket assemble 0 " + bx + " " + by + " " + bz);
        assertTrue("assemble failed: " + assemble, assemble.contains("\"ok\":true"));
        return assemble;
    }
}
