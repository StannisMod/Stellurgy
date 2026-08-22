package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.GameTicks;

import org.junit.After;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.SHIP_CAPTURE_RADIUS_BLOCKS;

/**
 * Substrate checkpoint — proves Valkyrien Skies physics is LIVE at the SERVER tier: a bare
 * AR-assembled tier-2 ship, commanded through its own flight computer, actually translates through
 * VS's physics loop. This is the load-bearing precondition for every pilotable-ship test — if a
 * command did not move the ship here, an e2e built on it would be false-green for reasons nothing
 * else in the suite would report.
 *
 * <p>The command is horizontal (+Z) to keep gravity out of the measured delta. Ships are addressed
 * by nearest-to-build-site exactly once, to learn this craft's identity; everything after asks by
 * id, so a shared server carrying another test's ship cannot start answering for it.</p>
 *
 * <p><b>Two things this class was wrong about until 2026-08-22, both measured rather than argued.</b>
 * It was disabled for eight weeks on the premise that "a VS ship assembled on a HEADLESS server
 * never becomes loaded", with the note that it should be re-enabled once a server-side force-load
 * existed. That probe ({@code vs permaload}) was added eight days after the class was parked, and
 * nobody came back: the ship loads, and the scenario runs. And the behaviour it pinned —
 * flight-control "model A", a direct {@code ShipPhysicsData} velocity setpoint — does NOT move a
 * ship, which is why the drive below goes through the flight computer instead. The dead model is
 * kept as the control leg, because a checkpoint that cannot tell a working drive from a broken one
 * is not a checkpoint.</p>
 */
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

    /** The commanded speed, in blocks/second, and how long it is given to move the craft. */
    private static final double COMMANDED_VZ = 10.0;
    private static final int DRIVE_TICKS = 25;

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    @After
    public void cleanup() throws Exception {
        exec("artest vs permaload false");
    }

    @Test
    public void aCommandedVelocityTranslatesTheShipWhileARawSetpointDoesNot() throws Exception {

        // A headless server has nobody standing near this craft, and an unattended ship UNLOADS
        // again between probe calls — a one-shot load request is not enough, because the ship is
        // gone from the loaded set by the time the next call asks about it. This is what the class
        // was disabled for; the probe that holds a ship loaded server-side has existed since
        // 2026-07-13.
        exec("artest vs permaload true");

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

        // CONTROL, and the reason this class was rewritten: a raw velocity SETPOINT does not move a
        // ship. Measured 2026-08-22 on the very run that re-enabled the class — 25 setpoints of
        // 10 b/s applied a tick apart left the craft where it was (zBefore=2203.0, zAfter=2202.4).
        // The substrate recomputes velocity from forces every physics step and overwrites what was
        // written, so the write is not a command; the probe surface says as much in one line beside
        // `force-vel-by-id` ("a velocity setpoint alone does nothing"). Asserting it here, rather
        // than deleting it, is what stops the class quietly going back to the setpoint.
        String setpoint = exec("artest vs push-ship-by-id 0 " + shipId[0] + " 0 0 " + COMMANDED_VZ);
        assertTrue("push-ship-by-id must find the ship: " + setpoint, setpoint.contains("\"pushed\":true"));
        GameTicks.advance(client(), GameTicks.server(), DRIVE_TICKS);
        double zAfterSetpoint = shipPosZ(exec("artest vs ship-info 0 id " + shipId[0]));
        assertTrue("a raw velocity setpoint must NOT be mistaken for a working drive: the ship moved "
                        + (zAfterSetpoint - zBefore) + " blocks on a bare setpoint, which means this"
                        + " control has stopped controlling and the test below no longer proves the"
                        + " CONTROLLER moved anything",
                Math.abs(zAfterSetpoint - zBefore) < 1.0);

        // THE SUBJECT: the path production actually flies. `force-vel-by-id` commands a world-frame
        // velocity that the ship's OWN flight computer realizes as force, once per physics tick, for
        // as long as it stands — so this is one command and then time, not a setpoint re-written
        // every tick against a substrate that keeps discarding it.
        String drive = exec("artest vs force-vel-by-id 0 " + shipId[0] + " 0 0 " + COMMANDED_VZ);
        assertTrue("the command must reach THIS ship's own flight computer: " + drive,
                drive.contains("\"afcResolved\":true"));
        GameTicks.advance(client(), GameTicks.server(), DRIVE_TICKS);
        double zAfter = shipPosZ(exec("artest vs ship-info 0 id " + shipId[0]));

        // A strict displacement, not merely "changed": it pins that VS integrated the commanded
        // motion into position. A substrate that ignored the command, or damped it to zero, would
        // leave the ship put — and the control above proves that outcome is reachable here.
        assertTrue("a commanded +Z velocity must translate the ship through VS physics "
                        + "(zBefore=" + zBefore + " zAfterSetpoint=" + zAfterSetpoint
                        + " zAfter=" + zAfter + ")",
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
