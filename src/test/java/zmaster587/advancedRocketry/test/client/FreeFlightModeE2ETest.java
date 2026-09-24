package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.Reply;

import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;

import zmaster587.advancedRocketry.test.Plot;
import zmaster587.advancedRocketry.test.RocketInfo;
import zmaster587.advancedRocketry.test.RocketList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Real-client end-to-end coverage for Free Flight Mode.
 *
 * <p>This boots both a dedicated server (via {@link AbstractClientE2ETest})
 * and a real MC client bot that connects in. The bot acts as a passenger;
 * server-side probes flip flight mode, push FF input, and the real server
 * tick loop runs {@code tickFreeFlight} on the live entity. The bot polls
 * the rocket entity through {@code /artest rocket info} to assert the
 * cross-side replication is coherent.
 *
 * Verified cross-side contracts:
 *  - Bot can mount a freshly-assembled rocket via {@code player mount-entity}.
 *  - Server-side flight-mode flip is observable via {@code rocket info}
 *    (which reads the field that NBT-roundtrips and is replicated through
 *    the datawatcher branch on subsequent state changes).
 *  - {@code start-free-flight} flips {@code isInFlight=true} without a chip.
 *  - Once airborne with non-zero throttle, server tick loop produces a
 *    cumulative motion delta over a 40-tick window.
 *  - The bot's reportState confirms the player is still riding the rocket
 *    AFTER ticks: the FF tick must NOT eject the passenger.
 *
 * <p>The client-side keypress&rarr;packet&rarr;server input wiring is unit-tested
 * via {@code FreeFlightInputTest} (ByteBuf round-trip with re-clamping) and
 * pinned indirectly here: the same {@code FREE_FLIGHT_INPUT} packet that
 * keybinds emit on real key events is what {@code free-flight-input} probe
 * dispatches server-side. A regression in the wire format would surface in
 * one of those two layers.
 *
 * Gated by {@code -Dforge.test.client=true}; skipped on headless CI.
 *
 * <h2>Shared harness</h2>
 *
 * <p>This class is the single largest item in the client tier: 27 scenarios, each of which used to
 * boot its own dedicated-server JVM AND its own Minecraft client — about 27 x 110 s in ONE gradle
 * fork, which made it the wall-clock FLOOR of the whole tier while the other seven forks idled.
 * It now runs on one shared harness.</p>
 *
 * <p>Two things made the migration safe rather than merely cheap:</p>
 * <ul>
 *   <li><b>The lane is not moved.</b> These scenarios build on GROUND at y=64, so they inherit
 *       whatever the fixed seed generated. The x=3000.. / z=500 strip is what every green run of
 *       this file was taken on; relocating it onto the shared-harness default would have been a
 *       change of subject dressed as a refactor. See {@link #lane()}.</li>
 *   <li><b>The rocket is found by PLOT, not by "the last one in the world".</b> The old
 *       {@code buildAndAssemble} read {@code artest rocket list 0} and took the highest id it saw.
 *       That is safe when the world holds exactly one rocket and silently wrong when it holds 27 —
 *       precisely the object-answers-for-another-object failure a shared world creates. The lookup
 *       now filters on {@link Plot#contains}.</li>
 * </ul>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class FreeFlightModeE2ETest extends AbstractSharedClientE2ETest {

    private static final String ROCKET_ID = "id";

    /** How long the CLIENT is given to perform a seating or a release the server has already done,
     *  in ticks — a ceiling on one round trip. */
    private static final int SEAT_LINK_BUDGET_TICKS = 200;

    /** How long the CLIENT is given to show something it has been told or pressed — a changed HUD
     *  line, an opened screen, its own chat line echoed back — in ticks: a ceiling on one round
     *  trip. */
    private static final int CLIENT_SHOWS_BUDGET_TICKS = 200;

    /** How long a held key's input is given to be APPLIED to the rocket on the server, in ticks — a
     *  ceiling on one client tick and one packet. */
    private static final int KEY_APPLIED_BUDGET_TICKS = 100;

    /**
     * How far the craft must rise for a vertical-thrust leg to have measured a CLIMB, in blocks.
     *
     * <p>The TEST'S OWN sensitivity bar: two blocks is many times the settle jitter of a craft
     * sitting on its pad, and far under what a live climb covers in the same window. What it
     * refuses is a craft that did not move.</p>
     */
    private static final double CLIMBED_BLOCKS = 2.0;

    /**
     * How far the heading must turn for a yaw input to have ARRIVED, in degrees.
     *
     * <p>The TEST'S OWN: ten degrees over eight ticks is far above the drift of a craft holding its
     * heading and far below what the commanded rate produces.</p>
     */
    private static final double YAW_TURNED_DEG = 10.0;

    /**
     * How far the CLIENT's rendering of the craft may sit from the SERVER's position, in blocks.
     *
     * <p>The TEST'S OWN replication tolerance — nothing in the mod decides how far apart the two
     * sides may be, and the contract is "the same craft". Six blocks is a craft's own length, so a
     * client drawing it somewhere else entirely still fails.</p>
     */
    private static final double CLIENT_TRACKS_SERVER_BLOCKS = 6.0;

    /**
     * How far the craft must strafe for the inventory key to have been RE-BOUND to strafing, in
     * blocks, signed because the direction is the claim.
     *
     * <p>The TEST'S OWN: the key is held for a fixed window and the craft is at yaw 0, so a
     * negative X of more than a block is unambiguous — and a key that opened an inventory instead
     * moves it nowhere.</p>
     */
    private static final double STRAFED_BLOCKS = -1.0;

    /**
     * The upward motion that says vertical thrust is being PRODUCED, in blocks/tick.
     *
     * <p>The TEST'S OWN precondition bar, and small on purpose: it gates a leg whose subject is
     * what happens when that thrust is cut, so all it has to establish is that there was some.</p>
     */
    private static final double PRODUCING_CLIMB = 0.01;

    /**
     * What counts as BRAKED for a craft whose vertical input has been cut, in blocks/tick.
     *
     * <p>The TEST'S OWN: the contract is a hover, so the honest statement is zero and this is the
     * residual of the brake settling. A craft still climbing is an order of magnitude above it.</p>
     */
    private static final double BRAKED_TO_HOVER = 0.05;

    /**
     * What counts as NEAR-STATIONARY for a craft already hovering, in blocks/tick — a shade wider
     * than {@link #BRAKED_TO_HOVER} because the hover is sampled over a window rather than at the
     * instant the brake finished.
     */
    private static final double HOVER_STATIONARY = 0.06;

    /**
     * The vertical-rate SETPOINT that says the ramp ran while the key was held, in blocks/s.
     *
     * <p>The TEST'S OWN sensitivity bar on production's own ramped value: what it refuses is a
     * setpoint that never left zero.</p>
     */
    private static final double VRT_SETPOINT_RAMPED = 0.4;

    /**
     * The actual vertical velocity that says the craft is CHASING that setpoint, in blocks/s.
     *
     * <p>The TEST'S OWN, and deliberately well under the setpoint bar above: the claim is that the
     * craft follows, not that it has arrived.</p>
     */
    private static final double VELOCITY_CHASES_SETPOINT = 0.1;

    /**
     * How far the camera may sit from the craft's own attitude on any rendered frame, in degrees.
     *
     * <p>The TEST'S OWN. The two are the same attitude read on different frames, one interpolated,
     * so this is frame lag rather than a budget for drift — and the defect it refuses is a camera
     * DETACHED from the craft, which diverges without bound.</p>
     */
    private static final double CAMERA_TRACKS_NOSE_DEG = 20.0;

    /**
     * How close the camera yaw must come to the server's craft heading once the turn stops, in
     * degrees.
     *
     * <p>The TEST'S OWN: two degrees is the residual of an interpolated camera settling onto a
     * heading the server has already stopped changing.</p>
     */
    private static final double CAMERA_CONVERGED_DEG = 2.0;

    /**
     * How far the nose must pitch for a mouse drag to have travelled the whole swipe&rarr;rate&rarr;
     * server path, in degrees.
     *
     * <p>The TEST'S OWN sensitivity bar: twenty degrees cannot be reached by frame noise, and a
     * drag that never reached the server produces none of it.</p>
     */
    private static final double NOSE_PITCHED_DEG = 20.0;

    /**
     * How far the craft must BANK for a horizontal mouse move to have been read as roll, in
     * degrees.
     */
    private static final double BANKED_DEG = 15.0;

    /**
     * How far the heading may drift while the craft banks, in degrees.
     *
     * <p>The TEST'S OWN, and the pair with {@link #BANKED_DEG} is the whole claim: roll must not
     * COUPLE into yaw. It is deliberately under the bank bar, so a run where the two moved together
     * fails.</p>
     */
    private static final double YAW_DRIFT_WHILE_BANKING_DEG = 12.0;

    /**
     * How far a commanded roll must integrate server-side to have been APPLIED, in degrees.
     *
     * <p>The TEST'S OWN sensitivity bar, and lower than {@link #BANKED_DEG} because it is read over
     * a shorter window: what it refuses is a command the server ignored.</p>
     */
    private static final double SERVER_ROLL_INTEGRATED_DEG = 3.0;
    /**
     * How much of the fuel the rocket calls PRIMARY it is carrying, or {@code -1} when the reply
     * names no primary fuel or holds no entry for it.
     *
     * <p>The reply is {@code {"primaryFuelType":"X","fuels":{"X":{"amount":…}}}} — a map keyed by
     * the value of another field. The regex this replaces expressed that with a back-reference
     * ({@code "\\1"}), which is exact only while the two are written in that order and adjacent;
     * read structurally, the lookup is what it always was: one field naming a key in another.</p>
     */
    private static int primaryFuelAmount(String fuelReply) {
        Reply reply = Reply.of("artest rocket fuel", fuelReply);
        // absence is the answer: a craft with no fuel type names none, and the branch below
        // reports exactly that rather than a tank reading.
        // absence is the answer: a craft with no fuel type names none, and the branch below
        // reports exactly that rather than a tank reading.
        String primary = reply.textOr("primaryFuelType", null);
        String fuels = reply.object("fuels");
        if (primary == null || fuels == null) {
            return -1;
        }
        String entry = Reply.of(fuels).object(primary);
        return entry == null ? -1 : Reply.of(entry).integer("amount");
    }

    /**
     * The base Y for every fixture here: the OPEN-AIR band, not terrain.
     *
     * <p>It was 64, documented as "the pad is built on terrain, not in air" — which was a statement
     * about where the pad happened to be, never something this class needs. Nothing here touches the
     * ground: a rocket is assembled on the launchpad the fixture lays at this Y and then flown. What
     * terrain cost is written at the pre-clear below, which had to reach FIFTY blocks up because a
     * hill or a tree overhanging the pad pins the assembled rocket and every thrust assertion then
     * reads exactly 0.0 — on a class whose world is generated with a RANDOM seed each run, so the
     * hill is there on some runs and not others.</p>
     */
    private static final int BASE_Y = FixtureSite.OPEN_AIR_Y;

    /**
     * The observation point behind this class's engine-state links: the test-only mixin on
     * {@code EntityRocket.setInFlight}, the one mutator every launch and every touchdown goes
     * through. (The touchdown ANNOUNCEMENT is a second, independent recorder — the Forge-bus
     * subscriber for AR's own {@code RocketLandedEvent} — and reports under its own name.)
     *
     * <p>Read by the one assertion here that concludes something from a SILENCE. The list it is
     * checked against is JVM-wide rather than window-scoped, so what it rules out is a seam that
     * never wove or never ran at all — which is the silence that would otherwise be read as
     * "the engines stayed off".</p>
     */
    private static final String ROCKET_INSTRUMENT = "rocket_events";

    @Override
    protected String subsystem() {
        return "free-flight";
    }

    /**
     * The strip this file's scenarios have always flown on: x from 3000, z=500, 100 apart. 27
     * scenarios reach x=5600; the pre-migration file already used up to 5100 on this line.
     *
     * <p>It was documented as un-relocatable because it was ground level. It is not any more —
     * {@link #BASE_Y} is the open-air band — so what keeps the lane here is only that these are the
     * numbers 27 scenarios' green runs were taken on, which is reason enough not to move them and
     * not a claim about terrain.</p>
     */
    @Override
    protected Plot.Lane lane() {
        return new Plot.Lane(3000, 500, 100);
    }

    // THE BASE COMES FROM THE ALLOCATED SITE, not from the plot's corner. The difference is not
    // cosmetic: a fixture at the corner has no room on the low side, so the volume it clears leaves
    // the plot and `requireClear`'s containment check can never pass. `site()` insets it, which is
    // what makes the check mean something here.
    private int baseX() {
        return site().x;
    }

    private int baseZ() {
        return site().z;
    }

    /** Stand the bot above and beside its own plot's build site, clear of the pad. */
    private void tpNearBuildSite() throws Exception {
        long mark = clientEvents().mark();
        exec("tp @a " + (baseX() + 10) + " " + (BASE_Y + 15) + " " + (baseZ() + 10) + " 0 0");
        // The far side of a teleport is the CLIENT applying it, and that is what the assembly below
        // needs: the bot has to be clear of the pad on the side that renders and collides.
        awaitClientPlacedNear(mark, baseX() + 10, baseZ() + 10,
                "the bot must be standing clear of the pad before anything is built on it");
    }

    /** Stand the bot on its own plot's pad, within {@code mount-entity} range of the rocket. */
    private void tpOntoPad() throws Exception {
        long mark = clientEvents().mark();
        exec("tp @a " + (baseX() + 0.5) + " " + (BASE_Y + 1) + " " + (baseZ() + 0.5) + " 0 0");
        // `mount-entity` is a RANGE check against where the player is, so the placement has to have
        // landed before the mount is issued — five ticks were a bet on that round trip.
        awaitClientPlacedNear(mark, baseX() + 0.5, baseZ() + 0.5,
                "the bot must be on the pad, within mounting range of the rocket");
    }

    private int buildAndAssemble() throws Exception {
        // FIRST link: the whole FLIGHT COLUMN is empty, not just the build site — fifty blocks of
        // it, because that is how far this rocket climbs inside the window. The reach is inherited
        // from the pre-clear this replaces, which needed it for a different reason: the world here
        // is generated with a RANDOM seed each run, and a hill or tree overhanging the pad above the
        // old +10 ceiling pinned the assembled rocket in place (Entity.move zeroes motionY on a
        // vertical collision) so every thrust assertion downstream read exactly 0.0. It was caught
        // by collidedVertically=true after a run-to-run flaky "rocket never moves".
        //
        // The site is in the open-air band now, so the column starts empty and this ASSERTS that,
        // on the air fill's own `placed`, rather than digging and hoping.
        final FixtureSite site = site();
        int[] bp = RocketFixture.placeAt(site, this::exec, "simple", 2, 50,
                "the rocket is assembled here and climbs fifty blocks up this column");

        // ONE assemble, and the retry that stood here is gone. It re-laid the fixture and assembled
        // again up to three times on the claim that "pad-bounds detection occasionally races
        // chunk/structure state on the shared world" — a claim written with the feature on
        // 2026-07-03 and never measured, which no ledger entry records, and which the arrangement
        // rules out: the fixture is laid and assembled by two probe commands, and probe commands
        // run one after another on the server thread, so the second cannot overtake the first.
        //
        // A retry of an operation that "sometimes" fails is a wait on something that does not
        // always happen, and it converts the failure into a pause. If this goes red, the reply names
        // the refusal — and a refusal on a freshly laid pad is a finding, not weather.
        String assemble = RocketFixture.assembleBuilt(site, this::exec, bp);
        assertTrue("the assembler must build the rocket laid on its pad one command earlier: "
                + assemble, Reply.of(assemble).ok());

        return rocketIdInThisPlot();
    }

    /**
     * The id of the rocket standing in THIS scenario's plot.
     *
     * <p>{@code artest rocket list 0} is a GLOBAL query: on a shared harness the world holds every
     * earlier scenario's rocket too. The pre-migration code took the highest id it saw, which is
     * correct only while exactly one rocket exists. Each entry carries its {@code pos}, so the
     * answer is narrowed to the plot that built it — and an ambiguous answer is an ARRANGEMENT
     * failure naming what it saw, never a silently-picked candidate.</p>
     */
    /**
     * Every rocket the PREVIOUS scenario left flying goes, before this one builds its own.
     *
     * <p>A {@code @Before} rather than an {@code @After} for the reason the base gives for its whole
     * reset: JUnit runs {@code @After} before the rules finish, so cleanup that must be visible to
     * the next scenario belongs at the next scenario's start. It runs AFTER the base's
     * {@code prepareScenario} because JUnit orders a superclass's {@code @Before} first.</p>
     *
     * <p><b>The plot does not cover this, and that is the finding.</b> The allocator hands each
     * scenario its own patch of world and never recycles one, so nothing else ever looks there — for
     * what stays put. Measured 2026-09-16, three loaded runs out of three: a rocket 93 ticks old, 120
     * blocks up and 80 downrange of where it was built, standing inside the NEXT scenario's plot and
     * making that scenario's own "exactly one rocket stands here" gate refuse. A craft leaves its
     * owner's patch of world under its own power; allocation cannot prevent that, and only disposal
     * can.</p>
     */
    @Before
    public void clearRocketsLeftFlyingByTheLastScenario() throws Exception {
        System.out.println("[reset] rockets cleared from this class's world: "
                + RocketList.clearFrom(this::exec, 0));
    }

    private int rocketIdInThisPlot() throws Exception {
        String list = exec("artest rocket list 0");
        java.util.List<RocketList.Entry> mine = RocketList.inPlot(list, plot());
        // The AGE is in the message because it is what an ambiguous answer turns on: two craft here
        // are either this scenario building twice (both young) or somebody else's craft that moved
        // into this plot (one of them old). The coordinates alone cannot tell those apart, and the
        // reading was made three times before anything carried the number that would have.
        scenario().requireArranged("exactly one rocket must stand in " + plot()
                + " after assemble, found " + mine.size() + " here —" + RocketList.describe(list)
                + " (age is ticks existed: a craft this scenario just assembled is young, one an"
                + " earlier scenario left behind is not)", mine.size() == 1);
        int found = mine.get(0).id;
        scenario().record("rocketId", found);
        return found;
    }

    /** What the server says about one craft, read through the verb's own reader. */
    private RocketInfo rocketInfo(int id) throws Exception {
        return RocketInfo.byId(this::exec, id);
    }

    // ---- the rocket's engine state as EVENTS -------------------------------------------------
    //
    // `EntityRocket.setInFlight` is the one mutator every launch and every touchdown goes through,
    // and a test-only mixin records each write with the rocket it was made on. Waiting for that
    // record is what a poll of `rocket info` cannot be: it cannot race its own start, it cannot miss
    // a flip that happened and was undone between two samples, and its failure prints what the flag
    // DID do instead of one stale reading.

    /** Wait for THIS rocket's in-flight flag to be written {@code inFlight}. */
    private void awaitFlightSet(Events events, long mark, int rocketId, boolean inFlight,
                                String what) throws Exception {
        awaitFlightSet(events, mark, rocketId, inFlight, what, 120);
    }

    /** {@link #awaitFlightSet(Events, long, int, boolean, String)} with an explicit tick budget. */
    private void awaitFlightSet(Events events, long mark, int rocketId, boolean inFlight,
                                String what, int tickBudget) throws Exception {
        events.awaitRecordWithFields(mark, "rocket_flight_set", what, tickBudget,
                "e", String.valueOf(rocketId), "inFlight", String.valueOf(inFlight));
    }

    // WHY EVERY WAIT BELOW NAMES ITS ROCKET. `Events.await` matches on the TYPE alone, and this
    // class is 27 scenarios on ONE shared world: every earlier scenario's rocket is still standing
    // there, several left in flight, and each can write its own in-flight flag or land on its own
    // while a later scenario is watching. A link that could not name the rocket would be answered
    // by a neighbour — the same failure the plot-filtered rocketIdInThisPlot() exists for. The join
    // is the `e` FIELD, through awaitField / awaitRecordWithFields; the local wrapper this replaced
    // matched a raw substring (`"e":<id>,`), which rides on the writer's field ORDER and on the
    // value being followed by a comma.

    /**
     * Hold {@code key} and wait until the input it makes has been APPLIED to THIS rocket on the
     * server — the moment a window driven by that key may start.
     *
     * <p>The record is the server's own {@code applyFreeFlightInput} trace, and the wait names the
     * input's CONTENT ({@code "vert=1"}, {@code "cut=true"}) as well as its rocket. A bare "some trace
     * for this rocket" is also answered by a lifecycle line — the liftoff, the first free-flight
     * tick — and by the input of a key released a moment earlier, so a window opened on it could
     * start before the key it is about had arrived. Matching the trace's wording ties this to a
     * production string; when that string changes the wait EXPIRES, which is the loud direction.
     * The vertical channel is matched on its sign and leading digit only, because the trace formats
     * the number with the server JVM's default locale.</p>
     *
     * <p>Owed, not hoped for: production sends pilot input only when it DIFFERS from the last one
     * sent, and every caller holds a key whose effect is not already in force.</p>
     */
    private void holdKeyUntilApplied(int rocketId, int key, String inputCarries, String what)
            throws Exception {
        Events events = events();
        long mark = events.markInstrumented();
        bot().holdKey(key);
        String id = String.valueOf(rocketId);
        events.awaitMatching(mark, "rocket_ff_traced", reply -> {
            for (String rec : Events.recordsWhere(reply, "e", id)) {
                // The recorder always writes `msg`; a record without one is not an input trace.
                String msg = Events.text(rec, "msg");
                if (msg != null && msg.startsWith("applyFreeFlightInput")
                        && msg.contains(inputCarries)) {
                    return true;
                }
            }
            return false;
        }, "applying an input carrying " + inputCarries + " to rocket " + rocketId, what,
                KEY_APPLIED_BUDGET_TICKS);
    }

    /**
     * Wait until the LATEST Free Flight HUD line the client drew since {@code mark} satisfies
     * {@code drawn}, and answer that line.
     *
     * <p>The latest, not any: the recorder writes a line each time it CHANGES, so the last record is
     * what the pilot is looking at, and a line drawn and then replaced is not. An empty window is NOT
     * YET. The caller must be OWED a record after its mark: the recorder writes nothing for a line
     * identical to the one it drew on the tick before, and nothing at all while the HUD is not drawn
     * — but a HUD that comes back after being away is recorded even with its old line.</p>
     */
    private String awaitHudLine(long mark, java.util.function.Predicate<String> drawn,
                                String matching, String what) throws Exception {
        String reply = clientEvents().awaitMatching(mark, "ff_hud", seen -> {
            String last = Events.lastRecord(seen);
            String line = last == null ? null : Events.text(last, "text");
            return line != null && drawn.test(line);
        }, matching, what, CLIENT_SHOWS_BUDGET_TICKS);
        return Events.text(Events.lastRecord(reply), "text");
    }

    /** The rendered VRT setpoint ({@code group} 1) or actual ({@code group} 2) of a HUD line, or
     *  {@code NaN} when the line draws no VRT pair — which every comparison then answers false. */
    private static double hudVrt(String line, int group) {
        Matcher m = HUD_VRT.matcher(line);
        return m.find() ? Double.parseDouble(m.group(group)) : Double.NaN;
    }

    /**
     * A measurement WINDOW, in client ticks, equal to the ceiling the poll it replaces was allowed.
     *
     * <h2>Why the polls went, and why the number is this number</h2>
     *
     * <p>Nine scenarios here drove a stimulus with {@code ClientPoll.until(…, predicate, step,
     * iterations)} and then asserted <b>the predicate the poll had just exited on</b>. That
     * assertion cannot fail: it either restates a condition already established, or the poll hit its
     * ceiling and the assertion re-checks the same last value — and the failure text then makes a
     * physical claim ("F must reduce vertical velocity") about what is actually a timeout. The poll
     * was the test; the assertion was its echo.</p>
     *
     * <p>So the shape is now: drive the stimulus, wait a WINDOW, read, assert. The window is
     * {@code stepTicks × baseIterations} — <b>exactly the poll's own ceiling</b>, which is
     * what makes the change safe in the only direction that matters: any run the poll would have
     * passed had at most this long to converge, so the physical claim now gets the whole of that
     * budget every time instead of exiting early. What is lost is the early exit (a few seconds of
     * wall clock per scenario on an idle box); what is gained is an assertion that can go red.</p>
     *
     * <p>A window is not a poll: it does not ask, it bounds.</p>
     */
    private int windowTicks(int stepTicks, int baseIterations) {
        return stepTicks * baseIterations;
    }

    // ---------------------------------------------------------------------

    @Test
    public void botMountsFreeFlightRocketAndObservesInFlightFlip() throws Exception {
        // Stand bot near the build site.
        tpNearBuildSite();

        int rocketId = buildAndAssemble();

        // Move bot adjacent to the rocket so mount-entity has line-of-sight.
        tpOntoPad();

        String mount = exec("artest player mount-entity " + rocketId);
        assertTrue("mount-entity must succeed: " + mount,
                Reply.of(mount).ok() && Reply.of(mount).bool("mounted"));

        // Pre-launch: flip mode to FREE_FLIGHT. This is the toggle contract
        // exercised by the M keybind path on a real client.
        String setMode = exec("artest rocket set-flight-mode " + rocketId + " FREE_FLIGHT");
        assertTrue("set-flight-mode must succeed: " + setMode,
                Reply.of(setMode).ok());
        assertTrue("mode echoed FREE_FLIGHT: " + setMode,
                "FREE_FLIGHT".equals(Reply.of(setMode).text("flightMode")));

        // start-free-flight: bypass classic countdown.
        Events events = events();
        long launchMark = events.markInstrumented();
        String start = exec("artest rocket start-free-flight " + rocketId);
        assertTrue("start-free-flight must succeed: " + start,
                Reply.of(start).ok());
        // The probe response itself reflects the immediate isInFlight=true
        // (read in the same call as the mutation).
        assertTrue("start-free-flight must report isInFlight=true in response: " + start,
                Reply.of(start).bool("isInFlight"));
        // And the flag was WRITTEN on the entity, which is what this scenario is named for. The
        // probe reply above is the same call as the mutation and would echo an assignment nobody
        // else can see; the record is taken at `EntityRocket.setInFlight`, the one mutator every
        // launch path goes through, and it names WHICH rocket flipped — this class's world holds
        // every earlier scenario's.
        awaitFlightSet(events, launchMark, rocketId, true,
                "start-free-flight must put THIS rocket in flight");

        // Snapshot info IMMEDIATELY (the real tick loop will drain motionY
        // on the test fixture's low-thrust rocket; what we pin here is that
        // the datawatcher saw isInFlight=true at least once).
        RocketInfo info = rocketInfo(rocketId);
        assertEquals("info must report flightMode=FREE_FLIGHT after toggle: " + info.raw(),
                RocketInfo.FREE_FLIGHT, info.flightMode);

        // Bot is still riding the rocket — FF tick must not dismount the pilot.
        String riding = exec("artest player riding-entity");
        assertTrue("bot must still be riding the FF rocket after takeoff: " + riding,
                String.valueOf(rocketId).equals(Reply.of(riding).text("ridingEntityId")));

        // Cleanup.
        exec("artest player dismount");
    }

    @Test
    public void verticalThrottleProducesObservableMotionThroughRealTickLoop() throws Exception {
        tpNearBuildSite();

        int rocketId = buildAndAssemble();
        tpOntoPad();

        exec("artest player mount-entity " + rocketId);
        exec("artest rocket set-flight-mode " + rocketId + " FREE_FLIGHT");
        exec("artest rocket start-free-flight " + rocketId);

        // Push full vertical throttle. This is the same FreeFlightInput
        // payload that the M-key + Z-key keybind chain sends on a real
        // client press-and-hold.
        String inputResp = exec("artest rocket free-flight-input " + rocketId
                + " 0.0 1.0 0.0 0.0 0.0");
        assertTrue("input must apply on FF rocket: " + inputResp,
                Reply.of(inputResp).bool("applied"));

        // Snapshot motion BEFORE ticks (right after start).
        double myBefore = rocketInfo(rocketId).motionY;

        // WINDOW: twenty ticks of the REAL server loop (onUpdate->tickFreeFlight runs every tick
        // while the rocket is in FF + isInFlight) between the myBefore and myAfter reads; the
        // assertion is over their difference and names both.
        bot().waitTicks(20);

        double myAfter = rocketInfo(rocketId).motionY;

        // After 20 ticks of commanded vertical-up thrust motionY must be net
        // UPWARD relative to the start — thrust ≫ gravity for the simple fixture.
        // Asserting strict increase (not merely "changed") is deliberate: a
        // mere "changed" check passes on gravity alone even if vertical thrust
        // is completely broken, so it would not actually pin the up-thrust
        // contract. A frozen rocket (tick loop not running the FF branch) also
        // fails this.
        assertTrue(
                "commanded vertical-up thrust must raise motionY across 20 server ticks "
                        + "(was " + myBefore + ", now " + myAfter + ")",
                myAfter > myBefore);

        // Bot still riding — FF tick preserves passenger across server ticks.
        String riding = exec("artest player riding-entity");
        assertFalse("FF tick must NOT auto-dismount the pilot mid-flight: " + riding,
                (Reply.of(riding).integer("ridingEntityId") == -1));

        // Cleanup.
        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void modeTogglesAreObservableFromBotSide() throws Exception {
        // Toggle without mounting — exercises the server probe surface that
        // the M-key sends via SET_FLIGHT_MODE packet. The bot just stays
        // connected and observes through the rocket info.
        tpNearBuildSite();

        int rocketId = buildAndAssemble();

        RocketInfo info0 = rocketInfo(rocketId);
        assertEquals("default mode must be CLASSIC_LAUNCH: " + info0.raw(),
                RocketInfo.CLASSIC_LAUNCH, info0.flightMode);

        // No wait: the probe sets the mode on the server thread before it replies, and `rocket
        // info` reads that same server entity.
        exec("artest rocket set-flight-mode " + rocketId + " FREE_FLIGHT");
        RocketInfo info1 = rocketInfo(rocketId);
        assertEquals("after toggle, info must report FREE_FLIGHT: " + info1.raw(),
                RocketInfo.FREE_FLIGHT, info1.flightMode);

        exec("artest rocket set-flight-mode " + rocketId + " CLASSIC_LAUNCH");
        RocketInfo info2 = rocketInfo(rocketId);
        assertEquals("flip-back must restore CLASSIC_LAUNCH: " + info2.raw(),
                RocketInfo.CLASSIC_LAUNCH, info2.flightMode);
    }

    // ===== FF flight controls (TWR-based thrust) =========================

    private int mountFreshFreeFlightRocket() throws Exception {
        final int baseX = baseX(), baseY = BASE_Y, baseZ = baseZ();
        tpNearBuildSite();
        int rocketId = buildAndAssemble();
        tpOntoPad();
        exec("artest player mount-entity " + rocketId);
        exec("artest rocket set-flight-mode " + rocketId + " FREE_FLIGHT");
        Events events = events();
        long launchMark = events.markInstrumented();
        exec("artest rocket start-free-flight " + rocketId);
        // The launch is a COMMIT and the log records it, so it is waited for as one: the flag write
        // cannot be missed between two samples, and a re-land inside the window is a SECOND record
        // rather than an invisible flip.
        awaitFlightSet(events, launchMark, rocketId, true,
                "start-free-flight must put THIS rocket in flight");
        // The v1 takeoff is a decaying kick + grace window; on a slow/contended
        // harness the bot round-trips can outlast it and the rocket re-lands
        // before the test's input arrives, failing on "never moved" instead of
        // the contract under test. Confirm we're STILL airborne, retrying the start —
        // same pattern as the assemble retry above. (The
        // engine-start hover removes the kick and this crutch with it.)
        // The re-issued start is the STIMULUS and `rocket_flight_set` is the link this class uses
        // everywhere else: the flag's own write, carrying the rocket it was made on. The poll of
        // `rocket info` this replaced could only sample the state that record announces, and its
        // "not in flight" was equally produced by a kick that had decayed and by one that had not
        // landed yet. The start is re-sent every 2 ticks, as the loop did, because a kick that
        // decayed is not recoverable by reading longer.
        if (rocketInfo(rocketId).inFlight) {
            return rocketId;
        }
        long restartMark = events.markInstrumented();
        try {
            events.awaitMatching(restartMark, "rocket_flight_set",
                    reply -> !Events.recordsWhereAll(reply, "e", String.valueOf(rocketId),
                            "inFlight", "true").isEmpty(),
                    "putting THIS rocket back in flight",
                    "the takeoff kick must leave the rocket airborne long enough to be flown", 6,
                    () -> exec("artest rocket start-free-flight " + rocketId), 2);
            return rocketId;
        } catch (AssertionError neverLit) {
            // Fall through to the diagnosis below, which prints what the rocket itself says.
        }
        RocketInfo lastInfo = rocketInfo(rocketId);
        String last = lastInfo.raw();
        if (lastInfo.inFlight) {
            return rocketId;
        }
        // ARRANGEMENT, and typed as one: a scenario whose rocket re-landed before it began has not
        // disproved anything about flight controls. The log says which of the two happened — a
        // launch that never committed, or one that committed and was undone by the touchdown
        // detector — where the state read alone could not.
        scenario().arrangementFailed("this rocket must be in flight after start-free-flight (retried"
                + " 3 times, last state " + last + "). What the in-flight flag DID do since the"
                + " launch: " + events.since(launchMark, "rocket_flight_set"));
        return rocketId; // unreachable: arrangementFailed always throws
    }

    @Test
    public void verticalThrustGainsAltitude() throws Exception {
        // The core "can take off" contract: with TWR-based thrust, a
        // launch-capable fixture rocket (TWR ≫ 1) must actually CLIMB under
        // full vertical throttle through the live server tick loop — not just
        // hop and re-land like the old /10000-scaled thrust did.
        int rocketId = mountFreshFreeFlightRocket();

        String inputResp = exec("artest rocket free-flight-input " + rocketId + " 0 1 0 0 0");
        assertTrue("vertical input must apply: " + inputResp, Reply.of(inputResp).bool("applied"));

        double yBefore = rocketInfo(rocketId).posY;
        // WINDOW: thirty ticks of full vertical input between the yBefore and yAfter reads; the
        // climb assertion is over their difference and names both.
        bot().waitTicks(30);
        RocketInfo after = rocketInfo(rocketId);
        double yAfter = after.posY;

        assertTrue("FF rocket must gain real altitude under vertical thrust "
                        + "(yBefore=" + yBefore + " yAfter=" + yAfter + ")",
                yAfter - yBefore > CLIMBED_BLOCKS);
        assertTrue("rocket must still be in flight while climbing: " + after.raw(),
                after.inFlight);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void forwardThrustDisplacesHorizontally() throws Exception {
        // Forward throttle at yaw=0 -> +Z. Vertical kept on so the rocket stays
        // airborne (doesn't auto-land mid-test).
        int rocketId = mountFreshFreeFlightRocket();

        RocketInfo before = rocketInfo(rocketId);
        final double xb = before.posX;
        final double zb = before.posZ;
        exec("artest rocket free-flight-input " + rocketId + " 1 1 0 0 0");
        // WINDOW: between the `before` and `moved` reads, and both assertions are over their
        // difference. Not a poll-until-travelled: the poll exited on "moved more than a block", which
        // is the assertion below, so the displacement could only ever be confirmed or timed out.
        // The window is the poll's own ceiling, so the takeoff-kick grace and the horizontal ramp
        // still get every tick they used to. The probe's own reply is what says the input was
        // ACCEPTED here — this leg drives the input through `free-flight-input` rather than a key,
        // so there is no delivery question left for a link to answer.
        bot().waitTicks(windowTicks(6, 10));
        RocketInfo moved = rocketInfo(rocketId);
        double xa = moved.posX;
        double za = moved.posZ;

        double horiz = Math.sqrt((xa - xb) * (xa - xb) + (za - zb) * (za - zb));
        assertTrue("forward thrust must move the rocket horizontally "
                        + "(horiz=" + horiz + "; before " + before.raw() + "; after " + moved.raw()
                        + ")", horiz > 1.0);
        assertTrue("forward at yaw=0 must be predominantly +Z, got dz=" + (za - zb)
                        + " (zBefore=" + zb + " zAfter=" + za + ")",
                (za - zb) > 0);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void yawInputRotatesHeading() throws Exception {
        // Yaw input must steer the heading through the live loop. Vertical kept
        // on to stay airborne while yawing (yaw rotates regardless of thrust).
        int rocketId = mountFreshFreeFlightRocket();

        exec("artest rocket free-flight-input " + rocketId + " 0 1 1 0 0");
        double yawBefore = rocketInfo(rocketId).rotationYaw;
        // WINDOW: eight ticks of yaw input between the yawBefore and yawAfter reads; the assertion
        // is over their difference and names both.
        bot().waitTicks(8);
        double yawAfter = rocketInfo(rocketId).rotationYaw;

        assertTrue("yaw input must rotate heading over 8 ticks "
                        + "(before=" + yawBefore + " after=" + yawAfter + ")",
                Math.abs(yawAfter - yawBefore) > YAW_TURNED_DEG);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    // ===== REAL keypress path (no server input probe) ====================

    @Test
    public void realVerticalKeyThrustClimbsServerAndClientTracks() throws Exception {
        // The honest end-to-end: FF entry via probes (M/space are event-driven and
        // not the broken part), but STEERING via a REAL injected key. Holding R
        // (flightVerticalUp) drives KeyBindings.onClientTick -> a real
        // FREE_FLIGHT_INPUT packet -> server tickFreeFlight. We assert BOTH halves
        // that the probe tests could never see:
        //   1) the server rocket actually climbs (real packet path delivers thrust),
        //   2) the CLIENT-rendered rocket tracks the server (no poscorrection lag).
        int rocketId = mountFreshFreeFlightRocket();

        // The mark goes BEFORE the key, and here that is not a style rule: production de-duplicates
        // free-flight input upstream of the trace seam (`KeyBindings` sends only when the input
        // DIFFERS from the last one), so a STEADY HELD KEY traces exactly ONCE. A mark taken after
        // the press would miss that one record and the wait would expire on a working build.
        Events climbEvents = events();
        long climbMark = climbEvents.markInstrumented();

        // Hold the real climb key. No artest free-flight-input here on purpose.
        bot().holdKey(Keyboard.KEY_R);

        double svrYBefore = rocketInfo(rocketId).posY;
        // The LINK: the held climb key must reach THIS rocket's free-flight input on the server.
        // This leg's point is that a REAL key does what the probe does, so "the key arrived" is
        // half of its subject and must be asserted as itself rather than inferred from altitude.
        climbEvents.awaitField(climbMark, "rocket_ff_traced", "e", rocketId,
                "holding the real climb key must deliver a free-flight input to this rocket", 100);
        // WINDOW: between the svrYBefore and svrYAfter reads, and the climb assertion is over their
        // difference and names both. The client's Y is read at the window's end beside the server's
        // — one comparison of the two sides, not a second window.
        bot().waitTicks(windowTicks(4, 10));
        RocketInfo svrInfo = rocketInfo(rocketId);
        double svrYAfter = svrInfo.posY;

        JsonObject ride = bot().reportRidingEntity();
        assertTrue("client must still be riding the rocket: " + ride,
                ride.has("riding") && ride.get("riding").getAsBoolean());
        double cliY = ride.get("posY").getAsDouble();

        bot().releaseKey(Keyboard.KEY_R);

        // 1) Real key -> real packet -> server physics.
        assertTrue("holding real R must drive a server-side climb via the packet path "
                        + "(before=" + svrYBefore + " after=" + svrYAfter + ")",
                svrYAfter - svrYBefore > CLIMBED_BLOCKS);
        assertTrue("rocket must stay in flight while climbing: " + svrInfo.raw(),
                svrInfo.inFlight);

        // 2) Client render tracks server — would be ~150 blocks behind with the old
        //    ct=50 poscorrection smoothing that this fix bypasses for FF.
        assertTrue("client-rendered rocket Y must track server Y within a few blocks "
                        + "(client=" + cliY + " server=" + svrYAfter + ")",
                Math.abs(cliY - svrYAfter) < CLIENT_TRACKS_SERVER_BLOCKS);

        exec("artest player dismount");
    }

    @Test
    public void freeFlightClientRenderAdvancesEveryTickNoStutter() throws Exception {
        // Render smoothness: the client must dead-reckon every tick, so the
        // rendered rocket advances on (almost) every single client tick. The
        // snap-only approach froze between the every-3-tick tracker updates and
        // jumped on update ticks — here that shows up as many zero-delta samples.
        int rocketId = mountFreshFreeFlightRocket();
        // Drive a reliable, sustained server-side climb. Probe input is
        // authoritative and not subject to key-injection timing; the bot holds
        // no keys, so onClientTick stays quiet and doesn't override it. (The real
        // keypress path is covered by realZKeyThrustClimbsServerAndClientTracks.)
        // This isolates the actual contract under test: given server motion, does
        // the CLIENT render advance smoothly every tick?
        // The ladder needs a CLIENT that already knows the craft is climbing: until a velocity
        // update reaches it, the client has nothing to dead-reckon with and every sample reads
        // still — the freeze this test exists to catch, manufactured by starting too early. The
        // HUD's VRT "actual" is drawn from the client rocket's own motion, which is exactly that
        // knowledge. A change is owed: the input just applied moves the craft off its hover, and
        // the setpoint beside the actual starts ramping.
        long climbSeenMark = clientEvents().mark();
        exec("artest rocket free-flight-input " + rocketId + " 0 1 0 0 0");
        awaitHudLine(climbSeenMark, line -> hudVrt(line, 2) > 0,
                "drawing a positive VRT actual",
                "the client must be told the craft is climbing before its per-tick render is sampled");

        // A SAMPLING LADDER, not a poll: the per-tick delta IS the subject, so the reads cannot
        // be collapsed into one window read at the end — "it advanced every tick" and "it froze
        // then jumped" have the same endpoints. There is no early exit and no acceptance test in
        // the header: the ladder always spends its eight ticks and the ratio is the finding.
        int samples = 8;
        int moved = 0;
        double prev = bot().reportRidingEntity().get("posY").getAsDouble();
        // WINDOW: eight consecutive client ticks, each read against the one before — the per-tick
        // delta is the measurement, on the clock the render it measures advances on.
        for (int i = 0; i < samples; i++) {
            bot().waitTicks(1);
            double cur = bot().reportRidingEntity().get("posY").getAsDouble();
            if (cur - prev > 1e-4) moved++;
            prev = cur;
        }
        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");

        assertTrue("FF client render must advance on (almost) every tick rather than "
                        + "freeze-then-jump (moved " + moved + "/" + samples + ")",
                moved >= samples - 2);

        exec("artest player dismount");
    }

    // ===== HUD =========================================================

    /** The TEST-side holder of what the pilot's own view last did — the HUD line, the
     *  camera-vs-craft divergence and the client attitude readback. Production keeps none of
     *  them: they were written on the render thread purely for these reads. */
    private static final String FLIGHT_CAMERA =
            "zmaster587.advancedRocketry.test.trace.FlightCameraState";

    @Test
    public void freeFlightHudInFlightShowsIndicatorAndControlLegend() throws Exception {
        // Riding a FF rocket in flight: the HUD must render the mode indicator,
        // a control legend keyed to the pilot's bindings, and the FA state. Read
        // the actually-rendered text from the client (reflective static), so a
        // missing lang key (which I18n echoes back raw) fails these assertions.
        // The mark goes before the whole arrangement, so the in-flight form is a change after it
        // whatever the previous scenario left drawn. That form is recognised by its ENGINE line,
        // which none of the assertions below reads, so the wait cannot answer for them. A record
        // is owed within one flash: "Engines started" gives way to ENGINES ON sixty client ticks
        // after the client sees the flight begin, and each is a new line.
        long hudMark = clientEvents().mark();
        int rocketId = mountFreshFreeFlightRocket();
        String hud = awaitHudLine(hudMark,
                line -> line.contains("ENGINES ON") || line.contains("Engines started"),
                "drawing the in-flight engine line",
                "a pilot whose rocket is in flight must be shown the in-flight HUD");

        assertTrue("FF HUD must show the active-mode indicator: " + hud,
                hud.contains("FREE FLIGHT"));
        assertTrue("FF HUD must show a vertical-thrust control hint: " + hud,
                hud.contains("Up / Down"));
        assertTrue("FF HUD must show the Flight Assist state: " + hud,
                hud.contains("Flight Assist"));

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void freeFlightHudPreLaunchShowsLaunchHint() throws Exception {
        // FF mode, mounted but NOT launched: HUD shows the title + how to launch /
        // switch back to classic — distinct from the in-flight legend.
        tpNearBuildSite();
        int rocketId = buildAndAssemble();
        tpOntoPad();
        // THE SUBJECT HERE IS A TRANSIENT STATE, so what is asserted is the RECORD OF ITS
        // APPEARANCE, not a live read taken afterwards. The pre-launch HUD is what the pilot sees
        // between entering the mode and starting the engines; the `ff_hud` recorder writes a record
        // each time the line CHANGES, so the moment it said this is in the log whether or not it is
        // still saying it when anybody looks.
        //
        // Measured 2026-09-15, and it is why this is not a mount link: converting the ten ticks here
        // into a wait for the client's own mount made the read LATER, the engines had started by
        // then, and the assertion failed on a HUD that was correct for the moment it was read. A
        // live read of a transient state is a race that no wait can win — a longer one loses harder.
        long hudMark = clientEvents().mark();
        exec("artest player mount-entity " + rocketId);
        exec("artest rocket set-flight-mode " + rocketId + " FREE_FLIGHT");
        String hud = Events.text(Events.lastRecord(clientEvents().awaitMatching(hudMark, "ff_hud",
                seen -> !Events.recordsContainingAll(seen, "Free Flight Mode", "ENGINES OFF").isEmpty(),
                "carrying the pre-launch title AND the engine-start hint",
                "entering free-flight mode must draw the pre-launch HUD — its mode title and its"
                        + " ENGINES OFF hint — for the pilot who just sat down",
                SEAT_LINK_BUDGET_TICKS)), "text");

        assertTrue("pre-launch FF HUD must show the mode title: " + hud,
                hud.contains("Free Flight Mode"));
        assertTrue("pre-launch FF HUD must show the engine-start hint: " + hud,
                hud.contains("ENGINES OFF"));
        assertTrue("pre-launch FF HUD must show the classic-mode toggle hint: " + hud,
                hud.contains("Classic mode"));

        exec("artest player dismount");
    }

    @Test
    public void verticalThrustDrainsFuelThroughLiveLoop() throws Exception {
        // Fuel must burn classic-style (getFuelConsumptionRate, gated by
        // rocketRequireFuel) while thrust is applied across real server ticks.
        int rocketId = mountFreshFreeFlightRocket();

        int fuelBefore = primaryFuelAmount(exec("artest rocket fuel " + rocketId));
        assertTrue("rocket must report a primary fuel amount", fuelBefore >= 0);
        assertTrue("start-free-flight must auto-fill fuel, got " + fuelBefore, fuelBefore > 0);

        exec("artest rocket free-flight-input " + rocketId + " 0 1 0 0 0");
        // WINDOW: twenty ticks of thrust between the fuelBefore and fuelAfter reads; the drain
        // assertion is over their difference and names both.
        bot().waitTicks(20);

        int fuelAfter = primaryFuelAmount(exec("artest rocket fuel " + rocketId));
        assertTrue("rocket must still report a primary fuel amount", fuelAfter >= 0);
        assertTrue("FF thrust must drain primary fuel through the live loop; "
                        + "before=" + fuelBefore + " after=" + fuelAfter,
                fuelAfter < fuelBefore);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    // ===== Key-conflict resolution (ARKeyConflictContext) =================
    // Steering keys share defaults with vanilla (E inventory, Q drop, A/D
    // strafe). These honest client tests prove the two halves of the contract:
    // the standard key behaves vanilla on foot, and is overridden — not merely
    // shadowed — while piloting. Both observe the REAL client (GUI screen +
    // client-driven server state), never a server probe stand-in.

    /** Default inventory key (E) — the vanilla binding pitch-down shares a key with. */
    private static final int KEY_INVENTORY = Keyboard.KEY_E;

    private String currentScreen() throws Exception {
        return bot().reportState().get("screen").getAsString();
    }

    @Test
    public void inventoryKeyOpensInventoryWhenNotPiloting() throws Exception {
        // On foot (not piloting any AR craft), pressing the inventory key must
        // open the survival inventory exactly like vanilla — i.e. the FF key
        // override does NOT leak into normal gameplay.
        tpNearBuildSite();
        // Guarantee the precondition: not riding, no GUI up.
        //
        // READ FIRST, WAIT ONLY IF HE IS ABOARD — and this branch is not an optimisation. A
        // dismount record exists only where there was something to dismount FROM, so on the common
        // path (he is already on his feet) an unconditional wait for one spends its whole budget on
        // a state that was already correct and then fails. Measured 2026-09-15: exactly that, twice,
        // after this very conversion — the same empty-window trap the shared mount wait was repaired
        // for hours earlier, met again from the other side.
        long offMark = clientEvents().mark();
        boolean wasRiding = bot().reportRidingEntity().get("riding").getAsBoolean();
        exec("artest player dismount");
        bot().closeScreen();
        if (wasRiding) {
            clientEvents().await(offMark, "dismount",
                    "the client must LET GO of the rocket before a key is pressed as a pedestrian",
                    SEAT_LINK_BUDGET_TICKS);
        }
        assertEquals("precondition: no screen should be open before pressing E",
                "", currentScreen());

        // The key is held until the client opens a screen, and the record of that is its own:
        // `client_gui_opened`, which a CLOSE also writes (as "none") — hence the filter. A screen is
        // owed, because none is open (asserted above). WHICH screen is left to the assertion below,
        // so a key that opened the wrong one still fails here.
        long guiMark = clientEvents().mark();
        bot().setKey(KEY_INVENTORY, true);
        clientEvents().awaitMatching(guiMark, "client_gui_opened", seen -> {
            for (String rec : Events.records(seen)) {
                if (!"none".equals(Events.text(rec, "gui"))) {
                    return true;
                }
            }
            return false;
        }, "opening a screen", "pressing the inventory key on foot must open a screen",
                CLIENT_SHOWS_BUDGET_TICKS);
        String screen = currentScreen();
        bot().setKey(KEY_INVENTORY, false);
        bot().closeScreen();

        // Survival opens GuiInventory; creative opens GuiContainerCreative —
        // both are the vanilla inventory-key action, which is the point.
        assertTrue("pressing the inventory key on foot must open the inventory GUI, got: "
                        + screen,
                screen.endsWith("GuiInventory") || screen.endsWith("GuiContainerCreative"));
    }

    @Test
    public void inventoryKeyIsOverriddenToStrafeWhilePiloting() throws Exception {
        // Same physical key (E), while piloting in Free Flight: it must NOT open
        // the inventory (which would also freeze steering) and must instead drive
        // the lateral strafe control through the real key->packet->server path.
        int rocketId = mountFreshFreeFlightRocket();
        assertEquals("precondition: no GUI open while piloting", "", currentScreen());

        double xBefore = rocketInfo(rocketId).posX;

        // Hold vertical-up (R, keeps it airborne so the FF tick keeps running) AND
        // the inventory key (E). On foot E opens the inventory; here it must
        // strafe. E = strafe right, which after the polarity fix commands -X at
        // yaw 0 (world +X renders on the pilot's left out the nose).
        Events eEvents = events();
        long eMark = eEvents.markInstrumented();
        bot().holdKey(Keyboard.KEY_R);
        bot().holdKey(KEY_INVENTORY);
        // The LINK first: the held keys must reach the rocket's own free-flight input. A
        // displacement of zero is produced both by a strafe that did not happen and by a key the
        // client ate into a GUI — and this leg's subject is which of those E does. The link does
        // not single out E (R is down too, and the record carries no input values); what pins E is
        // the screen check and the -X direction below.
        eEvents.awaitField(eMark, "rocket_ff_traced", "e", rocketId,
                "the held keys must deliver a free-flight input to this rocket while E is down",
                100);
        // WINDOW: between the xBefore and xAfter reads, and the strafe assertion is over their
        // difference and names both; the screen is read at the window's end, when an inventory
        // the key had opened would be showing.
        bot().waitTicks(windowTicks(5, 5));

        String screenDuring = currentScreen();
        RocketInfo info = rocketInfo(rocketId);
        double xAfter = info.posX;

        bot().releaseKey(KEY_INVENTORY);
        bot().releaseKey(Keyboard.KEY_R);

        assertEquals("inventory key must NOT open the inventory while piloting "
                + "(would also freeze steering): " + screenDuring, "", screenDuring);
        assertTrue("inventory key must instead strafe the craft (-X at yaw 0) while piloting "
                        + "(xBefore=" + xBefore + " xAfter=" + xAfter + ")",
                xAfter - xBefore < STRAFED_BLOCKS);
        assertTrue("rocket must stay in flight (override must not have frozen control): "
                + info.raw(), info.inFlight);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void strafeLeftKeyMovesPositiveX() throws Exception {
        // Q (strafe left) -> +X at yaw 0 after the polarity fix: with the camera
        // looking out the nose, world +X renders on the pilot's LEFT, so the
        // strafe-left key must push the craft toward +X to feel correct (the raw
        // body-right mapping felt inverted in playtest). E is the mirror.
        int rocketId = mountFreshFreeFlightRocket();
        double xBefore = rocketInfo(rocketId).posX;

        Events qEvents = events();
        long qMark = qEvents.markInstrumented();
        bot().holdKey(Keyboard.KEY_R);          // stay airborne
        bot().holdKey(Keyboard.KEY_Q);          // strafe left
        // The LINK first: the held keys' input must reach THIS rocket's free-flight input on the
        // server. That separates "the keys never got there" from "they got there and moved nothing"
        // — which the displacement below cannot, and neither could the poll that stood here. It
        // does NOT single out Q: two keys are down and the record carries no input values worth
        // matching on (its message is production's own trace string, which is scheduled to go), so
        // the claim is "the key path is alive for this rocket", and the DIRECTION is the assertion.
        qEvents.awaitField(qMark, "rocket_ff_traced", "e", rocketId,
                "the held keys must deliver a free-flight input to this rocket", 100);
        // WINDOW: between the xBefore and xAfter reads; the assertion is over their difference and
        // names both.
        bot().waitTicks(windowTicks(5, 5));
        double xAfter = rocketInfo(rocketId).posX;
        bot().releaseKey(Keyboard.KEY_Q);
        bot().releaseKey(Keyboard.KEY_R);

        assertTrue("Q must strafe +X at yaw 0 (xBefore=" + xBefore + " xAfter=" + xAfter + ")",
                xAfter - xBefore > 1.0);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void verticalKeysClimbAndDescend() throws Exception {
        // R climbs (real altitude gain); F is the opposite vertical thrust, so it
        // must drive the vertical velocity down. We measure F by motionY (robust
        // to the climb's accumulated upward inertia, which a position check is not).
        int rocketId = mountFreshFreeFlightRocket();

        // Each window below opens when its key's input has ARRIVED, so the time a packet takes to
        // cross is not charged against the climb or the brake being measured.
        holdKeyUntilApplied(rocketId, Keyboard.KEY_R, "vert=1",
                "holding R must deliver a climb input to this rocket");
        double y0 = rocketInfo(rocketId).posY;
        // WINDOW: twenty ticks of R between the y0 and y1 reads; the climb assertion is over their
        // difference and names both.
        bot().waitTicks(20);
        RocketInfo climbInfo = rocketInfo(rocketId);
        double y1 = climbInfo.posY;
        double myUp = climbInfo.motionY;
        bot().releaseKey(Keyboard.KEY_R);
        assertTrue("R must climb (y0=" + y0 + " y1=" + y1 + ")", y1 - y0 > CLIMBED_BLOCKS);

        // F is downward thrust: it must reduce the vertical velocity vs the climb.
        holdKeyUntilApplied(rocketId, Keyboard.KEY_F, "vert=-1",
                "holding F must deliver a descent input to this rocket");
        // WINDOW: between the myUp and myDown reads, and the assertion is over their difference
        // and names both. Not a poll-until-braked: the poll's predicate was `my < myUp - 0.05` and
        // the assertion below is the same expression, so nothing could fail except the ceiling.
        bot().waitTicks(windowTicks(5, 2));
        double myDown = rocketInfo(rocketId).motionY;
        bot().releaseKey(Keyboard.KEY_F);
        assertTrue("F must reduce vertical velocity vs the climb (myUp=" + myUp
                + " myDown=" + myDown + ")", myDown < myUp - 0.05);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void throttleCutKeyNeutralisesThrust() throws Exception {
        // X (cut) with FA on zeroes the velocity setpoint even while R is held:
        // a climbing craft stops accelerating and eases back toward hover.
        int rocketId = mountFreshFreeFlightRocket();

        // Establish a climb. Both legs below start counting when their key's input has ARRIVED, so
        // the time a packet takes to cross is not charged against what is measured.
        holdKeyUntilApplied(rocketId, Keyboard.KEY_R, "vert=1",
                "holding R must deliver a climb input to this rocket");
        // EXPERIMENT: twelve ticks of R from its arrival is the dose, and the precondition is what
        // they did — upward motion. Not a difference from the moment R arrived: the liftoff hover
        // may still be rising then, and the pilot's setpoint takes over from zero.
        bot().waitTicks(12);
        double myClimb = rocketInfo(rocketId).motionY;
        assertTrue("precondition: R must be producing upward motion twelve ticks after it"
                        + " arrived, got " + myClimb,
                myClimb > PRODUCING_CLIMB);

        // Now also hold X (cut) — vertical input is zeroed; with FA-off coast the
        // craft no longer accelerates upward (motionY stops growing).
        holdKeyUntilApplied(rocketId, Keyboard.KEY_X, "cut=true",
                "holding X must deliver a throttle cut to this rocket");
        double myAtCut = rocketInfo(rocketId).motionY;
        // WINDOW: between the myAtCut and myCut reads; the assertion is over their difference and
        // names both.
        bot().waitTicks(12);
        double myCut = rocketInfo(rocketId).motionY;
        bot().releaseKey(Keyboard.KEY_X);
        bot().releaseKey(Keyboard.KEY_R);

        assertTrue("throttle-cut must stop upward acceleration (when the cut arrived " + myAtCut
                + ", twelve ticks later " + myCut + "; the climb had reached " + myClimb + ")",
                myCut <= myAtCut + 1e-3);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void cutKeyBrakesToAGravityCancelledHover() throws Exception {
        // X with Flight Assist on: zero the velocity setpoint —
        // the craft eases to a stop AND holds altitude. Build a climb via the
        // real R key first, then hold the real X.
        int rocketId = mountFreshFreeFlightRocket();

        holdKeyUntilApplied(rocketId, Keyboard.KEY_R, "vert=1",
                "holding R must deliver a climb input to this rocket");
        // STIMULUS: fifteen ticks of R from its arrival builds the climb the cut is then asked to
        // brake; the check below says whether it did.
        bot().waitTicks(15);
        bot().releaseKey(Keyboard.KEY_R);
        RocketInfo preInfo = rocketInfo(rocketId);
        double myMoving = preInfo.motionY;
        if (!(Math.abs(myMoving) > 0.02)) {
            // Diagnose before failing: one SYNCHRONOUS physics step shows whether
            // the physics produces thrust and what immediately eats it.
            String singleStep = exec("artest rocket free-flight-tick " + rocketId + " 1");
            String postStep = rocketInfo(rocketId).raw();
            throw new AssertionError("precondition: rocket must be climbing before the cut, got "
                    + myMoving + "\n  state: " + preInfo.raw()
                    + "\n  single-step: " + singleStep
                    + "\n  after-step: " + postStep);
        }

        holdKeyUntilApplied(rocketId, Keyboard.KEY_X, "cut=true",
                "holding X must deliver a throttle cut to this rocket");
        // EXPERIMENT: forty SERVER ticks of cut from its arrival is the dose, and the assertion is
        // what they did — braked to a hover. The brake is the rocket's own server-side update, so
        // its clock is the server's: counted there, forty is forty brake steps on any box, where
        // forty client ticks bought a busy one fewer. It replaced a poll whose predicate,
        // `|my| < 0.05`, was the assertion below, so the old leg could only time out, never fail.
        GameTicks.advance(serverClient(), GameTicks.server(), windowTicks(5, 8));
        RocketInfo info = rocketInfo(rocketId);
        double myCut = info.motionY;
        bot().releaseKey(Keyboard.KEY_X);

        assertTrue("cut must brake the climb into a hover (was " + myMoving
                + ", now " + myCut + ")", Math.abs(myCut) < BRAKED_TO_HOVER);
        assertTrue("the hover must hold altitude, not land: " + info.raw(), info.inFlight);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    // ===== Engine start =====================================
    //
    // The REAL path: hold the actual jump key (Space) on the client for 3 s;
    // KeyBindings.onClientTick accumulates the hold and sends ENGINE_START;
    // the server validates and starts the hover. No probe shortcut here.

    /** Build + mount + flip to FREE_FLIGHT, but do NOT start the engines. */
    private int mountColdFreeFlightRocket() throws Exception {
        final int baseX = baseX(), baseY = BASE_Y, baseZ = baseZ();
        tpNearBuildSite();
        int rocketId = buildAndAssemble();
        tpOntoPad();
        long seatedMark = clientEvents().mark();
        exec("artest player mount-entity " + rocketId);
        exec("artest rocket set-flight-mode " + rocketId + " FREE_FLIGHT");
        // Fuel up (a freshly-assembled fixture is empty): the ENGINE_START
        // validation honestly rejects a dry rocket, which is its own contract —
        // these tests exercise the start RITUAL, so they fly fuelled.
        String fuel = exec("artest rocket fill-fuel " + rocketId);
        assertTrue("fill-fuel must succeed: " + fuel, Reply.of(fuel).ok());
        // The start ritual is the CLIENT's: its key handler counts a Space hold only for a pilot it
        // knows is seated in a free-flight rocket. The pre-launch HUD is drawn exactly then, so its
        // appearance is the link — the mount and the mode have both reached the client.
        awaitHudLine(seatedMark, line -> line.contains("Free Flight Mode"),
                "drawing the pre-launch title",
                "the pilot's client must be seated in the free-flight rocket before he reaches for"
                        + " the start key");
        return rocketId;
    }

    @Test
    public void realSpaceHoldStartsEnginesAndHoversOneBlock() throws Exception {
        int rocketId = mountColdFreeFlightRocket();
        RocketInfo before = rocketInfo(rocketId);
        assertFalse("precondition: engines off before the hold: " + before.raw(), before.inFlight);
        double y0 = before.posY;

        // The start ritual ENDS in a commit — the client's 60-tick hold completes, the server's
        // gate accepts it, and the engines are lit by writing the in-flight flag. Wait for that
        // record with the key still held, instead of holding for a fixed 75 ticks and asking
        // afterwards: the fixed wait cannot tell "the hold never completed on the client" from "it
        // completed and the server refused" from "it started and re-landed", and all three arrive
        // here as isInFlight=false.
        Events events = events();
        long holdMark = events.markInstrumented();
        long hudMark = clientEvents().mark();
        bot().holdKey(Keyboard.KEY_SPACE);
        try {
            awaitFlightSet(events, holdMark, rocketId, true,
                    "a 3 s hold of the real Space key must start THIS rocket's engines", 200);
        } finally {
            bot().releaseKey(Keyboard.KEY_SPACE);
        }
        // EXPERIMENT: forty SERVER ticks after the engines lit is the moment the claim is about —
        // the start ritual's ease (the rocket's own server update, gain 0.25 a tick) has closed on
        // its fixed hover target within a handful of them. Counted on the server, forty is forty
        // steps of that law on any box; more of them move neither number, the target being fixed.
        GameTicks.advance(serverClient(), GameTicks.server(), 40);

        RocketInfo info = rocketInfo(rocketId);
        assertTrue("3 s Space hold must start the engines (isInFlight=true): " + info.raw(),
                info.inFlight);
        double y = info.posY;
        double my = info.motionY;
        assertTrue("craft must hover ~1 block above the pad (y0=" + y0 + " y=" + y + ")",
                y > y0 + 0.5 && y < y0 + 1.6);
        assertTrue("hover must be near-stationary (motionY=" + my + ")",
                Math.abs(my) < HOVER_STATIONARY);

        // The pilot SEES the engine state: the rendered HUD reports ENGINES ON
        // (or the transient "Engines started" flash right after the start). What the client draws
        // is its own event, not a consequence of the forty ticks above: the wait is for the
        // in-flight form, recognised by its title, which the assertion does not read. Owed: the
        // hold's progress lines come after the mark, and even an in-flight line identical to one
        // drawn before it is followed by ENGINES ON when the start flash ends, sixty ticks in.
        String hud = awaitHudLine(hudMark, line -> line.contains("FREE FLIGHT"),
                "drawing the in-flight title",
                "the pilot must be shown the in-flight HUD once the engines are lit");
        assertTrue("HUD must show the engines running: " + hud,
                hud.contains("ENGINES ON") || hud.contains("Engines started"));

        exec("artest player dismount");
    }

    @Test
    public void spaceEarlyReleaseCancelsEngineStart() throws Exception {
        int rocketId = mountColdFreeFlightRocket();

        Events events = events();
        long holdMark = events.markInstrumented();
        long holdHudMark = clientEvents().mark();
        bot().holdKey(Keyboard.KEY_SPACE);
        // STIMULUS: twenty-five ticks of Space, well under the 60-tick requirement — the early
        // release is the experiment.
        bot().waitTicks(25);

        // Mid-hold the pilot must SEE the start progress — the line ON SCREEN now, with the key still
        // down, which is the latest record since the hold began. Any progress line in the history
        // would also be satisfied by a counter that reset mid-hold: one "STARTING ENGINES" drawn and
        // then replaced by "ENGINES OFF" while Space was still held.
        String holdHud = clientEvents().since(holdHudMark, "ff_hud");
        String onScreen = Events.lastRecord(holdHud);
        assertTrue("HUD must show engine-start progress while holding - on screen now: " + onScreen
                        + " | since the hold began: " + holdHud,
                onScreen != null && String.valueOf(Events.text(onScreen, "text"))
                        .contains("STARTING ENGINES"));

        long releaseHudMark = clientEvents().mark();
        bot().releaseKey(Keyboard.KEY_SPACE);
        // The release is the client's to act on, and it shows it: the progress line gives way to
        // the plain pre-launch form. Recognised by the title and the progress line's absence, so the
        // ENGINES OFF assertion below still reads something the wait did not ask for.
        String hud = awaitHudLine(releaseHudMark,
                line -> line.contains("Free Flight Mode") && !line.contains("STARTING ENGINES"),
                "drawing the pre-launch title without the progress line",
                "releasing Space early must put the pre-launch HUD back");
        // And the negative below needs a BOUND. "The engines were never lit" is a statement about
        // everything the client sent up to its release — the hold, and whatever the release tick
        // did — and a start sent at that tick would reach the server after any fixed pause chosen
        // for a fast box. The fence closes it exactly: once the server has echoed a line sent after
        // the release was drawn, every packet before it has run.
        fenceWhatTheClientSent("the server must have handled everything the client sent up to"
                + " the release before 'never lit' can be read");

        RocketInfo info = rocketInfo(rocketId);
        assertFalse("early release must cancel the start (still not in flight): " + info.raw(),
                info.inFlight);
        // The state read above is the end state; the CONTRACT is that the engines never lit at all,
        // and only the log can say that — a start that lit and re-landed inside the window leaves
        // exactly the same isInFlight=false behind. The silence means something because the HUD
        // assertion above proves the hold was really under way (the subject COULD have started), and
        // because the recorder is asked whether it was listening.
        String flightWrites = events.since(holdMark, "rocket_flight_set");
        Events.assertInstrumentRan(flightWrites, ROCKET_INSTRUMENT,
                "an early-released hold never wrote this rocket's in-flight flag");
        assertTrue("releasing the key early must mean the engines were never lit — not lit and then"
                        + " shut off again. Writes to any rocket's in-flight flag since the hold"
                        + " began: " + flightWrites,
                Events.recordsWhere(flightWrites, "e", String.valueOf(rocketId)).isEmpty());
        assertTrue("HUD must be back to ENGINES OFF after the cancel: " + hud,
                hud.contains("ENGINES OFF"));

        exec("artest player dismount");
    }

    @Test
    public void descendKeyLandsAndShutsEnginesOff() throws Exception {
        // Full cycle through real keys: start via probe (covered above), then
        // descend with the real F key until touchdown — engines must shut off
        // and the HUD must say so.
        // What the descent needs before it starts is not a settled hover but an ARMED landing
        // detector: production arms it only once the craft has left the ground, so a craft pushed
        // down before its liftoff never "lands" at all. The arming is its own trace, and the chain
        // is read from a mark before the launch: the LAST liftoff must follow the LAST start, since a
        // start re-arms nothing until the craft lifts again.
        Events events = events();
        long flightMark = events.markInstrumented();
        int rocketId = mountFreshFreeFlightRocket();
        String id = String.valueOf(rocketId);
        events.awaitMatching(flightMark, "rocket_ff_traced", reply -> {
            int lastStart = -1;
            int lastLiftoff = -1;
            java.util.List<String> mine = Events.recordsWhere(reply, "e", id);
            for (int i = 0; i < mine.size(); i++) {
                // The recorder always writes `msg`; a record without one is no lifecycle line.
                String msg = Events.text(mine.get(i), "msg");
                if (msg == null) {
                    continue;
                }
                if (msg.startsWith("startFreeFlight")) {
                    lastStart = i;
                } else if (msg.startsWith("liftoff")) {
                    lastLiftoff = i;
                }
            }
            return lastLiftoff > lastStart;
        }, "arming the landing detector after the last start",
                "the craft must lift off its pad, or no descent can end in a touchdown", 120);

        long descentMark = events.markInstrumented();
        long shutdownHudMark = clientEvents().mark();
        bot().holdKey(Keyboard.KEY_F);
        try {
            // A touchdown is two commits, not a state: the engines are shut off (the in-flight flag
            // is written false) and the landing is ANNOUNCED on the bus for everything that reacts
            // to a rocket arriving. The poll it replaces read the flag's end state, which a rocket
            // that was never airborne satisfies just as well, and never saw the announcement at all.
            awaitFlightSet(events, descentMark, rocketId, false,
                    "descending into the ground must shut THIS rocket's engines off", 240);
            events.awaitField(descentMark, "rocket_landed", "e", rocketId,
                    "a touchdown must be announced on the bus, or nothing that reacts to a rocket"
                            + " arriving ever hears about it", 120);
        } finally {
            bot().releaseKey(Keyboard.KEY_F);
        }

        // The client learns of the shutdown on its own schedule; what it draws then is the
        // pre-launch form, recognised by its title, which the assertion does not read. Owed as long
        // as the client drew the flight at all — before the mark or after it — since the pre-launch
        // form then differs from the last line it drew.
        String hud = awaitHudLine(shutdownHudMark, line -> line.contains("Free Flight Mode"),
                "drawing the pre-launch title",
                "a pilot whose engines were shut off must be shown the pre-launch HUD again");
        assertTrue("HUD must reflect the shutdown (stopped flash or ENGINES OFF): " + hud,
                hud.contains("Engines stopped") || hud.contains("ENGINES OFF"));

        exec("artest player dismount");
    }

    // ===== HUD indication ===============================

    private static final Pattern HUD_VRT =
            Pattern.compile("VRT ([+-][0-9.]+)/([+-][0-9.]+)");

    @Test
    public void hudVectorLineTracksSetpointAndVelocity() throws Exception {
        // The per-axis vector readout: holding R ramps the VRT setpoint and the
        // actual velocity follows; X zeroes the setpoint back. Read from the
        // REAL rendered HUD text.
        int rocketId = mountFreshFreeFlightRocket();

        holdKeyUntilApplied(rocketId, Keyboard.KEY_R, "vert=1",
                "holding R must deliver a climb input to this rocket");
        // EXPERIMENT: twenty-five ticks of R from its arrival is the dose, and the assertions are
        // what it did to the ramped setpoint and the velocity chasing it. It replaced a poll that
        // exited on `setpoint > 0.4 && actual > 0.1` — exactly the pair of assertions below — so
        // neither could fail except by the ceiling, and the failure text then blamed the ramp for a
        // timeout. Both numbers are read off ONE rendered HUD frame, which is what lets "the actual
        // is chasing the setpoint" be a statement about one moment.
        bot().waitTicks(windowTicks(5, 5));
        String hudClimb = freeFlightHud();
        bot().releaseKey(Keyboard.KEY_R);

        Matcher m = HUD_VRT.matcher(hudClimb);
        assertTrue("HUD must render the VRT setpoint/actual pair: " + hudClimb, m.find());
        double sp = Double.parseDouble(m.group(1));
        double act = Double.parseDouble(m.group(2));
        assertTrue("VRT setpoint must have ramped up while R held (got " + sp + ")",
                sp > VRT_SETPOINT_RAMPED);
        assertTrue("actual velocity must chase the setpoint (got " + act + ")",
                act > VELOCITY_CHASES_SETPOINT);
        assertTrue("HUD must show the speed readout: " + hudClimb,
                hudClimb.contains("SPD"));

        // Cut: the setpoint marker must return to zero on the rendered HUD. Not a converging value:
        // production zeroes the whole setpoint in ONE step when a cut arrives, so the zero is a
        // change the client draws, and the draw is the record. The wait IS the assertion — its
        // failure prints every line the HUD drew since the key went down. Owed: the setpoint read
        // above is well off zero.
        long cutHudMark = clientEvents().mark();
        bot().holdKey(Keyboard.KEY_X);
        awaitHudLine(cutHudMark, line -> Math.abs(hudVrt(line, 1)) <= 0.01,
                "drawing a VRT setpoint of zero",
                "the cut must zero the VRT setpoint the pilot's HUD shows");
        bot().releaseKey(Keyboard.KEY_X);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void hudShowsNewtonianLabelWhenFlightAssistIsOff() throws Exception {
        // FA state is part of the perception contract: with FA off the HUD
        // must say so (the N keybind path is edge-driven and not injectable —
        // the probe flips the same server state the key would).
        int rocketId = mountFreshFreeFlightRocket();

        // Each flip reaches the client as a packet and shows as a changed in-flight title. The wait
        // is for the title's FA STATE; the assertion reads the Newtonian label beside it, so a HUD
        // whose state and label disagree still fails. Both are owed: FA starts on, and each probe
        // flips it.
        long offMark = clientEvents().mark();
        exec("artest rocket set-flight-assist " + rocketId + " off");
        String hud = awaitHudLine(offMark, line -> line.contains("Flight Assist: OFF"),
                "drawing Flight Assist OFF",
                "switching Flight Assist off must reach the pilot's HUD");
        assertTrue("HUD must label the Newtonian mode when FA is off: " + hud,
                hud.contains("Newtonian"));

        long onMark = clientEvents().mark();
        exec("artest rocket set-flight-assist " + rocketId + " on");
        String hudOn = awaitHudLine(onMark, line -> line.contains("Flight Assist: ON"),
                "drawing Flight Assist ON",
                "switching Flight Assist back on must reach the pilot's HUD");
        assertFalse("HUD must drop the Newtonian label when FA is back on: " + hudOn,
                hudOn.contains("Newtonian"));

        exec("artest player dismount");
    }

    // ===== Camera-nose lock + mouse-as-rate =================
    //
    // THE perception contract that v1 missed: the view and the nose must
    // never diverge. These tests read BOTH sides from the real client
    // (reportState for the player camera, reportRidingEntity for the craft)
    // and inject look changes the way the mouse produces them (setLook).

    /** Wrapped angular distance on the circle, degrees in [0, 180]. */
    private static double angDiff(double a, double b) {
        return Math.abs(((a - b + 540) % 360) - 180);
    }

    @Test
    public void cameraIsLockedToCraftYawAndPitchWhileManeuvering() throws Exception {
        // While actively maneuvering (climb + yaw key + mouse swipes), the
        // client camera must stay pinned to the craft axes on every sampled
        // tick (loose eps: the two bot reads aren't atomic, one tick may pass
        // between them — max craft turn is 6°/tick). Then, with all input
        // released and corrections bled out, the lock must be exact.
        int rocketId = mountFreshFreeFlightRocket();

        // The extrema below belong to THIS leg. Opened here rather than inherited: the accumulator
        // is one per client JVM, and a scenario that reads it without opening is reading whatever
        // the previous one left — which is the whole reason these numbers moved off a static field
        // and behind a window.
        long camMark = openFlightCameraWindow();

        bot().holdKey(Keyboard.KEY_R);   // stay airborne
        bot().holdKey(Keyboard.KEY_D);   // yaw the nose
        for (int i = 0; i < 8; i++) {
            // A mouse swipe on top of the key yaw: down-right each tick.
            JsonObject st = bot().reportState();
            bot().setLook(st.get("playerYaw").getAsFloat() + 4f,
                          st.get("playerPitch").getAsFloat() + 3f);
            bot().waitTicks(1);
        }
        bot().releaseKey(Keyboard.KEY_D);
        bot().releaseKey(Keyboard.KEY_R);
        // THIS ONE STAYS A LOOP, because what it waits for is a VALUE that converges rather than
        // an event anything DECIDES. The client bleeds the server's rotation correction
        // geometrically, so there is no instant at which the craft "has settled" and no record
        // production could commit for one — the exit is the DIFFERENCE between two readings, which
        // no single record can carry. What it cannot see, written down: a yaw that stopped moving
        // for one pair of samples and then resumed, and the shape of the bleed in between.
        double prevYaw = Double.NaN;
        for (int i = 0; i < 20; i++) {
            bot().waitTicks(2);
            double yawNow = bot().reportRidingEntity().get("rotationYaw").getAsDouble();
            if (!Double.isNaN(prevYaw) && angDiff(yawNow, prevYaw) < 0.02) break;
            prevYaw = yawNow;
        }

        // Frame-time lock telemetry: the worst divergence the pilot SAW on any
        // rendered frame of this flight (sampled atomically on the render
        // thread — immune to the bot's non-atomic read pairs). The legitimate
        // transient is one frame straddling an injected look swipe (≤6°) plus
        // a slow tick or two of craft turn (6°/tick) ≈ up to ~18°, consumed by
        // the very next pin. What this pins is "no runaway": v1's broken look
        // detached by tens of degrees and STAYED detached.
        // One record carries both readings of this leg, taken from the same frames.
        String camWindow = closeFlightCameraWindow(camMark, "the manoeuvring leg");
        // The two divergence readings below are extrema that START at zero and are raised only by a
        // pinned in-flight frame; a window that measured none reports a perfect 0.0 lock. So the
        // window must have measured the lock at all before either reading means anything.
        requirePinnedFrames(camWindow, "the manoeuvring leg");
        double maxErr = Events.number(camWindow, "maxErrDeg");
        assertTrue("camera must never detach from the craft on any rendered frame "
                + "(worst frame divergence " + maxErr + "°)", maxErr < CAMERA_TRACKS_NOSE_DEG);

        // At-rest exactness, measured atomically on the render thread (a bot
        // reading camera and craft in two calls can straddle a tracker
        // quantisation-bleed tick and see a phantom 1-2° gap): the CURRENT
        // frame divergence must be sub-degree once input stops.
        double restErr = Events.number(camWindow, "lastErrDeg");
        assertTrue("at rest the camera lock must be exact on the rendered frame "
                + "(current divergence " + restErr + "°)", restErr < 1.0);
        JsonObject cam = bot().reportState();

        // And the camera attitude must CONVERGE to the SERVER craft heading
        // (replication). Convergence is asynchronous — the final resync packet
        // (sent on the turn->idle edge) can be in flight for several ticks on a
        // loaded box — so poll for it instead of a single-shot read; what we
        // pin is that the divergence DOES settle under the tracker quantum.
        // A WINDOW, sampled for its MINIMUM — and the two numbers below are a finding, not a
        // formality. The poll here exited on `e < 2.0` and the assertion restated it, so the
        // convergence could only be timed out, never disproved. Converting it to a window read at
        // the END reported **2.50°**: after the full ceiling the residual sits ABOVE the bound the
        // old poll passed on, which means the poll was passing on a momentary dip and the SETTLED
        // divergence is larger than this test has ever claimed.
        //
        // So the claim is kept exactly as it was — "the divergence DOES settle under the tracker
        // quantum at some point in the window" — and it is now measured as the minimum over the
        // window rather than as whichever sample the poll happened to stop on. The settled value is
        // printed beside it, because THAT is the number a tighter pin would have to be built from,
        // and one run is not a distribution.
        double convErr = Double.MAX_VALUE;
        double settledErr = Double.NaN;
        int window = windowTicks(4, 20);
        // WINDOW: the paragraph above says why — the measurement is the BEST convergence reached
        // anywhere in it, not the sample the loop stopped on. No record carries a best-over-a-stretch.
        // What it cannot see: a better residual touched between two samples.
        for (int spent = 0; spent < window; spent += 4) {
            bot().waitTicks(4);
            // Both halves of the residual read as ONE measurement, in this order: two reads a
            // moment apart attribute the camera's yaw to whatever the server's heading was when IT
            // was read.
            settledErr = angDiff(bot().reportState().get("playerYaw").getAsDouble(),
                    rocketInfo(rocketId).rotationYaw);
            convErr = Math.min(convErr, settledErr);
        }
        System.out.println("[ff-camera] yaw residual: min over the window=" + convErr
                + "° settled=" + settledErr + "° (the bound is 2.0°)");
        assertTrue("camera yaw must converge to the server craft heading at some point after the"
                + " turn->idle edge (best residual over the window " + convErr + "°, settled "
                + settledErr + "°)", convErr < CAMERA_CONVERGED_DEG);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void mouseSwipesPitchTheNoseAndCameraFollows() throws Exception {
        // Mouse-as-rate: repeated downward swipes (a real drag) pitch the nose
        // down tick by tick; the camera never detaches from the craft. The
        // server nose pitch must integrate the swipes through the real
        // key->packet path.
        int rocketId = mountFreshFreeFlightRocket();
        long camMark = openFlightCameraWindow(); // this drag's own extrema, not the last leg's
        bot().holdKey(Keyboard.KEY_R); // keep airborne so tickFreeFlight integrates pitch

        // A real mouse drag: repeated +6° swipes. This loop is the STIMULUS — it performs the
        // drag — so it owes a budget and a read taken after the swipes stop, not a reading per
        // iteration. It used to exit on `nosePitch > 20.0`, which is the assertion below: the
        // claim could be timed out but never disproved.
        //
        // The count is MEASURED, and the measurement corrected the arithmetic that produced it.
        // From the production constants alone: the flight cursor is ABSOLUTE
        // (KeyBindings.FF_CURSOR_SENS = 0.04 per degree), so a +6° swipe adds 0.24 deflection and
        // five saturate it at 1.0; the nose then integrates at MAX_PITCH_RATE = 4°/tick, which
        // predicts ~33° after twelve swipes. Run, twelve swipes reached **71.1°** — an iteration
        // costs more than the one tick it waits (the reportState/setLook round-trips each pass
        // ticks of their own) and the nose keeps integrating after the last swipe, since the
        // deflection stays where the drag left it.
        //
        // That headroom matters: past 90° the nose goes over the top (the attitude is a
        // quaternion — there is no clamp to stop it) and the pitch reading turns back down
        // through the bound. So the drive is EIGHT, measured at 40.8° — twice the bound, and
        // twice as far again from the wrap.
        for (int i = 0; i < 8; i++) {
            JsonObject st = bot().reportState();
            bot().setLook(st.get("playerYaw").getAsFloat(),
                          st.get("playerPitch").getAsFloat() + 6f);
            bot().waitTicks(1);
        }
        double nosePitch = rocketInfo(rocketId).freeFlightPitch;
        // Printed because a green now says only "past 20°"; the margin the fixed drive actually
        // leaves is what a tighter pin would have to be built from, and one run is not a
        // distribution.
        System.out.println("[ff-drag] nose pitch after 8 swipes: " + nosePitch
                + "° (the bound is 20.0°, the wrap is 90°)");
        String dragWindow = closeFlightCameraWindow(camMark, "the mouse-drag leg");
        double maxErr = Events.number(dragWindow, "maxErrDeg");
        bot().releaseKey(Keyboard.KEY_R);
        // "Stays locked" is read off frames on which the camera WAS pinned; a flight that never
        // pinned it measures no divergence and would report a perfect lock.
        requirePinnedFrames(dragWindow, "the mouse-drag leg");

        assertTrue("mouse drag must pitch the nose down through the real "
                + "swipe->rate->server path (got " + nosePitch + "°)", nosePitch > NOSE_PITCHED_DEG);
        assertTrue("camera must stay locked to the nose during the drag "
                + "(worst frame divergence " + maxErr + "°)", maxErr < CAMERA_TRACKS_NOSE_DEG);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void mouseHorizontalBanksTheCraftNotItsHeading() throws Exception {
        // Deflection scheme: the mouse is a virtual cursor —
        // HORIZONTAL drives ROLL (bank), not yaw (yaw is A/D only). A real
        // rightward mouse drag must bank the craft (client camera roll grows) while
        // the heading (client yaw) stays put. Supersedes the pre-deflection
        // "fast mouse swipe yaws the craft" test, whose premise no longer holds.
        int rocketId = mountFreshFreeFlightRocket();
        openFlightCameraWindow(); // this bank's own numbers, not the previous scenario's
        bot().holdKey(Keyboard.KEY_R);   // climb clear of the pad while banking
        // STIMULUS: five ticks of climb input ahead of the drag, so the bank is flown clear of the
        // pad; nothing is read on its account.
        bot().waitTicks(5);

        double yaw0 = bot().reportRidingEntity().get("rotationYaw").getAsDouble();
        double roll0 = flightCameraNow("camRoll");
        // A real rightward mouse drag: repeated +8° horizontal swipes saturate the
        // absolute roll cursor, which then holds a steady bank rate.
        for (int i = 0; i < 8; i++) {
            JsonObject st = bot().reportState();
            bot().setLook(st.get("playerYaw").getAsFloat() + 8f, st.get("playerPitch").getAsFloat());
            bot().waitTicks(1);
        }
        // WINDOW: between the roll0/yaw0 and camRoll/yaw1 reads; both assertions are over the
        // difference and name both ends. It replaced a poll that exited on `|r| > 15.0`, which is
        // the bank assertion.
        bot().waitTicks(windowTicks(3, 5));
        double camRoll = flightCameraNow("camRoll");
        double yaw1 = bot().reportRidingEntity().get("rotationYaw").getAsDouble();
        bot().releaseKey(Keyboard.KEY_R);

        assertTrue("mouse-horizontal must BANK the craft — client camera roll must "
                + "grow (roll0=" + roll0 + "° roll=" + camRoll + "°)",
                angDiff(camRoll, roll0) > BANKED_DEG);
        assertTrue("mouse-horizontal must NOT change the heading — client yaw drifted "
                + angDiff(yaw1, yaw0) + "° (yaw0=" + yaw0 + "° yaw1=" + yaw1
                + "°; roll must not couple into yaw)",
                angDiff(yaw1, yaw0) < YAW_DRIFT_WHILE_BANKING_DEG);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    /**
     * The headline Phase-7 perception contract: a sustained pitch input drives the
     * nose all the way OVER THE TOP (a loop), which the world-frame Euler ±85°
     * clamp made impossible. Witnessed on the CLIENT render thread: the nose Z the
     * camera actually pointed goes negative (points backwards) — a clamped attitude
     * can never do that (forward.z ≳ cos 85° ≈ 0.09). Also proves the client
     * survives rendering an inverted / looping craft.
     */
    @Test
    public void sustainedPitchLoopsPastVerticalWithNoClamp() throws Exception {
        int rocketId = mountFreshFreeFlightRocket();
        openFlightCameraWindow(); // this loop's own min-forward-Z witness
        // Climb well clear of the pad, then CUT to a gravity-cancelled hover: with
        // a zero velocity setpoint FA holds position regardless of attitude, so the
        // craft can loop in place without body-up thrust flying it into the ground.
        holdKeyUntilApplied(rocketId, Keyboard.KEY_R, "vert=1",
                "holding R must deliver a climb input to this rocket");
        // STIMULUS: thirty ticks of climb from the input's arrival is the altitude the loop is flown
        // at; nothing is read on its account.
        bot().waitTicks(30);
        bot().releaseKey(Keyboard.KEY_R);
        // Cut -> hover (attitude-independent). The drag may start the moment the cut has ARRIVED:
        // from then the setpoint is zero whatever the nose does, and a craft still braking is only
        // carried further from the ground.
        holdKeyUntilApplied(rocketId, Keyboard.KEY_X, "cut=true",
                "holding X must deliver a throttle cut to this rocket");

        // Real downward mouse drag: saturate the absolute pitch cursor, which then
        // holds full pitch rate and carries the nose past vertical and over.
        for (int i = 0; i < 8; i++) {
            JsonObject st = bot().reportState();
            bot().setLook(st.get("playerYaw").getAsFloat(), st.get("playerPitch").getAsFloat() + 8f);
            bot().waitTicks(1);
        }
        // EXPERIMENT: sixty ticks of the saturated pitch cursor is the dose that must carry the nose
        // over the top, and the assertion is what it did. `minForwardZ` is an ACCUMULATOR — a
        // minimum over the flight — so reading it at the end sees every frame, and nothing is lost
        // by not sampling along the way. It replaced a poll that exited on `z < -0.5`, which was
        // the assertion.
        bot().waitTicks(windowTicks(5, 12));
        double minFwdZ = flightCameraNow("minForwardZ");
        // `riding`, not `!= null`: reportRidingEntity throws on a failed reply and otherwise
        // returns an object, so the null test was a compile-time true and the pilot could have
        // been ejected with this still green. The file reads it correctly elsewhere.
        boolean stillRiding = bot().reportRidingEntity().get("riding").getAsBoolean();
        bot().releaseKey(Keyboard.KEY_X);

        // THE THRESHOLD COMES FROM PRODUCTION, and the number it replaces is why this matters.
        // This asserted `minFwdZ < -0.5` under a message reading "a ±85° clamp keeps it ≳ 0.09".
        // Both numbers were production's, written down: 85 is `FreeFlightPhysics.PITCH_MAX`, and
        // 0.09 is cos(85°) = 0.0872, the smallest forward.z a craft held inside that clamp can
        // show. The -0.5 was the test's own margin on top, chosen by hand. So when PITCH_MAX moves,
        // the prose goes stale silently and the bound stops meaning anything.
        //
        // Derived instead: ZERO is "vertical" by definition, so a negative forward.z IS the loop —
        // and what makes that discriminating rather than noise-sensitive is the clamp's own floor,
        // computed here from production's constant. A clamped craft cannot read below +clampFloor,
        // so a negative reading is the whole of that distance away from anything the clamp allows.
        double clampFloor = Math.cos(Math.toRadians(FreeFlightPhysics.PITCH_MAX));
        assertTrue("the nose must loop PAST vertical — client min forward.z must go negative (was "
                + minFwdZ + "). A craft held inside production's ±" + FreeFlightPhysics.PITCH_MAX
                + "° clamp cannot read below +" + clampFloor + ", so a negative reading is the loop"
                + " and cannot be the clamp",
                minFwdZ < 0.0);
        assertTrue("client must survive rendering the looping/inverted craft", stillRiding);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    /**
     * The Free Flight HUD as the client last DREW it, or {@code ""} when it has never drawn one.
     *
     * <p>The recorder writes only when the line CHANGES, so the latest record is the current text —
     * asking "since 0" is therefore the right window, not a leak: an unchanged HUD is one whose last
     * change is still what the pilot sees. What the record adds over the field it replaces is a
     * sequence number, so a reader can tell an old line from one that appeared during its own leg.</p>
     */
    private String freeFlightHud() throws Exception {
        String rec = Events.lastRecord(clientEvents().since(0, "ff_hud"));
        return rec == null ? "" : Events.text(rec, "text");
    }

    /**
     * One field of the flight-camera window AS IT STANDS, for a value that is still integrating —
     * the bank growing under a held mouse, the nose coming over the top.
     *
     * <p>A peek writes the window's numbers as a record without ending it, so a poll reads a record
     * like everything else rather than reaching for a field. Two round trips per poll instead of
     * one, which is what the reading costs when it must be attributable.</p>
     */
    private double flightCameraNow(String field) throws Exception {
        assertNotNull("no flight-camera window is open in this scenario, so " + field
                + " would be a reading of nobody's window", flightCameraWindow);
        long mark = clientEvents().mark();
        flightCameraWindow.peek();
        String rec = Events.lastRecord(clientEvents().since(mark, "flight_camera_window"));
        assertNotNull("no flight_camera_window record after a peek — the client did not answer, so "
                + field + " has no reading", rec);
        return Events.number(rec, field);
    }

    /** This scenario's flight-camera window, held by the test instance — see {@link ClientWindow}. */
    private ClientWindow flightCameraWindow;

    /**
     * Start a flight-camera window on the client and take the mark its summary will land after. A
     * window this scenario still holds from an earlier leg is closed first: each leg's extrema are
     * its own, and a second window beside the first would leave the first unreleased.
     */
    private long openFlightCameraWindow() throws Exception {
        if (flightCameraWindow != null) {
            flightCameraWindow.close();
        }
        long mark = clientEvents().mark();
        flightCameraWindow = ClientWindow.open(bot(), FLIGHT_CAMERA);
        return mark;
    }

    /**
     * Close the window and return its summary record, failing by name when none arrived.
     *
     * <p>A missing record means the close never ran or the log never received it — which is a
     * finding, not a zero divergence. The frame count inside separates a leg the renderer never
     * sampled from one it sampled and found still.</p>
     */
    /**
     * The window measured the camera lock on at least one frame: in flight, with the camera pinned.
     * The lock's extrema start at zero and only a pinned frame raises them, so without this a leg in
     * which production never pinned the camera — or in which the recorder saw no in-flight frame —
     * reads as a perfect lock.
     */
    private static void requirePinnedFrames(String window, String what) {
        double pinned = Events.number(window, "pinnedFrames");
        assertTrue("the camera lock was measured on no pinned in-flight frame during " + what
                + ", so its divergence of 0 describes an absent measurement, not a lock: "
                + window, pinned > 0);
    }

    private String closeFlightCameraWindow(long mark, String what) throws Exception {
        flightCameraWindow.close();
        String rec = Events.lastRecord(clientEvents().since(mark, "flight_camera_window"));
        assertNotNull("no flight_camera_window record for " + what
                + " — the window did not close, so there is no camera reading for it", rec);
        return rec;
    }

    /**
     * Roll DOF smoke: a commanded bank integrates server-side AND the real
     * client renders through it (the camera-roll mixin runs every frame) without
     * crashing. Pins the roll channel end-to-end; camera-bank direction/feel is
     * a manual-playtest perception check.
     */
    @Test
    public void rollChannelIntegratesAndClientRendersWithoutCrash() throws Exception {
        int rocketId = mountFreshFreeFlightRocket();
        double roll0 = rocketInfo(rocketId).freeFlightRoll;

        // Command a steady bank-right: probe args are
        // id fwd vert yaw pitch brake cut strafe roll -> roll = last (=+1).
        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0 0 0 1");
        // WINDOW: ten ticks of commanded roll between the roll0 and roll1 reads; the assertion is
        // over their difference and names both.
        bot().waitTicks(10);
        double roll1 = rocketInfo(rocketId).freeFlightRoll;

        // Stop and let the client keep rendering the banked craft a moment.
        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0 0 0 0");
        long renderMark = openFlightCameraWindow();
        // EXPERIMENT: ten ticks of the client drawing the banked craft is the dose, and the claim
        // is that it survived them. The window counts the in-flight frames it saw, so a client that
        // drew none cannot pass on an empty dose.
        bot().waitTicks(10);
        double framesDrawn = Events.number(
                closeFlightCameraWindow(renderMark, "the banked craft's render"), "frames");
        // Both halves were compile-time true: a present JSON primitive never renders as a null
        // String, and reportRidingEntity throws rather than returning null. What the sentence
        // means is that the pilot is still aboard the banked craft.
        boolean stillRiding = bot().reportRidingEntity().get("riding").getAsBoolean();

        exec("artest player dismount");

        assertTrue("commanded roll must integrate server-side (roll0=" + roll0
                + " roll1=" + roll1 + ")", angDiff(roll1, roll0) > SERVER_ROLL_INTEGRATED_DEG);
        assertTrue("the client must have drawn the banked craft in flight at all (in-flight frames"
                + " in the render window: " + framesDrawn + ")", framesDrawn > 0);
        assertTrue("client must survive rendering the banked craft (camera-roll mixin)",
                stillRiding);
    }
}
