package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonObject;
import com.github.stannismod.forge.testing.TestTimeouts;

import org.junit.After;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;


import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.PilotSeat;
import zmaster587.advancedRocketry.test.TransitSetup;
import zmaster587.advancedRocketry.test.ShipIdentity;
import zmaster587.advancedRocketry.test.ShipInfo;

import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.HYPERSPACE_JUMP_SPEED;
import static org.junit.Assert.assertTrue;

/**
 * Does a ship fly as SMOOTHLY after a jump as it did before one?
 *
 * <p>The report this test exists for is a pilot's: after the first jump the craft "flies in jerks",
 * in a cell and back over a planet alike. Nothing in the session log could see it — the log records
 * what the game DECIDED, and a jerk is not a decision, it is a distribution: the same held key, the
 * same commanded velocity, and a clock somewhere underneath that stopped arriving evenly.</p>
 *
 * <p><b>Why the answer needs four clocks, not one.</b> A ship's motion passes through the physics
 * loop's own thread (where the velocity integrates), the server world tick (where the command is
 * republished), the client tick (where the arriving pose is smoothed) and the rendered frame (where
 * the pilot finally sees it). Each of those can stutter alone, they are fixed by different things,
 * and a measurement on only one of them cannot tell which. So both legs below read all four, and
 * the failure message carries every one — a red here should say WHICH clock broke, not merely that
 * something did.</p>
 *
 * <p><b>The control is the same ship, minutes earlier.</b> A smoothness number has no absolute
 * meaning: it depends on the box, the fork count, the ship's mass. So the pre-jump leg is not
 * arrangement — it is the control arm, flown by the same pilot holding the same key on the same
 * craft in a cell it reached the same way. What the post-jump leg is measured against is that, and
 * a green means "the jump did not make it worse", which is exactly the reported claim's negation.
 * Both legs also carry their own stimulus control: a craft that never moved reads as perfectly
 * smooth, so each leg asserts it actually flew before it is allowed to say anything about how.</p>
 *
 * <p></p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSFlightSmoothnessAcrossJumpE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-flight-smoothness";
    }


    private static final String MOTION_TRACE = "zmaster587.advancedRocketry.command.test.MotionTrace";

    /** A channel's net displacement over the window, as an {@code [x,y,z]} array. */
    private static final String NET_MOVE = "netMove";

    /**
     * How long one measured leg holds the key. Long enough that the Flight-Assist setpoint has
     * finished ramping well before the measured window opens, so the window sees a craft at a
     * settled cruise rather than one still accelerating — a ramp is a legitimately uneven
     * displacement and would read as roughness that is not the subject.
     *
     * <p>A GAME-TICK count, deliberately not scaled by the fork factor: scaling it would change how
     * far the ship flies rather than how long the test waits, making the experiment itself a
     * function of machine load.</p>
     */
    private static final int FLY_TICKS = 140;

    /** The trailing window every channel is summarised over. Matches a window the client offers. */
    private static final int WINDOW_MS = 3000;

    /**
     * How long the settled leg waits after the immediate one, in client ticks. Several times the
     * length of a measured window, so an arrival transient that merely SLID into leg B's window
     * cannot still be inside leg C's.
     */
    private static final int SETTLE_TICKS = 300;

    /** A leg that moved less than this over its window did not fly, and measures nothing. */
    private static final double MIN_LEG_TRAVEL = 5.0;
    /**
     * How far the craft must have got before the CONTROL leg's window opens, and how long it is
     * given to manage it.
     *
     * <p>Leg A is the only one measured from a craft standing on its build site; legs B and C are
     * measured on a craft already floating in a cell. A rocket that has not yet cleared the pad
     * pushes at the authority ceiling and goes nowhere — the physics channel shows acceleration
     * pinned and a net move under a tenth of a block — so the control arm was measuring the
     * release, not flight, on roughly one run in three. Lifting clear first removes that, and it
     * also makes the control arm what the subject arms already are: steady-state flight.</p>
     */
    private static final double LIFT_CLEAR_BLOCKS = 5.0;
    private static final int LIFT_POLL_TICKS = 40;
    private static final int LIFT_ATTEMPTS = 8;

    /**
     * How much rougher the post-jump leg is allowed to be than the control before this test calls
     * it a regression. Generous on purpose: the quantity is a ratio of hitch time between two
     * windows on a loaded developer box, and the claim being tested is "jerks", not "1.4x rougher".
     */
    private static final double MAX_ROUGHNESS_RATIO = 4.0;
    /** Below this many milliseconds of lost time a leg is smooth outright and no ratio is taken. */
    private static final double HITCH_NOISE_FLOOR_MS = 40.0;

    /**
     * The physics channel's declared rate, READ FROM ITS OWN DECLARATION rather than written down.
     *
     * <p>The vendored VS physics loop sleeps {@code 1e9 / VSConfig.targetTps} nanoseconds per step
     * ({@code VSWorldPhysicsLoop:64}), so {@code targetTps} IS the rate — and it is a config field,
     * which is the whole reason a test may not carry a copy of it. Before this, the instrument
     * control asserted {@code physHz > 40 && < 85} under a message quoting "its declared 60 Hz":
     * three numbers, none of which the test owned, and a config change would have left the bound
     * asserting a rate nothing runs at.</p>
     */
    private static final double PHYSICS_HZ = org.valkyrienskies.mod.common.config.VSConfig.targetTps;

    /**
     * The server tick rate, from vanilla's own period: {@code MinecraftServer} runs a tick every
     * 50 ms, so the rate is the reciprocal. Written as the arithmetic rather than as "20" so the
     * relation to the period is visible where the number is.
     */
    private static final double SERVER_TICK_HZ = 1000.0 / 50.0;

    /**
     * How far a SAMPLED rate may sit from its declared one before the instrument is called broken.
     *
     * <p>This is the test's own number and it stays one: how much a clock drifts under
     * concurrent-fork load on a developer box is a property of the harness, not of the game. It
     * replaces four hand-picked edges (40/85 around 60, 13/28 around 20) with one factor applied to
     * whatever each channel declares — so the two channels cannot drift apart in strictness, and a
     * declaration change moves both windows with it.</p>
     *
     * <p>The value is the widest of the four it replaces: 40 against a declared 60 is 0.67, and
     * 13 against 20 is 0.65, so a floor of 0.6 and a ceiling of 1/0.6 ≈ 1.67 is at least as
     * permissive as every bound that passed before. NOT tightened here — a tighter one needs the
     * spread across repeated runs, which is not measured.</p>
     */
    private static final double RATE_TOLERANCE_FRACTION = 0.6;

    /**
     * How much more the ground covered between beats may vary after the jump than before it.
     *
     * <p>PROVISIONAL. The first calibration run read 1.06 before and 1.63 after on the frame
     * channel, from a single run — and one sample of a distribution is not a measurement of it, so
     * this bound is set generously enough to be a guard against a real regression rather than a
     * coin toss on run-to-run noise. It tightens when the spread across repeated runs is known.</p>
     */
    private static final double MAX_SURGE_RATIO = 2.0;
    /** A spread at or below this is even enough that no ratio against the control is meaningful. */
    private static final double EVENNESS_FLOOR = 1.5;

    /**
     * The frame cap this test runs its measurement at. The harness default is 30, which is a sampler
     * too coarse to resolve a hitch a player sees at his own frame rate — the reported symptom is
     * about what the pilot SEES, so the frame clock has to run at something like what he runs at.
     */
    private static final int MEASURED_FPS = 120;

    /** The client's frame cap before this test raised it, so cleanup can put it back. */
    private int previousFrameRate = -1;

    @Test
    public void aShipFliesAsSmoothlyAfterAJumpAsBeforeOne() throws Exception {

        // The rendered frame is one of the four clocks this test reads, and the harness seeds
        // maxFps:30. A pilot sees his stutter at 120; a 30 Hz sampler averages that away, so the
        // frame channel would return a clean number off an instrument that cannot resolve the thing
        // it is looking for. Raised for this test only and read BACK off the client's own field,
        // never assumed — the same discipline the sky-pass gate gets in the sibling jump e2e.
        com.google.gson.JsonObject fps = bot().setFrameRate(MEASURED_FPS);
        previousFrameRate = fps.get("previous").getAsInt();
        scenario().requireArranged("the frame cap must actually be raised, read back off the client's "
                        + "own field - every frame-channel number below is measured through it: " + fps,
                fps.get("effectiveLimit").getAsInt() >= MEASURED_FPS);
        scenario().requireArranged("vsync must be off, or the driver caps the frame clock below the "
                + "limit that was just set and the frame channel is throttled by something this "
                + "test cannot see: " + fps, !fps.get("vsync").getAsBoolean());

        // Headless: nothing but the client holds a ship loaded, and the probe calls below run
        // between client ticks. This affordance touches ship LOADING, never the timing of the
        // physics loop or of the render thread, which is all this test reads.

        // ---- ARRANGE: the transit stack over an empty origin cell, and a real flyable ship built
        // in it by the real assembler. The piloted setup fixture's bare deck has no propulsion, so
        // a held key could move nothing and every number below would describe a parked craft. ----
        int originDim = TransitSetup.empty(this::exec).originDim;

        // The cell is a void world, so this is not about escaping terrain — it is about ONE
        // definition of where a fixture stands instead of a 64 nobody chose. The first link still
        // earns its place: it MEASURES that the cell is empty rather than taking the setup probe's
        // word for it, and this class's four clocks are all sensitive to what the hull meets.
        //
        // NOT ALLOCATED FROM A PLOT, deliberately. A plot keeps a scenario clear of its siblings in
        // a SHARED world; this craft is alone in a cell made for it, so there is nobody to be kept
        // clear of, and moving it into a region of that dimension whose extent nobody has measured
        // would be a real risk taken for no contract.
        final zmaster587.advancedRocketry.test.FixtureSite site =
                zmaster587.advancedRocketry.test.FixtureSite.openAir(originDim, 40, 40);
        int bx = site.x, by = site.y, bz = site.z;
        site.requireClear(this::exec, 2, 16,
                "the craft whose flight smoothness is measured across the jump");
        // The BIGGEST flyable tier-2 variant the catalogue has, not the bare one. Mass and block
        // count are on the causal path for every one of the four clocks — the physics step's cost,
        // the volume of ship state synced per tick, the chunk work a moving hull does — and the
        // report is about a real ship, not a builder's minimum. Using the largest existing variant
        // rather than hand-placing a new one keeps the fixture inside the rules the catalogue
        // already enforces (tower-bounded scan, anchor connectivity, flyability).
        String fixture = exec("artest fixture rocket " + originDim + " " + bx + " " + by + " " + bz
                + " with-pilot-seat");
        scenario().requireArranged("fixture (with-pilot-seat) failed: " + fixture,
                fixture.contains("\"ok\":true"));
        int[] bp = Reply.of("artest fixture rocket", fixture).blockPos("builderPos");
        scenario().requireArranged("fixture missing builderPos: " + fixture, bp != null);
        String assembled = exec("artest rocket assemble " + originDim + " " + bp[0] + " "
                + bp[1] + " " + bp[2]);
        scenario().requireArranged("a with-pilot-seat build must route to a ship: " + assembled,
                assembled.contains("\"rocketCount\":0"));
        scenario().requireArranged("the origin ship never assembled/loaded in dim " + originDim,
                waitForLoadedShip(originDim) >= 1);

        // The ship's IDENTITY, from the assembler that minted its durable name. This used to be a
        // bounded lookup at the build site, defended as "the one moment a positional lookup is
        // defensible" — but a bound is a statement about DISTANCE and ships do not collide, so it
        // says nothing about how many hulls are in the box. The name is not a distance.
        String durableShipId = ShipIdentity.nameFromAssembly(assembled);
        String shipId = awaitPhysicsId(originDim, durableShipId);
        // Name the craft to the transit stack, by its durable id: a jump begun for a ship the stack
        // cannot name captures nobody (measured 2026-09-05 — the pilot arrived in nothing).
        String named = exec("artest space transit-name " + originDim + " " + shipId);
        scenario().requireArranged("the transit stack must resolve this ship's flight computer and its"
                + " durable id, or the jump departs nameless: " + named,
                named.contains("\"afcFound\":true") && !named.contains("\"durableId\":\"\""));
        PilotSeat seat = PilotSeat.byId(this::exec, originDim, shipId)
                .requireFound("the pilot seat must be found in the assembled ship");
        int seatX = seat.seatX, seatY = seat.seatY, seatZ = seat.seatZ;
        int[] afcOrigin = {seat.afcX, seat.afcY, seat.afcZ};
        int sx = (int) Math.round(seat.shipWorldX);
        int sy = (int) Math.round(seat.shipWorldY);
        int sz = (int) Math.round(seat.shipWorldZ);

        String enter = exec("artest space enter " + botName() + " " + originDim
                + " " + sx + " " + sy + " " + sz);
        scenario().requireArranged("space enter into the origin cell must succeed: " + enter,
                readBool(enter, "ok"));
        bot().waitTicks(20);
        scenario().requireArranged("the client must have followed into the origin cell",
                bot().reportWeather().get("dim").getAsInt() == originDim);
        mountTheSeat(originDim, seatX, seatY, seatZ);

        // ---- LEG A (the control): fly in the origin cell, before anything has jumped. -----------
        liftClear(originDim, afcOrigin);
        Leg before = measure("before-jump", originDim, afcOrigin);

        // ---- ACT: the jump. Probe-driven so the park cannot race the measurement either side. ---
        bot().waitTicks(40); // let the station-hold settle, so the departure anchor is a still pose
        // BY IDENTITY: the control leg above LIFTS the ship clear of the ground, so its berth is
        // exactly the place it is no longer at. A bounded read there answers managed:false and an
        // unbounded one answers about whatever else is loaded; neither is this ship.
        ShipInfo shipNow = ShipInfo.byId(this::exec, originDim, shipId);
        // The mark before the departure, so every link of the jump is in the log in order — and the
        // CLIENT's own beside it, for the remount his client performs when the arrival tells it who
        // is riding what.
        Events events = transitEvents(this::exec);
        long mark = events.markInstrumented();
        long clientMark = clientEvents().mark();
        String begin = exec("artest space transit-begin " + originDim
                + " " + (int) Math.round(shipNow.x)
                + " " + (int) Math.round(shipNow.y)
                + " " + (int) Math.round(shipNow.z)
                + " " + HYPERSPACE_JUMP_SPEED);
        scenario().requireArranged("the transit must begin (departure crossing): " + begin,
                readBool(begin, "began"));
        scenario().requireArranged("the jump must depart under the craft's own name, never the synthetic"
                + " id a nameless fixture gets: " + begin, !begin.contains("\"shipId\":\"t\""));

        // The jump is this scenario's ARRANGEMENT — the subject is how the ship flies afterwards —
        // so the chain is required, not asserted: a jump that settles with its pilot left behind is
        // a post-jump leg with no pilot, and the chain names the link that left him.
        requireChain(events, mark, "the pilot must arrive SEATED in the target cell, or the post-jump"
                + " leg has no pilot and measures a drifting hulk", PILOTED_JUMP_CHAIN);
        int targetDim = arrivedTargetDim(this::exec);
        JsonObject arrivedRiding =
                ridingOnceTheClientHasRemounted(clientMark, CLIENT_REMOUNT_BUDGET_TICKS);
        scenario().requireArranged("the arrived pilot's client must be in the target cell: riding="
                        + arrivedRiding + " clientDim=" + bot().reportWeather().get("dim").getAsInt()
                        + " targetDim=" + targetDim,
                bot().reportWeather().get("dim").getAsInt() == targetDim);

        // The crossing re-pastes the ship, so its flight computer is at a NEW subspace block: read
        // the arrived one rather than reusing the departure's, which would key the recorder to a
        // ring nothing writes and report a silent, perfectly smooth nothing.
        // BY NAME on the far side too. The crossing re-assembles the hull, so the physics id changes
        // — but the durable name crosses with it, and `(0,200,0)` in the target cell was never an
        // address of this craft at all: it named whatever the yard lookup reached from there.
        String arrivedShipId = awaitPhysicsId(targetDim, durableShipId);
        PilotSeat arrivedSeat = null;
        int[] afcArrived = null;
        for (int i = 0; i < 20 && afcArrived == null; i++) {
            arrivedSeat = PilotSeat.byId(this::exec, targetDim, arrivedShipId);
            if (arrivedSeat.found && arrivedSeat.hasAfc) {
                afcArrived = new int[]{arrivedSeat.afcX, arrivedSeat.afcY, arrivedSeat.afcZ};
            } else {
                bot().waitTicks(10);
            }
        }
        scenario().requireArranged("the arrived ship must expose its flight computer, or the post-jump "
                + "recorder has nothing to key on: " + arrivedSeat, afcArrived != null);

        // ---- LEG B (the subject): the same pilot, the same key, the same craft, after a jump. ---
        Leg after = measure("after-jump", targetDim, afcArrived);

        // ---- LEG C: the same again, once the arrival has had time to finish whatever it is still
        // doing. Leg B alone cannot tell an arrival TRANSIENT from a lasting change: both put a
        // stalled tick inside the measured window, and the reported symptom ("it flies in jerks
        // afterwards") is the lasting one. A stall that survives here is the ship's new normal; a
        // stall that vanishes is the arrival finishing up, which is a different and much smaller
        // defect. Measured 6 of 6 in leg B before this leg existed: one 199-266 ms tick, with ZERO
        // chunks arriving in the window — so whatever it is, it is not the loading the report
        // suspected.
        bot().waitTicks(SETTLE_TICKS);
        Leg settled = measure("after-jump-settled", targetDim, afcArrived);

        // The raw client channels beside the summary. A ratio says the pose stream is uneven; only
        // the beat GAPS beside the per-beat STEPS can say whether the tick arrived late (a stalled
        // clock) or arrived on time carrying an uneven pose (a stuttering pose stream), and only the
        // step series can say whether that unevenness is periodic.
        System.out.println("[SMOOTH-RAW] before:  " + before.clientJson);
        System.out.println("[SMOOTH-RAW] settled: " + settled.clientJson);
        System.out.println("[SMOOTH] control (before jump):    " + before);
        System.out.println("[SMOOTH] subject (after jump):     " + after);
        System.out.println("[SMOOTH] subject (settled):        " + settled);

        // The instrument spoke on every clock, in both legs. A mute channel is not a smooth one,
        // and without this the comparison below can be satisfied by two silences.
        assertInstrumentSpoke("control", before);
        assertInstrumentSpoke("subject", after);
        assertInstrumentSpoke("settled", settled);

        // And the stimulus fired: a craft that did not move is trivially smooth on every channel.
        assertTrue("CONTROL LEG: the ship must actually FLY before the jump — a leg that did not "
                        + "move reads as perfectly smooth on all four clocks and measures nothing. "
                        + before,
                before.travel >= MIN_LEG_TRAVEL);
        assertTrue("SUBJECT LEG: the ship must actually FLY after the jump. A red here is a control "
                        + "failure, NOT a smoothness finding: the pilot's key stopped reaching the "
                        + "arrived ship's computer, which is a delivery defect with its own tests. "
                        + after + " delivery=" + exec("artest vs seat-delivery"),
                after.travel >= MIN_LEG_TRAVEL);
        // And on the leg every claim below is actually judged on. Without this the two surge
        // assertions are unfalsifiable: evenness() degenerates to 0.0 for a craft that did not
        // move, and 0.0 is under every threshold, so a settled leg that never flew would read as
        // the smoothest run this test has ever seen.
        assertTrue("CONTROL LEG: the ship must actually FLY on the SETTLED leg, which is the one "
                        + "every claim below is judged on. A craft that stopped moving reads as "
                        + "perfectly smooth on all four clocks, so a red here is a control failure "
                        + "and not a smoothness finding. " + settled,
                settled.travel >= MIN_LEG_TRAVEL);

        // The claim under test, on each clock in turn, so a red names the one that broke.
        //
        // Two shapes, because a jerk can arrive as either and they look nothing alike in the data:
        // a LATE BEAT (the clock itself stalled) and a SURGE (the clock is metronomic but the craft
        // covered wildly different ground between beats). The first calibration run had exactly one
        // of each — a 260 ms server tick and a per-frame displacement spread that went from 1.06 to
        // 1.63 — with the frame RATE untouched at 30 fps on both sides.
        // Judged on the SETTLED leg. The reported symptom is how the ship flies AFTERWARDS, not the
        // one tick an arrival spends tidying up, and a test that failed on the transient would be
        // pinning a different, smaller thing under this one's name. Leg B is printed either way and
        // its numbers are in every failure message.
        assertNotRougher("the physics loop's own clock (where the ship's velocity integrates)",
                before.physHitchMs, settled.physHitchMs, before, settled, after);
        assertNotRougher("the server tick that republishes the flight command",
                before.gameHitchMs, settled.gameHitchMs, before, settled, after);
        assertNotRougher("the client tick that smooths the arriving ship pose",
                before.clientTickHitchMs, settled.clientTickHitchMs, before, settled, after);
        assertNotRougher("the rendered frame — what the pilot actually looks at",
                before.frameHitchMs, settled.frameHitchMs, before, settled, after);

        assertNoSurge("the pilot's own view", before.frameEvenness, settled.frameEvenness,
                before, settled, after);
        assertNoSurge("the ship pose the client renders from",
                before.clientTickEvenness, settled.clientTickEvenness, before, settled, after);
    }

    @After
    public void cleanup() {
        try {
            if (previousFrameRate > 0) {
                bot().setFrameRate(previousFrameRate);
            }
            exec("artest player dismount");
            exec("artest vs motion-trace reset");
        } catch (Exception ignored) {
        }
    }

    // --- the measurement ------------------------------------------------------------------------

    /** One measured leg: the four clocks' summaries plus the numbers the assertions read. */
    private static final class Leg {
        String label = "";
        String serverJson = "";
        String clientJson = "";
        double travel;
        double physHitchMs;
        double gameHitchMs;
        double clientTickHitchMs;
        double frameHitchMs;
        int physSamples;
        int gameSamples;
        int clientTickSamples;
        int frameSamples;
        /**
         * How UNEVEN the pilot's own view moved: the 95th percentile of per-frame eye displacement
         * over its median. One means every frame carried the craft the same distance; the further
         * above one, the more the picture surges and pauses at a constant frame rate — which is
         * what "flies in jerks" describes when the frame TIMING is clean.
         */
        double frameEvenness;
        /** The same ratio on the client tick, for the case where the surge is in the pose stream. */
        double clientTickEvenness;
        /** Each server clock's measured rate — the guard against two ships sharing one ring. */
        double physHz;
        double gameHz;
        /** Distinct CONTROLLER objects that drove this flight computer in the window. Must be 1. */
        int physWriters;
        /** Distinct SHIPS those controllers belonged to — which of the two duplicate shapes it is. */
        int physWriterShips;
        /** Chunks that arrived DURING the window, differenced across it rather than between legs. */
        long chunksInWindow;
        long serverChunkLoads;
        long clientChunkLoads;

        @Override
        public String toString() {
            return label + " travel=" + round(travel)
                    + " physWriters=" + physWriters + " onShips=" + physWriterShips
                    + " chunksInWindow=" + chunksInWindow
                    + " hz{phys=" + round(physHz) + " game=" + round(gameHz) + "}"
                    + " frameEvenness=" + round(frameEvenness)
                    + " clientTickEvenness=" + round(clientTickEvenness)
                    + " hitchMs{phys=" + round(physHitchMs) + " game=" + round(gameHitchMs)
                    + " clientTick=" + round(clientTickHitchMs) + " frame=" + round(frameHitchMs)
                    + "} n{phys=" + physSamples + " game=" + gameSamples
                    + " clientTick=" + clientTickSamples + " frame=" + frameSamples + "}"
                    + " chunkLoads{server=" + serverChunkLoads + " client=" + clientChunkLoads + "}"
                    + " server=" + serverJson + " client=" + clientJson;
        }

        private static double round(double v) {
            return Math.round(v * 100.0) / 100.0;
        }
    }

    /**
     * Hold the vertical key for a fixed number of client ticks, then read every clock's account of
     * the trailing window. The server rings are cleared first; the client rings cannot be (they
     * live in the other JVM and the harness can only READ a static), which is why the client half
     * is summarised over trailing windows and read immediately after the key is released.
     */
    /**
     * Hold the climb key until the craft is genuinely under way, and say so plainly if it never is.
     *
     * <p>A red here is an ARRANGEMENT failure, not a smoothness finding: the craft never left its
     * build site, so there is nothing to measure the jump against.</p>
     */
    /**
     * The PHYSICS id of the craft named {@code durableShipId}, once the physics mod has finished
     * assembling it. Retried because that assembly is asynchronous — not because the answer is
     * uncertain: the ship is named, so the only question is whether it exists yet.
     */
    private String awaitPhysicsId(int dim, String durableShipId) throws Exception {
        return ShipIdentity.awaitPhysicsIdOf(this::exec, dim, durableShipId, 40,
                () -> bot().waitTicks(5));
    }

    private void liftClear(int dim, int[] afc) throws Exception {
        double moved = 0.0;
        bot().holdKey(Keyboard.KEY_R);
        try {
            for (int attempt = 0; attempt < LIFT_ATTEMPTS && moved < LIFT_CLEAR_BLOCKS; attempt++) {
                exec("artest vs motion-trace reset");
                bot().waitTicks(LIFT_POLL_TICKS);
                moved = netMoveLength(section(exec("artest vs motion-trace " + dim + " " + afc[0]
                        + " " + afc[1] + " " + afc[2] + " " + WINDOW_MS), "\"phys\":"));
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        scenario().requireArranged("the craft never got under way on its own pad. It was given "
                        + (LIFT_ATTEMPTS * LIFT_POLL_TICKS) + " ticks of held climb key and moved "
                        + Leg.round(moved) + " blocks, against " + LIFT_CLEAR_BLOCKS + " asked for. "
                        + "A craft pushing at the authority ceiling while its net move stays near "
                        + "zero is being held by its surroundings, so nothing below would be a "
                        + "reading about the jump.",
                moved >= LIFT_CLEAR_BLOCKS);
    }

    private Leg measure(String label, int dim, int[] afc) throws Exception {
        exec("artest vs motion-trace reset");
        bot().holdKey(Keyboard.KEY_R);
        try {
            bot().waitTicks(FLY_TICKS);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        Leg leg = new Leg();
        leg.label = label;
        leg.serverJson = exec("artest vs motion-trace " + dim + " " + afc[0] + " " + afc[1]
                + " " + afc[2] + " " + WINDOW_MS);
        leg.clientJson = bot().readStaticField(MOTION_TRACE, "CLIENT_SUMMARY")
                .get("value").getAsString();

        String phys = section(leg.serverJson, "\"phys\":");
        String game = section(leg.serverJson, "\"game\":");
        String window = section(leg.clientJson, "\"w" + WINDOW_MS + "\":");
        String clientTick = section(window, "\"tick\":");
        String frame = section(window, "\"frame\":");

        leg.physSamples = readIntOr(phys, "n", 0);
        leg.gameSamples = readIntOr(game, "n", 0);
        leg.clientTickSamples = readIntOr(clientTick, "n", 0);
        leg.frameSamples = readIntOr(frame, "n", 0);
        leg.physHitchMs = readDoubleOr(phys, "hitchMs", 0);
        leg.gameHitchMs = readDoubleOr(game, "hitchMs", 0);
        leg.clientTickHitchMs = readDoubleOr(clientTick, "hitchMs", 0);
        leg.frameHitchMs = readDoubleOr(frame, "hitchMs", 0);
        leg.frameEvenness = evenness(frame);
        leg.clientTickEvenness = evenness(clientTick);
        leg.physHz = readDoubleOr(phys, "hz", 0);
        leg.gameHz = readDoubleOr(game, "hz", 0);
        leg.physWriters = readIntOr(phys, "writers", 0);
        leg.physWriterShips = readIntOr(phys, "writerShips", 0);
        // Column 3 of the server-tick channel is the cumulative server chunk count; differencing it
        // across the window is what turns "chunks arrived between the legs" into "chunks arrived
        // while that tick was stalled".
        leg.chunksInWindow = (long) (column(game, "\"last\":", 3) - column(game, "\"first\":", 3));
        leg.serverChunkLoads = readIntOr(leg.serverJson, "serverChunkLoads", 0);
        leg.clientChunkLoads = readIntOr(leg.clientJson, "chunkLoads", 0);
        // How far the SHIP itself went over the window, from the physics channel's own net move —
        // the one displacement in the set that no interpolation filter has touched.
        leg.travel = netMoveLength(phys);
        return leg;
    }

    /**
     * The 95th percentile of a channel's per-sample displacement over its median: 1.0 is a craft
     * covering the same ground every beat, and the excess over 1.0 is the surge-and-pause the pilot
     * sees. Read from {@code stepBlocks}, whose two numbers this deliberately keeps as a RATIO —
     * the absolute distances differ with cruise speed and with the channel's own rate, and the
     * question is evenness, not speed.
     */
    private static double evenness(String channelJson) {
        String step = section(channelJson, "\"stepBlocks\":");
        double p50 = readDoubleOr(step, "p50", 0);
        double p95 = readDoubleOr(step, "p95", 0);
        if (p50 <= 1.0e-6) {
            // A parked craft has no evenness to speak of. This value is BELOW every threshold, so
            // it can only ever read as a pass — which is why each leg this test judges carries its
            // own travel control, including the settled one.
            return 0.0;
        }
        return p95 / p50;
    }

    private static void assertInstrumentSpoke(String which, Leg leg) {
        assertTrue("INSTRUMENT CONTROL (" + which + "): the physics-thread channel must have "
                + "recorded samples. A mute channel cannot be distinguished from a perfectly "
                + "smooth one, so every reading below it would be a silence read as a pass. " + leg,
                leg.physSamples >= 10);
        assertTrue("INSTRUMENT CONTROL (" + which + "): the server-tick channel must have recorded "
                + "samples. " + leg, leg.gameSamples >= 10);
        assertTrue("INSTRUMENT CONTROL (" + which + "): the CLIENT tick channel must have recorded "
                + "samples — this is the harness reading a static in the other JVM, so a zero here "
                + "usually means the read found the wrong class rather than a stalled client. " + leg,
                leg.clientTickSamples >= 10);
        assertTrue("INSTRUMENT CONTROL (" + which + "): the rendered-FRAME channel must have "
                + "recorded samples. This is the only clock that sees what the pilot looks at; "
                + "without it the test cannot answer the half of the report that is about the "
                + "picture rather than the motion. " + leg, leg.frameSamples >= 10);
        // TWO physics bodies driving one flight computer. Not an instrument fault — the recorder
        // keys its rings by dimension AND block, so a second writer here is a second SHIP claiming
        // the same computer, which is a state no build should be able to reach. It is checked among
        // the instrument controls because until it is excluded, every displacement below is a
        // difference between two different craft and means nothing.
        assertTrue("ONE flight computer was driven by " + leg.physWriters + " controllers on "
                        + leg.physWriterShips + " ships (" + which + " leg), so the physics channel "
                        + "sampled at " + Leg.round(leg.physHz) + " Hz against a 60 Hz clock. "
                        + (leg.physWriterShips > 1
                            ? "TWO SHIPS claim this computer: the arrival left a duplicate craft "
                              + "behind, and two rigid bodies sharing a hull collide every step."
                            : "One ship, several controller objects: a stale tile instance is still "
                              + "in the ship's controller set after its replacement was installed, "
                              + "so the ship is commanded twice per step by two copies of itself.")
                        + " Either way the pilot feels it as jerks, and the travel figure here shows "
                        + "a craft that barely moved. " + leg,
                leg.physWriters == 1);
        // BOTH RATES COME FROM THEIR OWN DECLARATION NOW. These read `physHz > 40 && < 85` under a
        // message saying "its declared 60 Hz", and `gameHz > 13 && < 28` for "20 Hz" — four numbers
        // the test invented around two it only asserted in prose. The physics rate is
        // `VSConfig.targetTps` (the vendored VS physics loop sleeps `1e9 / targetTps` nanos,
        // `VSWorldPhysicsLoop:64`), and the server rate is vanilla's 50 ms tick. Neither was ever
        // the test's to know: a config change or a VS edit would leave the bound asserting a rate
        // nothing runs at, and the window is wide enough that it would say nothing on the way.
        //
        // The TOLERANCE stays the test's own, and that is the honest split: how far a sampled rate
        // may sit from its declared one on a loaded box is a property of THIS harness, not of the
        // game. It is one factor for both channels instead of four hand-picked edges.
        assertRateNearItsDeclaration("physics", which, leg.physHz, PHYSICS_HZ, leg);
        assertRateNearItsDeclaration("server-tick", which, leg.gameHz, SERVER_TICK_HZ, leg);
    }

    /**
     * An INSTRUMENT CONTROL on one channel's sampled rate, against the rate that channel declares.
     *
     * <p>Its job is to refuse the smoothness numbers below when the clock they were sampled on was
     * not running: a channel at a fraction of its rate produces gaps that read as hitches. So the
     * failure says which channel, what it declared, and what it read.</p>
     */
    private static void assertRateNearItsDeclaration(String channel, String which, double sampled,
                                                     double declared, Leg leg) {
        double floor = declared * RATE_TOLERANCE_FRACTION;
        double ceiling = declared / RATE_TOLERANCE_FRACTION;
        assertTrue("INSTRUMENT CONTROL (" + which + "): the " + channel + " channel must run near"
                        + " its DECLARED " + Leg.round(declared) + " Hz — it read "
                        + Leg.round(sampled) + " Hz, outside [" + Leg.round(floor) + ", "
                        + Leg.round(ceiling) + "]. Every smoothness figure below was sampled on this"
                        + " clock, so a channel that was not running produces gaps that read as"
                        + " hitches. " + leg,
                sampled > floor && sampled < ceiling);
    }

    private static void assertNotRougher(String clock, double control, double subject,
                                         Leg before, Leg after, Leg transient_) {
        if (subject <= HITCH_NOISE_FLOOR_MS) {
            return; // smooth outright; a ratio against near-zero says nothing
        }
        double allowed = Math.max(HITCH_NOISE_FLOOR_MS, control * MAX_ROUGHNESS_RATIO);
        assertTrue("a jump must not make the ship rougher to fly. On " + clock + " the pilot lost "
                        + Leg.round(subject) + " ms to late beats after the jump against "
                        + Leg.round(control) + " ms before it, over the same " + WINDOW_MS
                        + " ms window with the same key held on the same craft — more than the "
                        + MAX_ROUGHNESS_RATIO + "x this test allows. The control leg is the one to "
                        + "read first: if it is itself rough, this box is loaded and the comparison "
                        + "is weak; if it is clean, the jump did this.\n  control: " + before
                        + "\n  subject: " + after,
                subject <= allowed);
    }

    private static void assertNoSurge(String what, double control, double subject,
                                      Leg before, Leg after, Leg transient_) {
        double allowed = Math.max(EVENNESS_FLOOR, control * MAX_SURGE_RATIO);
        assertTrue("a jump must not make the ship SURGE. On " + what + ", the ground covered between "
                        + "beats went from a spread of " + Leg.round(control) + " to "
                        + Leg.round(subject) + " (95th percentile over median; 1.0 is a craft "
                        + "covering the same distance every beat). The clock's own rate is reported "
                        + "beside it — if the rate is steady and this is not, the beats are arriving "
                        + "on time carrying uneven amounts of movement, which is the pose stream "
                        + "stuttering rather than the renderer. Chunk arrivals during each leg are "
                        + "on the legs below; a surge that tracks them is the loading, not the jump."
                        + "\n  control: " + before + "\n  subject: " + after,
                subject <= allowed);
    }

    /** One column of a channel's {@code "first"} or {@code "last"} sample row. */
    private static double column(String channelJson, String rowKey, int index) {
        int at = channelJson.indexOf(rowKey);
        if (at < 0) {
            return 0.0;
        }
        int open = channelJson.indexOf('[', at);
        int close = channelJson.indexOf(']', open);
        if (open < 0 || close < 0) {
            return 0.0;
        }
        String[] parts = channelJson.substring(open + 1, close).split(",");
        if (index >= parts.length) {
            return 0.0;
        }
        try {
            return Double.parseDouble(parts[index].trim());
        } catch (NumberFormatException notANumber) {
            return 0.0;
        }
    }

    /** The length of a channel's {@code netMove} vector, or 0 when it reported none. */
    private static double netMoveLength(String json) {
        Reply channel = Reply.of("a smoothness channel", json);
        if (!channel.has(NET_MOVE)) {
            return 0.0;
        }
        double x = channel.arrayNumber(NET_MOVE, 0);
        double y = channel.arrayNumber(NET_MOVE, 1);
        double z = channel.arrayNumber(NET_MOVE, 2);
        return Math.sqrt(x * x + y * y + z * z);
    }

    /**
     * The balanced JSON object that follows {@code key} in {@code json}. Written by hand rather
     * than parsed because the probe envelope is a flat string and a regex for a nested object stops
     * at the first closing brace, which here is always the wrong one.
     */
    private static String section(String json, String key) {
        int at = json.indexOf(key);
        if (at < 0) {
            return "";
        }
        int start = json.indexOf('{', at + key.length() - 1);
        if (start < 0) {
            return "";
        }
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, i + 1);
                }
            }
        }
        return "";
    }

    // --- arrangement helpers (mirroring the tier-2 client e2e classes) ---------------------------

    private void mountTheSeat(int dim, int seatX, int seatY, int seatZ) throws Exception {
        // The CLIENT's mark BEFORE the mount is attempted: his own startRiding is what the link
        // below waits for, and a mark taken after it could not tell "he was seated before I looked"
        // from "he was never seated".
        long clientMark = clientEvents().mark();
        String mount = "";
        boolean mounted = false;
        for (int attempt = 0; attempt < 5 && !mounted; attempt++) {
            String mountAt = exec("artest vs seat-mount-at " + dim
                    + " " + seatX + " " + seatY + " " + seatZ);
            scenario().requireArranged("seat-mount-at must spawn the seat dummy: " + mountAt,
                    readBool(mountAt, "ok"));
            mount = exec("artest player mount-entity " + readInt(mountAt, "dummyId"));
            mounted = mount.contains("\"mounted\":true");
            if (!mounted) {
                bot().waitTicks(10);
            }
        }
        scenario().requireArranged("the bot must mount the pilot-seat dummy: " + mount, mounted);
        // The base raises a TYPED arrangement failure carrying the server's own mount/dismount
        // record, so a red here says whether the client was merely behind or the product dropped
        // him. This class used to keep its own copy of that logic with a comment explaining why it
        // could not use the shared one; the reason was that it sat on the wrong base, and it does
        // not any more. The settling `waitTicks(10)` that stood ahead of it went with the poll: the
        // client's own mount record is the thing being waited for, and it cannot be arrived at too
        // early off a mark taken before the attempt.
        ridingOnceTheClientHasRemounted(clientMark, CLIENT_REMOUNT_BUDGET_TICKS);
    }

    private int waitForLoadedShip(int dim) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (readIntOr(exec("artest vs ship-count-all " + dim), "count", -1) >= 1) {
                exec("artest vs load-ships " + dim);
                int loaded = readIntOr(exec("artest vs ship-count " + dim), "count", -1);
                if (loaded >= 1) {
                    return loaded;
                }
            }
            bot().waitTicks(5);
        }
        return 0;
    }

    private static boolean readBool(String json, String key) {
        return Reply.of(json).bool(key, false);
    }

    private static int readInt(String json, String key) {
        assertTrue("expected int \"" + key + "\" in: " + json, Reply.of(json).has(key));
        return Reply.of(json).integer(key);
    }

    private static int readIntOr(String json, String key, int def) {
        return Reply.of(json).integerOr(key, def);
    }

    private static double readDouble(String json, String key) {
        assertTrue("expected number \"" + key + "\" in: " + json, Reply.of(json).has(key));
        return Reply.of(json).number(key);
    }

    private static double readDoubleOr(String json, String key, double def) {
        return Reply.of(json).numberOr(key, def);
    }
}
