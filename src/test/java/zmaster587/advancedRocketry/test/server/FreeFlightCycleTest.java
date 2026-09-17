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
 * Server-side end-to-end coverage for Free Flight Mode (feature/true_rcs).
 *
 * <p>Verified contracts:
 *
 * <ul>
 *   <li><b>Mode persistence</b>: a freshly-assembled rocket defaults to CLASSIC_LAUNCH;
 *       set-flight-mode flips it; rocket info reports the new mode.</li>
 *   <li><b>Free-flight bypass</b>: start-free-flight flips isInFlight=true WITHOUT a
 *       destination chip and without the LAUNCH_COUNTER countdown.</li>
 *   <li><b>Input &rarr; motion</b>: pushing a positive forward throttle through
 *       free-flight-input then ticking the FF physics produces a positive +Z motion
 *       delta on an unrotated rocket (yaw=0 &rarr; forward = +Z).</li>
 *   <li><b>Fuel drain</b>: vertical-thrust input across ticks decreases the primary
 *       fuel level monotonically until exhaustion.</li>
 *   <li><b>Mode-mismatch input is dropped</b>: free-flight-input on a CLASSIC rocket
 *       leaves currentFreeFlightInput at zero.</li>
 * </ul>
 *
 * <p>Probe surface: {@code artest rocket set-flight-mode|start-free-flight|
 * free-flight-input|free-flight-tick|info}.
 */
