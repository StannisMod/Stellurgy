package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.RocketInfo;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Server-side e2e for the Flight Assist velocity-setpoint law
 * through the real probe surface:
 *  - FA defaults ON and round-trips through set-flight-assist;
 *  - the cut flag (X) travels the input wire and is stored;
 *  - holding forward RAMPS the setpoint, releasing KEEPS the cruise;
 *  - cut eases the craft into a gravity-cancelled hover (not a fall);
 *  - the setpoint is body-frame: yawing rotates the world velocity;
 *  - re-enabling FA captures the current velocity (no jerk);
 *  - FA off remains raw Newtonian, with the manual brake still honoured.
 */
public class FreeFlightAssistsE2ETest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    /** What the server says about one craft, read through the verb's own reader. */
    private RocketInfo rocketInfo(int id) throws Exception {
        return RocketInfo.byId(cmd -> ok(client().execute(cmd)), id);
    }

    private int buildAndAssemble(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the
        // body below unchanged, so what moved is visible in one place.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume this craft is built and flown in is EMPTY. The site
        // stands in open air, so this ASSERTS rather than digs - anything standing here
        // means the arrangement is wrong, and it is said now instead of arriving many
        // links later wearing some mechanic's name.
        site.requireClear(cmd -> ok(client().execute(cmd)), 2, 50,
                "the craft is built and flown in this volume");

        String fixture = ok(client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple"));
        assertTrue("fixture failed: " + fixture, fixture.contains("\"ok\":true"));
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture missing builderPos: " + fixture, bp != null);
        int bx = bp[0];
        int by = bp[1];
        int bz = bp[2];

        String assemble = ok(client().execute(
                "artest rocket assemble 0 " + bx + " " + by + " " + bz));
        assertTrue("assemble failed: " + assemble, assemble.contains("\"ok\":true"));

        String list = ok(client().execute("artest rocket list 0"));
        java.util.List<RocketList.Entry> built = RocketList.of(list);
        assertTrue("rocket list empty after assemble: " + list, !built.isEmpty());
        return built.get(built.size() - 1).id;
    }

    // -----------------------------------------------------------------

    @Test
    public void flightAssistDefaultsOnAndTogglesThroughProbe() throws Exception {
        int id = buildAndAssemble(FixtureSite.openAir(0, 4000, 500));
        RocketInfo info0 = rocketInfo(id);
        assertTrue("FA must default to true: " + info0.raw(), info0.flightAssistOn);

        String off = ok(client().execute("artest rocket set-flight-assist " + id + " off"));
        assertTrue("set-flight-assist off must succeed: " + off,
                off.contains("\"ok\":true") && off.contains("\"flightAssistOn\":false"));

        RocketInfo info1 = rocketInfo(id);
        assertFalse("info must round-trip FA=false: " + info1.raw(), info1.flightAssistOn);

        ok(client().execute("artest rocket set-flight-assist " + id + " on"));
        RocketInfo info2 = rocketInfo(id);
        assertTrue("info must round-trip FA=true after flip-back: " + info2.raw(),
                info2.flightAssistOn);
    }

    @Test
    public void setFlightAssistRejectsBadValue() throws Exception {
        int id = buildAndAssemble(FixtureSite.openAir(0, 4050, 500));
        String resp = ok(client().execute(
                "artest rocket set-flight-assist " + id + " wat"));
        assertTrue("bad value must report error: " + resp,
                resp.contains("\"error\":\"bad value"));
    }

    @Test
    public void cutFlagThroughInputIsStoredOnServer() throws Exception {
        int id = buildAndAssemble(FixtureSite.openAir(0, 4100, 500));
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        // cut=1 at position 7.
        String applied = ok(client().execute(
                "artest rocket free-flight-input " + id + " 0 0 0 0 0 1"));
        assertTrue("input must apply on FF rocket: " + applied,
                applied.contains("\"applied\":true"));
        assertTrue("probe echoes cut=true: " + applied,
                applied.contains("\"cut\":true"));

        RocketInfo info = rocketInfo(id);
        assertTrue("info must store ffInputCut=true: " + info.raw(),
                info.freeFlightInput().cut);
    }

    @Test
    public void setpointPersistsAfterReleasingTheKey() throws Exception {
        // THE Flight Assist feature: holding forward RAMPS the
        // velocity setpoint; releasing the key KEEPS it — the craft cruises
        // hands-off instead of coasting down.
        int id = buildAndAssemble(FixtureSite.openAir(0, 4150, 500));
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        // Hold forward+up for 20 ticks: the setpoint ramps to ~1.0 blocks/tick
        // on both axes. The upward component keeps the cruise climbing away
        // from terrain — the world outside the cleared pad column is random.
        ok(client().execute("artest rocket free-flight-input " + id + " 1 1 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 20"));

        // Release (all-zero input) and keep flying.
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 40"));

        RocketInfo info = rocketInfo(id);
        assertTrue("released key must NOT bleed the cruise (motionZ=" + info.motionZ
                + ", expected to keep cruising +Z): " + info.raw(), info.motionZ > 0.5);
        assertTrue("setpoint must persist on the server: " + info.raw(), info.hasFaSetpoint());
    }

    @Test
    public void cutEasesTheCraftIntoAGravityCancelledHover() throws Exception {
        // X (cut): zero the setpoint -> FA damps motion to zero AND holds
        // altitude (gravity cancelled) — brake-to-hover, not brake-to-fall.
        int id = buildAndAssemble(FixtureSite.openAir(0, 4200, 500));
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        // Build a climbing cruise first (also cancels the liftoff assist; the
        // upward component keeps it clear of un-cleared terrain).
        ok(client().execute("artest rocket free-flight-input " + id + " 1 1 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 20"));

        // Cut.
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 0 0 0 1"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 60"));

        RocketInfo info = rocketInfo(id);
        assertTrue("cut must ease the cruise to a stop (motionZ=" + info.motionZ + ")",
                Math.abs(info.motionZ) < 0.05);
        assertTrue("cut must HOLD ALTITUDE, not drop the craft (motionY=" + info.motionY + ")",
                Math.abs(info.motionY) < 0.05);
        assertTrue("hovering craft must still be in flight: " + info.raw(), info.inFlight);
    }

    @Test
    public void yawingTheCraftRotatesTheCruiseVelocity() throws Exception {
        // The setpoint is body-frame: cruise forward, then yaw ~90° — the
        // WORLD velocity must rotate with the nose (from +Z toward -X).
        int id = buildAndAssemble(FixtureSite.openAir(0, 4300, 500));
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        ok(client().execute("artest rocket free-flight-input " + id + " 1 1 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 20"));
        // Release forward, hold yaw for 30 ticks (= 90° at MAX_YAW_RATE 3°/tick).
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 1 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 30"));
        // Let FA re-align the velocity to the rotated setpoint.
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 40"));

        RocketInfo info = rocketInfo(id);
        assertTrue("after a 90° yaw the cruise must point -X (mx=" + info.motionX
                        + " mz=" + info.motionZ + ")",
                info.motionX < -0.5 && Math.abs(info.motionZ) < 0.35);
    }

    @Test
    public void reEnablingFlightAssistCapturesTheCurrentVelocity() throws Exception {
        // Toggling FA back on mid-flight must NOT jerk the craft: the setpoint
        // initialises to the current velocity (Elite behaviour).
        //
        // AMENDED 2026-08-17. This test asserted the capture for a cruise ABOVE the assist's own
        // ceiling, and that promise no longer exists: the acceleration law moved the ceiling ONTO the
        // setpoint (FA_SETPOINT_MAX_SPEED), so re-engaging the assist above it deliberately decelerates
        // the craft to it at the thrust budget rather than rewriting its velocity. The old assertion
        // had been failing since that change landed and nobody read it — the cruise built here is 4.0
        // against a ceiling of 3.0. The capture is still the contract; it is now tested where the
        // contract holds, and the clamp is tested beside it as its own leg.
        int id = buildAndAssemble(FixtureSite.openAir(0, 4350, 500));
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        // Climb well away from the ground first: the Newtonian coast sheds
        // altitude under gravity, and — because re-enabling FA captures the
        // CURRENT velocity (including any downward component) — the craft keeps
        // descending through the 20-tick observation window. Starting from the
        // 1-block engine hover it would touch down (engines off, motion zeroed)
        // before the cruise capture could be observed. The climb budget is sized
        // for the worst case (a full held descent), not just the coast.
        ok(client().execute("artest rocket free-flight-input " + id + " 0 1 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 60"));
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 0 0 0 1")); // cut -> hover
        ok(client().execute("artest rocket free-flight-tick " + id + " 30"));

        // FA off, build a Newtonian cruise with direct thrust, then coast.
        ok(client().execute("artest rocket set-flight-assist " + id + " off"));
        ok(client().execute("artest rocket free-flight-input " + id + " 1 0 0 0 0"));
        // Four ticks of thrust, not eight: 4 × 0.5 = 2.0 b/t, comfortably UNDER the assist ceiling,
        // which is the regime where "capture the current velocity" is the promise.
        ok(client().execute("artest rocket free-flight-tick " + id + " 4"));
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 2"));
        double mzBefore = rocketInfo(id).motionZ;
        assertTrue("precondition: must be coasting (+Z), got " + mzBefore, mzBefore > 0.2);
        assertTrue("precondition: this leg tests the capture, so the cruise must be UNDER the assist "
                        + "ceiling (" + mzBefore + " vs 3.0) — above it the contract is the clamp below",
                mzBefore < 3.0);

        // FA back on -> setpoint captured -> cruise continues, no jerk.
        ok(client().execute("artest rocket set-flight-assist " + id + " on"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 20"));
        double mzAfter = rocketInfo(id).motionZ;
        assertTrue("FA re-enable must keep the cruise (was " + mzBefore + ", now "
                + mzAfter + ")", Math.abs(mzAfter - mzBefore) < 0.25);
    }

    /**
     * The other side of the same toggle, and the behaviour that replaced the old promise: re-engaging
     * the assist on a craft flying FASTER than the assist's ceiling pulls it down to that ceiling —
     * by thrusting against its motion, which is why it is a deceleration and not a rewrite.
     */
    @Test
    public void reEnablingFlightAssistAboveItsCeilingDeceleratesToTheCeiling() throws Exception {
        int id = buildAndAssemble(FixtureSite.openAir(0, 4375, 500));
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        // Climb clear of the ground, then hover, exactly as the capture leg does.
        ok(client().execute("artest rocket free-flight-input " + id + " 0 1 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 60"));
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 0 0 0 1"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 30"));

        // FA off, build a cruise well ABOVE the assist ceiling (8 × 0.5 = 4.0 against 3.0).
        ok(client().execute("artest rocket set-flight-assist " + id + " off"));
        ok(client().execute("artest rocket free-flight-input " + id + " 1 0 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 8"));
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 2"));
        double mzBefore = rocketInfo(id).motionZ;
        assertTrue("precondition: the cruise must exceed the assist ceiling, got " + mzBefore,
                mzBefore > 3.0);

        ok(client().execute("artest rocket set-flight-assist " + id + " on"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 20"));
        double mzAfter = rocketInfo(id).motionZ;
        assertTrue("the assist must bring an overfast craft DOWN toward its ceiling (was " + mzBefore
                + ", now " + mzAfter + ")", mzAfter < mzBefore);
        assertTrue("and must not overshoot below it — it tracks the ceiling, it does not brake to a "
                        + "halt (now " + mzAfter + ")", mzAfter > 2.0);
    }

    @Test
    public void flightAssistOffStillAcceptsExplicitBrake() throws Exception {
        // Cross-side wiring: FA=off + brake input still attenuates motion.
        int id = buildAndAssemble(FixtureSite.openAir(0, 4400, 500));
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));
        ok(client().execute("artest rocket set-flight-assist " + id + " off"));

        ok(client().execute("artest rocket set-state " + id + " motionY=1.0"));
        double myBefore = rocketInfo(id).motionY;

        // brake=1.0 (channel 4).
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 0 0 1 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 1"));

        double myAfter = rocketInfo(id).motionY;
        // Brake at FA off must still pull motion down (toward gravity-altered baseline).
        assertTrue("FA off + brake must still attenuate motionY (was "
                        + myBefore + ", now " + myAfter + ")",
                Math.abs(myAfter) < Math.abs(myBefore));
    }

    @Test
    public void engineSoundPowerTracksThrustNotJustClimb() throws Exception {
        // The client engine sound is driven by getEnginePower(); in FF that must be
        // non-zero for thrust in ANY direction — not only motionY>0 (the classic
        // areEnginesRunning gate that made the sound cut out in cruise/hover).
        int id = buildAndAssemble(FixtureSite.openAir(0, 4250, 500));
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        // Climb clear of the pad, then CUT to a gravity-cancelled hover: FA fires
        // the engines to hold altitude, so motionY settles ~0 while thrust is still
        // being produced — exactly the case the old motionY>0 gate silenced.
        ok(client().execute("artest rocket free-flight-input " + id + " 0 1 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 30"));
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 0 0 0 1")); // cut -> hover
        ok(client().execute("artest rocket free-flight-tick " + id + " 25"));
        RocketInfo hover = rocketInfo(id);
        double myHover  = hover.motionY;
        double powHover = hover.enginePower;
        assertTrue("hover thrust (no climb) must still register engine power for the sound "
                + "(motionY=" + myHover + " enginePower=" + powHover + ")", powHover > 0.0);

        // FA off + no input: pure coast under gravity, no thrust -> engines silent.
        ok(client().execute("artest rocket set-flight-assist " + id + " off"));
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 3"));
        double powCoast = rocketInfo(id).enginePower;
        assertTrue("coasting with no thrust must be silent (enginePower=" + powCoast + ")",
                powCoast < 1e-3);
    }
}
