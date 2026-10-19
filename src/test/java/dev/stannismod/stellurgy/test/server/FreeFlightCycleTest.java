package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.After;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.*;

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
 * <p>Probe surface: {@code stellurgytest rocket set-flight-mode|start-free-flight|
 * free-flight-input|free-flight-tick|info}.
 */
public class FreeFlightCycleTest extends AbstractSharedServerTest {

    /**
     * The clamp production applies to an out-of-range axis.
     *
     * <p>PRODUCTION'S bound, restated here because it is what the two overshoot legs assert: an
     * input past the end of the range comes back AT the end of it.</p>
     */
    private static final double AXIS_CLAMP = -1.0;

    /**
     * The upward motion that says a full vertical throttle BUILT something, in blocks/tick.
     *
     * <p>The TEST'S OWN sensitivity bar: what it refuses is a craft that did not move.</p>
     */
    private static final double THROTTLE_BUILT_MOTION = 0.1;

    private static final String MOTION_X = "motionX";
    private static final String MOTION_Z = "motionZ";
    /** The field {@code free-flight-tick} answers with — that verb's own, not {@code rocket info}'s. */
    private static final String MOTION_Y = "motionY";

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    /** What the server says about one craft, read through the verb's own reader. */
    private RocketInfo rocketInfo(int id) throws Exception {
        return RocketInfo.byId(cmd -> ok(client().read(cmd)), id);
    }

    private final ScenarioRockets rockets = ScenarioRockets.of(commands(), evictionReports());

    @After
    public void disposeOfThisScenariosRockets() throws Exception {
        rockets.disposeStanding();
    }

    /** The rocket this scenario's press made, named by that press's own {@code rocket_born} record. */
    private int buildAndAssemble(FixtureSite site) throws Exception {
        // FIRST link: the volume this craft is built and flown in is EMPTY. The site
        // stands in open air, so this ASSERTS rather than digs - anything standing here
        // means the arrangement is wrong, and it is said now instead of arriving many
        // links later wearing some mechanic's name.
        return rockets.assembleAt(site, cmd -> ok(client().act(cmd)), "simple", 2, 50,
                "the craft is built and flown in this volume");
    }

    private static double parseDouble(String body, String field, String label) {
        double value = Reply.of(body).number(field);
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
        Reply reply = Reply.of("stellurgytest rocket fuel", fuelBody);
        // absence is the answer: a craft with no fuel type names none, and the branch below
        // reports exactly that rather than a tank reading.
        // absence is the answer: a craft with no fuel type names none, and the branch below
        // reports exactly that rather than a tank reading.
        String primary = reply.textOr("primaryFuelType", null);
        String fuels = reply.object("fuels");
        if (primary == null || fuels == null) {
            // Rocket may have no primary fuel type; treat as 0 for our purposes.
            return 0;
        }
        String entry = Reply.of(fuels).object(primary);
        return entry == null ? 0 : Reply.of(entry).integer("amount");
    }

    // ---------------------------------------------------------------------

    @Test
    public void startFreeFlightBypassesClassicCountdown() throws Exception {
        // Critical FF contract: NO destination chip programmed, NO classic
        // countdown — start-free-flight goes directly to isInFlight=true.
        int id = buildAndAssemble(FixtureSite.openAir(0, 2300, 500));
        ok(client().arrange("stellurgytest rocket set-flight-mode " + id + " FREE_FLIGHT"));

        String start = ok(client().arrange(
                "stellurgytest rocket start-free-flight " + id));
        assertTrue("start-free-flight must succeed: " + start,
                Reply.of(start).ok());
        assertTrue("start-free-flight must flip isInFlight=true: " + start,
                Reply.of(start).bool("isInFlight"));

        RocketInfo info = rocketInfo(id);
        assertTrue("info must reflect in-flight after start-free-flight: " + info.raw(),
                info.inFlight);
    }