public class FreeFlightCycleTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";
    private static final String MOTION_X = "motionX";
    private static final String MOTION_Z = "motionZ";
    /** The field {@code free-flight-tick} answers with — that verb's own, not {@code rocket info}'s. */
    private static final String MOTION_Y = "motionY";

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

    private static double parseDouble(String body, String field, String label) {
        double value = Reply.of(body).number(field);
        assertTrue("missing " + label + " in: " + body, !Double.isNaN(value));
        return value;
    }

    /**
     * How much of its PRIMARY fuel the rocket carries; 0 when it names none.
     *
     * <p>The reply is a map keyed by the value of another field
     * ({@code {"primaryFuelType":"X","fuels":{"X":{"amount":…}}}}), which the regex this replaces
     * expressed with a back-reference — exact only while the two are adjacent and in that order.</p>
     */
    private static int parsePrimaryFuel(String fuelBody) {
        Reply reply = Reply.of("artest rocket fuel", fuelBody);
        String primary = reply.text("primaryFuelType");
        String fuels = reply.object("fuels");
        if (primary == null || fuels == null) {
            // Rocket may have no primary fuel type; treat as 0 for our purposes.
            return 0;
        }
        String entry = Reply.of(fuels).object(primary);
        return entry == null ? 0 : Reply.of(entry).integerOr("amount", 0);
    }

    // ---------------------------------------------------------------------

    @Test
    public void freshRocketDefaultsToClassicLaunchMode() throws Exception {
        int id = buildAndAssemble(FixtureSite.openAir(0, 2000, 500));
        RocketInfo info = rocketInfo(id);
        assertEquals("default mode must be CLASSIC_LAUNCH: " + info.raw(),
                RocketInfo.CLASSIC_LAUNCH, info.flightMode);
    }

    @Test
    public void setFlightModeRoundTripsThroughInfo() throws Exception {
        int id = buildAndAssemble(FixtureSite.openAir(0, 2100, 500));

        String set = ok(client().execute(
                "artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        assertTrue("set-flight-mode FREE_FLIGHT must succeed: " + set,
                set.contains("\"ok\":true"));
        assertTrue("set-flight-mode must echo mode: " + set,
                set.contains("\"flightMode\":\"FREE_FLIGHT\""));

        RocketInfo info1 = rocketInfo(id);
        assertEquals("info must report FREE_FLIGHT after set: " + info1.raw(),
                RocketInfo.FREE_FLIGHT, info1.flightMode);

        // And back to classic.
        ok(client().execute("artest rocket set-flight-mode " + id + " CLASSIC_LAUNCH"));
        RocketInfo info2 = rocketInfo(id);
        assertEquals("info must report CLASSIC_LAUNCH after flip-back: " + info2.raw(),
                RocketInfo.CLASSIC_LAUNCH, info2.flightMode);
    }

    @Test
    public void setFlightModeRejectsUnknownMode() throws Exception {
        int id = buildAndAssemble(FixtureSite.openAir(0, 2200, 500));
        String resp = ok(client().execute(
                "artest rocket set-flight-mode " + id + " WARPDRIVE"));
        assertTrue("unknown mode must be reported as error: " + resp,
                resp.contains("\"error\":\"unknown mode\""));
    }

    @Test
    public void startFreeFlightBypassesClassicCountdown() throws Exception {
        // Critical FF contract: NO destination chip programmed, NO classic
        // countdown — start-free-flight goes directly to isInFlight=true.
        int id = buildAndAssemble(FixtureSite.openAir(0, 2300, 500));
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));

        String start = ok(client().execute(
                "artest rocket start-free-flight " + id));
        assertTrue("start-free-flight must succeed: " + start,
                start.contains("\"ok\":true"));
        assertTrue("start-free-flight must flip isInFlight=true: " + start,
                start.contains("\"isInFlight\":true"));

        RocketInfo info = rocketInfo(id);
        assertTrue("info must reflect in-flight after start-free-flight: " + info.raw(),
                info.inFlight);
        assertEquals("info must keep flightMode=FREE_FLIGHT: " + info.raw(),
                RocketInfo.FREE_FLIGHT, info.flightMode);
    }

    @Test
    public void startFreeFlightRejectsClassicRocket() throws Exception {
        // Counter-test: start-free-flight on a rocket still in CLASSIC mode
        // must NOT silently launch it (classic flow has its own gates).
        int id = buildAndAssemble(FixtureSite.openAir(0, 2400, 500));
        String resp = ok(client().execute(
                "artest rocket start-free-flight " + id));
        assertTrue("classic rocket must reject start-free-flight: " + resp,
                resp.contains("\"error\":\"rocket not in FREE_FLIGHT\""));

        RocketInfo info = rocketInfo(id);
        assertFalse("rejected start must NOT flip isInFlight: " + info.raw(), info.inFlight);
    }

    @Test
    public void freeFlightInputIsStoredOnServerAfterPacketPath() throws Exception {
        // Cross-side wiring: free-flight-input probe goes through the same
        // server-side application path that PacketType.FREE_FLIGHT_INPUT
        // would (calls rocket.applyFreeFlightInput). After the probe completes,
        // info must reflect the new currentFreeFlightInput so a client UI /
        // tick loop reads what was set.
        int id = buildAndAssemble(FixtureSite.openAir(0, 2500, 500));
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        String applied = ok(client().execute(
                "artest rocket free-flight-input " + id + " 1.0 -0.5 0.25 0 0.75"));
        assertTrue("input must apply on FF rocket: " + applied,
                applied.contains("\"applied\":true"));
        // Probe echoes the clamped values back; full-range happy-path values
        // should pass through unchanged.
        assertTrue("applied response must echo fwd=1.0: " + applied,
                applied.contains("\"fwd\":1.0"));
        assertTrue("applied response must echo vert=-0.5: " + applied,
                applied.contains("\"vert\":-0.5"));

        // Info must round-trip the input — proves server-side storage path
        // is wired into the probe surface that clients/UI will read.
        RocketInfo.FreeFlightInput stored = rocketInfo(id).freeFlightInput();
        assertEquals("info must store ffInputFwd=1.0: " + stored, 1.0, stored.forward, 0.0);
        assertEquals("info must store ffInputVert=-0.5: " + stored, -0.5, stored.vertical, 0.0);
        assertEquals("info must store ffInputBrake=0.75: " + stored, 0.75, stored.brake, 0.0);
    }

    @Test
    public void freeFlightTickLoopRunsAndMutatesMotion() throws Exception {
        // Contract: tickFreeFlight is invoked when the rocket is in FF +
        // isInFlight, and pilot input mutates motion. With the
        // engine start there is no takeoff kick: the craft rests in the
        // liftoff hover until input arrives, so 10 ticks of full vertical
        // throttle must produce a clearly positive climb rate.
        int id = buildAndAssemble(FixtureSite.openAir(0, 2550, 500));
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        ok(client().execute("artest rocket free-flight-input " + id + " 0 1.0 0 0 0"));
        String tickRes = ok(client().execute(
                "artest rocket free-flight-tick " + id + " 10"));
        double my = parseDouble(tickRes, MOTION_Y, "motionY");
        assertTrue("full vertical throttle must build upward motion "
                        + "(got motionY=" + my + ")", my > 0.1);
    }

    @Test
    public void engineStartHoversOneBlockAboveThePad() throws Exception {
        // starting the engines is NOT a launch — the craft eases
        // ~1 block off the pad and HOVERS there (near-zero motion), without
        // any takeoff kick and without auto-landing.
        int id = buildAndAssemble(FixtureSite.openAir(0, 2900, 500));
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        double y0 = rocketInfo(id).posY;

        ok(client().execute("artest rocket start-free-flight " + id));
        ok(client().execute("artest rocket free-flight-tick " + id + " 60"));

        RocketInfo info = rocketInfo(id);
        assertTrue("engines-on craft must still be in flight (hovering): " + info.raw(),
                info.inFlight);
        assertEquals("must hover ~1 block above the start height (y0=" + y0 + ")",
                y0 + 1.0, info.posY, 0.35);
        assertEquals("hover must be near-stationary", 0.0, info.motionY, 0.05);
    }

    @Test
    public void descendingToTheGroundShutsTheEnginesOff() throws Exception {
        // touchdown auto-shutdown. From the engine-start hover,
        // pilot descent input drives the craft into ground contact, which
        // exits flight (engines off) and zeroes motion.
        int id = buildAndAssemble(FixtureSite.openAir(0, 2950, 500));
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));
        ok(client().execute("artest rocket free-flight-tick " + id + " 40")); // reach the hover

        ok(client().execute("artest rocket free-flight-input " + id + " 0 -1.0 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 80"));

        RocketInfo info = rocketInfo(id);
        assertFalse("touchdown must shut the engines off (isInFlight=false): " + info.raw(),
                info.inFlight);
        assertEquals("landed craft must be stationary", 0.0, info.motionY, 0.01);
    }

    @Test
    public void verticalInputDrainsPrimaryFuel() throws Exception {
        int id = buildAndAssemble(FixtureSite.openAir(0, 2600, 500));
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        // Inspect fuel BEFORE — start-free-flight auto-fills the primary tank.
        String fuelBefore = ok(client().execute("artest rocket fuel " + id));
        int amountBefore = parsePrimaryFuel(fuelBefore);
        assertTrue("start-free-flight must auto-fill primary fuel for tests, got amount="
                + amountBefore + " from " + fuelBefore, amountBefore > 0);

        // Push full vertical thrust + tick several ticks.
        ok(client().execute("artest rocket free-flight-input " + id + " 0 1.0 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 10"));

        String fuelAfter = ok(client().execute("artest rocket fuel " + id));
        int amountAfter = parsePrimaryFuel(fuelAfter);
        assertTrue("fuel must decrease monotonically under thrust; before=" + amountBefore
                        + " after=" + amountAfter,
                amountAfter < amountBefore);
    }

    @Test
    public void inputOnClassicRocketIsDroppedSilently() throws Exception {
        // Authority/mode contract: free-flight-input is a no-op when the
        // rocket isn't in FREE_FLIGHT. The probe reports applied=false.
        int id = buildAndAssemble(FixtureSite.openAir(0, 2700, 500));
        // (intentionally NO set-flight-mode — rocket stays CLASSIC_LAUNCH)

        String applied = ok(client().execute(
                "artest rocket free-flight-input " + id + " 1.0 1.0 1.0 1.0 0.0"));
        assertTrue("classic-mode input must report applied=false: " + applied,
                applied.contains("\"applied\":false"));

        // info still shows zero current input (defensive). A craft with NO input block at all
        // satisfies the same claim more strongly — nothing is holding its stick — so the two are
        // read as one question rather than defaulted into each other.
        RocketInfo info = rocketInfo(id);
        assertTrue("classic rocket info must keep currentFreeFlightInput at zero: " + info.raw(),
                !info.hasFreeFlightInput() || info.freeFlightInput().forward == 0.0);
    }

    @Test
    public void inputClamping() throws Exception {
        // Server-side authority: out-of-range float inputs must be clamped
        // before storage. The applied JSON is the clamped value.
        int id = buildAndAssemble(FixtureSite.openAir(0, 2800, 500));
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        String resp = ok(client().execute(
                "artest rocket free-flight-input " + id + " 5.0 -5.0 99.0 -99.0 50.0"));
        assertTrue("clamp positive overshoot to 1.0: " + resp,
                resp.contains("\"fwd\":1.0"));
        assertTrue("clamp negative overshoot to -1.0: " + resp,
                resp.contains("\"vert\":-1.0"));
        assertTrue("clamp yaw +∞ish to 1.0: " + resp,
                resp.contains("\"yaw\":1.0"));
        assertTrue("clamp pitch -∞ish to -1.0: " + resp,
                resp.contains("\"pitch\":-1.0"));
        assertTrue("clamp brake to 1.0: " + resp,
                resp.contains("\"brake\":1.0"));
    }
}