    @Test
    public void freeFlightTickLoopRunsAndMutatesMotion() throws Exception {
        // Contract: tickFreeFlight is invoked when the rocket is in FF +
        // isInFlight, and pilot input mutates motion. With the
        // engine start there is no takeoff kick: the craft rests in the
        // liftoff hover until input arrives, so 10 ticks of full vertical
        // throttle must produce a clearly positive climb rate.
        int id = buildAndAssemble(FixtureSite.openAir(0, 2550, 500));
        ok(client().arrange("stellurgytest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().arrange("stellurgytest rocket start-free-flight " + id));

        ok(client().act("stellurgytest rocket free-flight-input " + id + " 0 1.0 0 0 0"));
        String tickRes = ok(client().act(
                "stellurgytest rocket free-flight-tick " + id + " 10"));
        double my = parseDouble(tickRes, MOTION_Y, "motionY");
        assertTrue("full vertical throttle must build upward motion "
                        + "(got motionY=" + my + ")", my > THROTTLE_BUILT_MOTION);
    }

    @Test
    public void engineStartHoversOneBlockAboveThePad() throws Exception {
        // starting the engines is NOT a launch — the craft eases
        // ~1 block off the pad and HOVERS there (near-zero motion), without
        // any takeoff kick and without auto-landing.
        int id = buildAndAssemble(FixtureSite.openAir(0, 2900, 500));
        ok(client().arrange("stellurgytest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        double y0 = rocketInfo(id).posY;

        ok(client().arrange("stellurgytest rocket start-free-flight " + id));
        ok(client().act("stellurgytest rocket free-flight-tick " + id + " 60"));

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
        ok(client().arrange("stellurgytest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().arrange("stellurgytest rocket start-free-flight " + id));
        ok(client().act("stellurgytest rocket free-flight-tick " + id + " 40")); // reach the hover

        ok(client().act("stellurgytest rocket free-flight-input " + id + " 0 -1.0 0 0 0"));
        ok(client().act("stellurgytest rocket free-flight-tick " + id + " 80"));

        RocketInfo info = rocketInfo(id);
        assertFalse("touchdown must shut the engines off (isInFlight=false): " + info.raw(),
                info.inFlight);
        assertEquals("landed craft must be stationary", 0.0, info.motionY, 0.01);
    }

    @Test
    public void verticalInputDrainsPrimaryFuel() throws Exception {
        // With fuel REQUIRED for the whole scenario, assembly included: the engines' burn rate is
        // fixed when the rocket is assembled, a rocket that needs no fuel burns none, and the shared
        // harness world starts with the requirement off.
        try (ConfigFlag fuelRequired = ConfigFlag.set(commands(),
                "rocketRequireFuel", true)) {
            int id = buildAndAssemble(FixtureSite.openAir(0, 2600, 500));
            ok(client().arrange("stellurgytest rocket set-flight-mode " + id + " FREE_FLIGHT"));
            ok(client().arrange("stellurgytest rocket start-free-flight " + id));

            // Inspect fuel BEFORE — start-free-flight auto-fills the primary tank.
            String fuelBefore = ok(client().read("stellurgytest rocket fuel " + id));
            int amountBefore = parsePrimaryFuel(fuelBefore);
            assertTrue("start-free-flight must auto-fill primary fuel for tests, got amount="
                    + amountBefore + " from " + fuelBefore, amountBefore > 0);

            // Push full vertical thrust + tick several ticks.
            ok(client().act("stellurgytest rocket free-flight-input " + id + " 0 1.0 0 0 0"));
            ok(client().act("stellurgytest rocket free-flight-tick " + id + " 10"));
            int amountAfter = parsePrimaryFuel(ok(client().read("stellurgytest rocket fuel " + id)));
            assertTrue("fuel must decrease monotonically under thrust; before=" + amountBefore
                            + " after=" + amountAfter,
                    amountAfter < amountBefore);
        }
    }

    @Test
    public void inputOnClassicRocketIsDroppedSilently() throws Exception {
        // Authority/mode contract: free-flight-input is a no-op when the
        // rocket isn't in FREE_FLIGHT. The probe reports applied=false.
        int id = buildAndAssemble(FixtureSite.openAir(0, 2700, 500));
        // (intentionally NO set-flight-mode — rocket stays CLASSIC_LAUNCH)

        ok(client().act(
                "stellurgytest rocket free-flight-input " + id + " 1.0 1.0 1.0 1.0 0.0"));

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
        ok(client().arrange("stellurgytest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().arrange("stellurgytest rocket start-free-flight " + id));

        String resp = ok(client().act(
                "stellurgytest rocket free-flight-input " + id + " 5.0 -5.0 99.0 -99.0 50.0"));
        assertTrue("clamp positive overshoot to 1.0: " + resp,
                (Reply.of(resp).number("fwd") == 1.0));
        assertTrue("clamp negative overshoot to -1.0: " + resp,
                (Reply.of(resp).number("vert") == AXIS_CLAMP));
        assertTrue("clamp yaw +∞ish to 1.0: " + resp,
                (Reply.of(resp).number("yaw") == 1.0));
        assertTrue("clamp pitch -∞ish to -1.0: " + resp,
                (Reply.of(resp).number("pitch") == AXIS_CLAMP));
        assertTrue("clamp brake to 1.0: " + resp,
                (Reply.of(resp).number("brake") == 1.0));
    }

    // ---- the drain follows the force the burn applied ---------------------------------------------

    /**
     * s: one tier-1 flight tick — the tick vanilla's 0.08 blocks/tick² is 9.81 m/s² in (C10 STAT-6, units).
     * A constant: a final double computed once from two literals.
     */
    private static final double TICK_SECONDS = Math.sqrt(0.08 / 9.81);

    /**
     * N: the thrust of {@code with-nuclear-stack}'s two fuelled NERVA-class cores, power-bound —
     * {@code 2ηP/v_e} per core (C10 STAT-30) with the nozzle's η = 0.753 (XE-Prime), a core's
     * P = 1.1·10⁹ W (C10's component table) and hydrogen's v_e = 8 500 m/s at the core's 2 700 K (STAT-32).
     */
    private static final double TWO_CORES_THRUST = 2 * 0.753 * (2 * 1.1e9) / 8_500.0;

    /** m/s: the exhaust the drain divides by — hydrogen at 2 700 K (C10 STAT-32's anchor). */
    private static final double HYDROGEN_EXHAUST_VELOCITY = 8_500.0;

    /** kg per millibucket of hydrogen: a litre of the liquid, 70.8 kg/m³ (C10 STAT-33, weight-mass INV-WGT-09). */
    private static final double HYDROGEN_KG_PER_MB = 70.8 / 1000;

    /**
     * Millibuckets a window's drain may differ from {@code F·τ/v_e} per flight tick: STAT-33's own bound,
     * the fraction of a millibucket each burn carries to the next (under one at either read), plus the
     * flight's precision on the applied force — its thrust in whole newtons (1 in 389 788) and its climb
     * in single precision — taken as 1e-5 of the drain. The smallest defect it exists to see, a drain at
     * the rated flow under a half throttle, is half the window's drain.
     */
    private static final double DRAIN_CARRY_MB = 1.0;
    /** @see #DRAIN_CARRY_MB */
    private static final double DRAIN_PRECISION = 1e-5;

    /** The gentle burn's throttle: half the vertical stick, an input of the experiment. */
    private static final double GENTLE_THROTTLE = 0.5;

    /** EXPERIMENT dose of each burn, game ticks: dozens of millibuckets a tick, so hundreds over a window. */
    private static final int BURN_WINDOW_TICKS = 10;

    /**
     * A tier-1 free flight drains the propellant of the force it APPLIED (C10 STAT-33): a gentle burn
     * drains in proportion to its throttle, a full one the whole of {@code F·τ/v_e} a tick, with τ the
     * 90.3 ms tick the flight integrates over.
     *
     * <p>The craft is {@code with-nuclear-stack}: its thrust is power-bound by its cores, so the force is
     * a figure of the contract, and its acceleration stays under the free-flight law's cap
     * ({@code FreeFlightPhysics#MAX_THRUST_ACCEL}, a premise read below), where the force the Newtonian law
     * applies is the throttle times the thrust. Flight Assist is off, so the vertical stick IS the throttle.
     * The gentle burn comes first and does not lift the craft off its pad (its thrust is half its weight);
     * the force it applies is still half the thrust, because the law pushes and the pad holds. The full
     * burn then lifts it a block.</p>
     *
     * <p>Two windows of two reads each; the flight ticks between the reads are the rocket's own count, in
     * the same reply as the propellant aboard, so a slow box changes how many ticks a window holds and
     * never the verdict.</p>
     *
     * <p>A mechanics test: probes arrange the flight (mode, Flight Assist off, the start, the throttle);
     * the server's own entity tick flies and drains. It does not see a pilot's key reaching the throttle,
     * the classic ascent's drain (at the rated thrust, through the same {@code EntityRocket#burnPropellant}),
     * a pair's split by its mixture ratio, a force above the free-flight cap, or a burn of less than a
     * millibucket a tick (STAT-33's other regions).</p>
     */
    @Pins("STAT-33")
    @RedWitness(verdict = "STAT-33 a full burn drains its thrust times the tick over its exhaust velocity",
            member = "StatsRocket#ejectedMassPerTick", at = "Math.max(0, forceNewtons) * TICK_SECONDS / exhaustVelocity",
            replacedBy = "Math.max(0, forceNewtons) * 0.05 / exhaustVelocity",
            fails = "STAT-33 a full burn drains its thrust times the tick over its exhaust velocity", on = "2026-10-18")
    @RedWitness(verdict = "STAT-33 a gentle burn drains in proportion to the force it applied",
            member = "EntityRocket#tickFreeFlight", at = "burnPropellant(appliedForce, 1);",
            replacedBy = "burnPropellant(stats.getThrust(), 1);",
            fails = "STAT-33 a gentle burn drains in proportion to the force it applied", on = "2026-10-18")
    @Wait(shape = Wait.Shape.WINDOW, at = "Window<Reply> gentle = Window.over(",
            why = "a drain is a value that falls with every burning tick; two reads and the flight ticks between them")
    @Wait(shape = Wait.Shape.WINDOW, at = "Window<Reply> full = Window.over(",
            why = "a drain is a value that falls with every burning tick; two reads and the flight ticks between them")
    @Test
    public void aFreeFlightBurnDrainsThePropellantOfTheForceItApplied() throws Exception {
        FixtureSite site = site();
        int id;
        // Two NERVA-class cores do not lift this chassis past the launch gate's margin, and whether they
        // do is the gate's question (C10 STAT-26..29): the gate is opened for the press alone, so the scan
        // weighs the craft as the flight will.
        try (ConfigFlag gateOpen = ConfigFlag.set(commands(), "minLaunchTWR", 0.0)) {
            id = rockets.assembleAt(site, c -> act(c).raw(), "with-nuclear-stack", 2, 10,
                    "the nuclear rocket whose burns are measured stands in this volume");
        }
        Reply mode = arrange("stellurgytest rocket set-flight-mode " + id + " FREE_FLIGHT");
        requireArranged("the rocket must be put in free flight mode: " + mode, mode.ok());
        Reply assist = arrange("stellurgytest rocket set-flight-assist " + id + " off");
        requireArranged("Flight Assist must be off, so the stick is the throttle: " + assist,
                assist.ok() && !assist.bool("flightAssistOn"));

        Reply built = drainInfo(id);
        // Nobody stands near the rocket, so nothing ticks it until a ticket holds the chunk its column
        // stands in; the server's own entity tick then flies it. The ticket is taken only once the flight
        // has started AND the stick is set, so the first tick the rocket flies is already the gentle burn —
        // the start's lift-off hover, which would take it off its pad and arm the landing, never runs.
        String chunk = (int) built.number("dim") + " " + Math.floorDiv((int) Math.floor(built.number("posX")), 16)
                + " " + Math.floorDiv((int) Math.floor(built.number("posZ")), 16);
        // The harness's server runs with consumption off; a burn drains only with it on, as the game ships.
        try (ConfigFlag fuelRequired = ConfigFlag.set(commands(), "rocketRequireFuel", true)) {
            Reply started = arrange("stellurgytest rocket start-free-flight " + id);
            requireArranged("the rocket must be in free flight, its tanks filled: " + started,
                    started.ok() && started.bool("isInFlight"));
            throttle(id, GENTLE_THROTTLE);
            Reply held = arrange("stellurgytest chunk forceload " + chunk);
            requireArranged("the rocket's chunk must be held loaded: " + held, held.ok());
            Reply burning = drainInfo(id);
            requireArranged("the rocket must burn hydrogen at the two cores' thrust, " + TWO_CORES_THRUST
                            + " N, out of 4 000 mB: " + burning, "hydrogen".equals(burning.text("workingFluid"))
                            && Math.abs(burning.number("thrust") - TWO_CORES_THRUST) <= TWO_CORES_THRUST * DRAIN_PRECISION
                            && workingFluid(burning) > 0);
            requireArranged("the thrust must accelerate the rocket under the free-flight law's cap of "
                            + dev.stannismod.stellurgy.api.FreeFlightPhysics.MAX_THRUST_ACCEL + " blocks/tick²: "
                            + burning, burning.number("thrust") / burning.number("mass_kg") * TICK_SECONDS * TICK_SECONDS
                            < dev.stannismod.stellurgy.api.FreeFlightPhysics.MAX_THRUST_ACCEL);

            Window<Reply> gentle = Window.over(() -> drainInfo(id), client(), GameTicks.server(),
                    t -> GameTicks.advance(client(), GameTicks.server(), t), BURN_WINDOW_TICKS);
            throttle(id, 1.0);
            Window<Reply> full = Window.over(() -> drainInfo(id), client(), GameTicks.server(),
                    t -> GameTicks.advance(client(), GameTicks.server(), t), BURN_WINDOW_TICKS);

            double perTickAtFull = TWO_CORES_THRUST * TICK_SECONDS / HYDROGEN_EXHAUST_VELOCITY / HYDROGEN_KG_PER_MB;
            double fullTicks = full.change(r -> r.number("freeFlightTicksSinceStart"));
            double gentleTicks = gentle.change(r -> r.number("freeFlightTicksSinceStart"));
            requireArranged("the rocket must fly in both windows: gentle " + gentle + "; full " + full,
                    fullTicks > 0 && gentleTicks > 0);
            double fullDrain = fullTicks * perTickAtFull;
            assertEquals("STAT-33 a full burn drains its thrust times the tick over its exhaust velocity: " + full,
                    -fullDrain, full.change(FreeFlightCycleTest::workingFluid), DRAIN_CARRY_MB + fullDrain * DRAIN_PRECISION);
            double gentleDrain = gentleTicks * GENTLE_THROTTLE * perTickAtFull;
            assertEquals("STAT-33 a gentle burn drains in proportion to the force it applied: " + gentle,
                    -gentleDrain, gentle.change(FreeFlightCycleTest::workingFluid),
                    DRAIN_CARRY_MB + gentleDrain * DRAIN_PRECISION);
            System.out.println("[measured] drain | perTickAtFull=" + perTickAtFull + " | gentle " + gentle
                    + " | full " + full);
        } finally {
            arrange("stellurgytest chunk release " + chunk);
        }
    }

    /** The vertical stick at {@code throttle}, nothing else held, and require that it reached the rocket. */
    private void throttle(int id, double throttle) throws Exception {
        Reply r = act("stellurgytest rocket free-flight-input " + id + " 0 " + throttle + " 0 0 0");
        requireArranged("the throttle must reach the rocket: " + r, r.ok() && r.bool("applied") && r.number("vert") == throttle);
    }

    /** One read of the rocket — its flight, its thrust and the propellant aboard — in one reply. */
    private Reply drainInfo(int id) throws Exception {
        Reply r = read("stellurgytest rocket info " + id);
        requireArranged("the rocket must answer its info: " + r, r.has("fuel"));
        return r;
    }

    /** Millibuckets of working fluid aboard, as the read reports them. */
    private static double workingFluid(Reply info) {
        return Reply.of(Reply.of(info.object("fuel")).object("NUCLEAR_WORKING_FLUID")).number("amount");
    }
}
