package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.client.ClientBot;
import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.google.gson.JsonObject;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runners.MethodSorters;

import zmaster587.advancedRocketry.test.Events;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.Plot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Class-scoped client-harness base: ONE server JVM and ONE client JVM for every {@code @Test} in
 * the subclass, instead of one pair per method.
 *
 * <h2>Why</h2>
 *
 * <p>Measured on the maintainer's box, 2026-08-06, from the result XML:
 * {@code ModCountParityE2ETest} — whose entire body is one {@code report_mods} call — takes
 * <b>119.2 s</b>, and {@code OreScannerRightClickClientE2ETest}'s two methods take 120.1 s and
 * 101.8 s and print <b>two distinct client pids</b>. A four-scenario run on ONE shared harness
 * costs <b>73.8 s of boot plus 2.1-3.7 s per scenario</b>. Boot is 25-35x the scenario, and better
 * than 95 % of this tier's wall clock. {@code build.gradle}'s {@code forkEvery 1L} then makes the
 * whole tier's floor equal to its LONGEST class, so the 27-method {@code FreeFlightModeE2ETest}
 * pins it at ~35 min in one fork while the other seven idle.</p>
 *
 * <h2>What a subclass owes</h2>
 *
 * <ol>
 *   <li><b>{@code @FixMethodOrder(MethodSorters.NAME_ASCENDING)} on the concrete class.</b> JUnit's
 *       annotation is NOT {@code @Inherited} (checked: it carries only {@code @Retention} and
 *       {@code @Target}), so this base cannot supply it — and without it "the methods are
 *       independent" is a belief rather than a property. {@link #enforceDeterministicOrder} fails
 *       loudly rather than letting a subclass run in an undefined order.</li>
 *   <li><b>Stay inside {@link #plot()}.</b> Each scenario is handed its own 64-block patch, never
 *       recycled. A scenario that asks a GLOBAL question ({@code artest rocket list},
 *       {@code artest station list}) must narrow the answer with {@link Plot#contains}.</li>
 *   <li><b>Declare the phase</b> as it goes, through {@link #scenario()} — that is what lets a
 *       failure name the broken system without anyone opening this file.</li>
 *   <li><b>No un-restored global mutation.</b> Atmosphere density and weather are shareable only if
 *       every scenario SETS what it needs and MEASURES that the set took; a scenario that assumes a
 *       global instead belongs on the per-method {@link AbstractClientE2ETest}. ({@code vs
 *       permaload} used to be on this list and is not any more: a test server holds its ships
 *       loaded from the moment the probes register, so it is a property of the server rather than
 *       something each scenario sets — and the three whose subject IS an unloaded ship turn it off
 *       for themselves.)</li>
 *   <li><b>Declare a config, do not write one.</b> A value the server reads at START goes through
 *       {@link #seedGameDirectory}, which merges the class's keys into one file before boot; a value
 *       read at every use is flipped per scenario through {@code artest config set} and restored in
 *       the family reset. "This class writes its own {@code advancedRocketry.cfg}" stopped being a
 *       reason to leave this base on 2026-08-23 — what could not be merged was the whole-FILE write,
 *       never the settings, and the four classes that carried that justification turned out to want
 *       one key, the same number twice, and a flag already on the runtime whitelist.</li>
 * </ol>
 *
 * <p><b>Still not shareable: a server RESTART.</b> The pair is owned by this class and a scenario
 * that stops the server takes every later scenario with it — and the restart is the SUBJECT of the
 * classes that do it, so there is nothing to amortise anyway.</p>
 *
 * <h2>The reset, and why it is asserted rather than trusted</h2>
 *
 * <p>A shared client carries state across scenarios. Measured on the second scenario of a shared
 * run, ALL FOUR of these were still holding the first scenario's leavings: an open
 * {@code GuiModular}; an action-bar overlay at {@code overlayTicks=50}, still counting down; the
 * previous scenario's item in the hotbar; and the player standing on the previous plot.</p>
 *
 * <p>The chat backlog is the dangerous one, and it is why the pilot for this base class was chosen
 * to be a chat-asserting test: a scenario that proves "the player was told X" by searching the last
 * N chat lines passes on the PREVIOUS scenario's identical line, with no stimulus behind it at all.
 * {@code ItemSealDetectorPlayerMessagesE2ETest} has three methods expecting the same message.</p>
 *
 * <p>So {@link #resetBetweenScenarios} does the reset and then <b>asserts the world is clean</b>. A
 * reset nobody checks is indistinguishable from no reset.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public abstract class AbstractSharedClientE2ETest {

    private static RealDedicatedServerHarness sharedServer;
    private static RealClientHarness sharedClient;
    /** Set once the shared harness stops answering; every later scenario then fails FAST. */
    private static final AtomicBoolean HARNESS_DEAD = new AtomicBoolean(false);
    private static String firstFailure;
    /** Scenario name -> its plot. Stable within a run because the method order is pinned. */
    private static final Map<String, Plot> PLOTS = new HashMap<>();
    private static int nextPlotIndex;

    /**
     * Where this class's plot allocation STARTS, from {@code -PplotOffset=N} (default 0).
     *
     * <p>An experiment lever, and it exists because an ordinary run cannot separate two variables it
     * always changes together: <b>what ran in this world before a scenario</b>, and <b>which plot the
     * scenario's fixture stands on</b>. Running a test alone gives it plot #0 and no predecessor;
     * running it second gives it plot #1 AND a predecessor. A scenario that is green in the first and
     * red in the second therefore accuses both, and comparing the two runs answers neither.</p>
     *
     * <p>With this, a scenario can be run alone on plot #N — one variable moved, the other held.
     * Belongs in no gate and no default: it changes where fixtures stand, which is the one thing the
     * allocator exists to decide.</p>
     */
    private static final int PLOT_OFFSET = Integer.getInteger("artest.plot.offset", 0);
    /** Which concrete class the live pair was booted for; null when nothing is up. */
    private static Class<?> bootedFor;

    /** The client's start-time framebuffer switch, read by the harness as it launches the child. */
    private static final String CLIENT_FBO_PROPERTY = "forge.test.client.fbo";
    /**
     * What {@link #CLIENT_FBO_PROPERTY} held before this class claimed it, and whether it claimed it
     * at all. CLEAR MEANS RESTORE: a null here is a real state (the property was unset), so the flag
     * is what says "we changed it", never the value.
     */
    private static String displacedFboProperty;
    private static boolean fboPropertyClaimed;

    /**
     * Never cleared in an {@code @After}. JUnit runs {@code @After} BEFORE
     * {@link TestWatcher#failed}, so nulling it there destroys the journal the watcher exists to
     * print — measured on this class's first run: every red reported
     * "never started — failed before or inside the shared setup" and no journal at all, for six
     * failures that had in fact run their whole arrangement. A fresh instance is assigned per
     * scenario in {@link #resetBetweenScenarios} instead.
     */
    private Scenario scenario;

    @Rule
    public final TestName testName = new TestName();

    /**
     * Prints the taxonomy line, the journal and the scenario's own state bundle on a failure, and
     * decides whether the harness is still alive.
     */
    @Rule
    public final TestRule verdict = new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
            // "Is the harness still there?" is the difference between one broken contract and a
            // whole group reporting the same corpse — so it is an INPUT to the verdict, not an
            // afterthought. Ask it of the CLIENT, which is the half that dies.
            boolean groupAlreadyDown = HARNESS_DEAD.get();
            // Do not ping a corpse we already buried — but then do not REPORT a liveness we never
            // measured either. Printing "harnessAlive=true" for a scenario aborted BECAUSE the
            // harness is dead is a field that states the opposite of the truth, and this class
            // exists so a reader can trust the line without opening the source.
            boolean alive = groupAlreadyDown || pingClient();
            String aliveReport = groupAlreadyDown
                    ? "not-probed (group already down)" : String.valueOf(alive);
            Scenario.Phase effective =
                    Scenario.classify(e, scenario, alive, groupAlreadyDown);
            if (!alive) {
                HARNESS_DEAD.set(true);
            }
            if (firstFailure == null) {
                firstFailure = description.getMethodName();
            }

            StringBuilder out = new StringBuilder();
            out.append('\n');
            out.append(scenario == null
                    ? "E2E verdict=" + effective + " scenario=" + description.getMethodName()
                      + " (never started — failed before or inside the shared setup)"
                    : scenario.verdictLine(effective));
            out.append("\n  harnessAlive=").append(aliveReport);
            if (firstFailure != null && !firstFailure.equals(description.getMethodName())) {
                out.append(" firstFailureInThisGroup=").append(firstFailure);
            }
            out.append('\n');
            if (scenario != null) {
                out.append(scenario.renderJournal());
                if (alive) {
                    out.append(renderStateBundle(scenario));
                }
            }
            System.out.println(out);
        }
    };

    // ── lifecycle ────────────────────────────────────────────────────────────

    /**
     * The game directory this class needs BEFORE its server boots — declared as keys, not as a file.
     *
     * <p>Default: nothing, and a class that declares nothing boots in a bare temp directory exactly
     * as this base always has. Override it when a scenario's premise is a config value or a planet
     * catalogue that must exist at server start:</p>
     *
     * <pre>
     * protected void seedGameDirectory(GameDirSeed seed) {
     *     seed.config("performance", "I:spaceCellPoolSize", 1, getClass());
     * }
     * </pre>
     *
     * <p><b>Only for what the server reads at START.</b> A value read at every use — the time-skip
     * policy, the terraform flags, anything on {@code artest config set}'s whitelist — is flipped
     * PER SCENARIO through that verb instead, and restored in the family reset. Seeding such a key
     * pins one side of it for the whole class and quietly makes the other side untestable there.</p>
     */
    protected void seedGameDirectory(GameDirSeed seed) throws Exception {
        // declared by subclasses that need one; empty is the common case
    }

    /**
     * Whether this class's client must be STARTED with the framebuffer object, because it measures
     * what the client drew.
     *
     * <p>Default false, which is the harness's own default and the render path every other class
     * runs. Override it in a class that captures WORLD pixels.</p>
     *
     * <p><b>Turning the FBO on at runtime is not the same thing and does not work.</b> The harness
     * measured it on 2026-07-29 and says so in {@code ClientBot.setFramebuffer}: a framebuffer
     * recreated mid-session receives the HUD pass but not the world pass, so a capture comes back as
     * the framebuffer's own clear colour — opaque WHITE — with the HUD drawn over it. That looks
     * exactly like "the world rendered nothing", and it cost this project a session in July and six
     * red tier runs since, under a bug entry the maintainer could never reproduce in play because
     * there was nothing to reproduce.</p>
     *
     * <p>It is a per-CLASS client option and nothing else needs to know: the harness reads the system
     * property when it launches the child, and this base sets it around the boot and puts the
     * previous value back afterwards. It became declarable when the boot became lazy — a
     * {@code @BeforeClass} could not have asked the subclass.</p>
     */
    protected boolean clientNeedsFramebuffer() {
        return false;
    }

    /**
     * Boot the class's pair once, on its FIRST scenario.
     *
     * <p>It is not a {@code @BeforeClass} because a static method cannot ask the subclass anything —
     * and what it has to ask is {@link #seedGameDirectory}, which is the whole point: "this class
     * writes its own config" stopped being a reason to leave the shared harness on 2026-08-23, and
     * the only thing that had made it one was that a static boot could not see the declaration.</p>
     *
     * <p>The Assume guards moved here with it, so a run without the harness enabled skips each
     * scenario instead of the class. Same runs skipped, one line each instead of one for the class.</p>
     */
    private void ensureHarnessBooted() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -D"
                        + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue(
                "Client harness disabled — set -D"
                        + AbstractClientE2ETest.PROP_CLIENT_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractClientE2ETest.PROP_CLIENT_ENABLED, "false")));

        if (bootedFor == getClass() && sharedServer != null) {
            return;
        }
        // A previous class's pair in the same JVM (the tier runs forkEvery=1, so this is a
        // belt-and-braces path rather than the usual one): close it before starting another, or the
        // second boot contends with a live server for ports and disk.
        if (sharedServer != null || sharedClient != null) {
            closeSharedHarness();
        }

        HARNESS_DEAD.set(false);
        firstFailure = null;
        PLOTS.clear();
        nextPlotIndex = PLOT_OFFSET;

        GameDirSeed seed = new GameDirSeed();
        seedGameDirectory(seed);

        long startedNanos = System.nanoTime();
        String seeded = "";
        if (seed.isEmpty()) {
            sharedServer = RealDedicatedServerHarness.start();
        } else {
            java.nio.file.Path root =
                    java.nio.file.Files.createTempDirectory("forge-shared-client-");
            seeded = seed.writeInto(root);
            sharedServer = RealDedicatedServerHarness.startWith(root, /*cleanupOnClose=*/true);
        }
        // The client's start-time options come from system properties the harness reads as it
        // launches the child, so a class that needs one sets it HERE, around the boot, and the value
        // it displaced goes back in closeSharedHarness. Set, not assumed: a scenario that measures
        // pixels asserts the option took (see ClientBot.setFramebuffer's own `previous`).
        if (clientNeedsFramebuffer()) {
            displacedFboProperty = System.getProperty(CLIENT_FBO_PROPERTY);
            fboPropertyClaimed = true;
            System.setProperty(CLIENT_FBO_PROPERTY, "true");
        }
        try {
            sharedClient = RealClientHarness.start(sharedServer);
        } catch (Exception startupFailure) {
            try {
                sharedServer.close();
            } catch (Exception cleanup) {
                startupFailure.addSuppressed(cleanup);
            }
            sharedServer = null;
            throw startupFailure;
        }
        bootedFor = getClass();
        // The number this whole base class exists to amortise — print it so a run can be audited
        // against the claim rather than against a memory of it. The seed is printed with it: a
        // scenario whose premise is a config value must be able to show that value was there.
        System.out.println("[shared-harness] boot ms="
                + (System.nanoTime() - startedNanos) / 1_000_000L
                + " — one server JVM + one client JVM for " + getClass().getSimpleName()
                + (seeded.isEmpty() ? " (no seeded game directory)" : " seeded:" + seeded));
    }

    @AfterClass
    public static void closeSharedHarness() throws Exception {
        Exception deferred = null;
        if (sharedClient != null) {
            try {
                sharedClient.close();
            } catch (Exception e) {
                deferred = e;
            }
            sharedClient = null;
        }
        if (sharedServer != null) {
            try {
                sharedServer.close();
            } catch (Exception e) {
                if (deferred == null) deferred = e;
                else deferred.addSuppressed(e);
            }
            sharedServer = null;
        }
        bootedFor = null;
        if (fboPropertyClaimed) {
            if (displacedFboProperty == null) {
                System.clearProperty(CLIENT_FBO_PROPERTY);
            } else {
                System.setProperty(CLIENT_FBO_PROPERTY, displacedFboProperty);
            }
            displacedFboProperty = null;
            fboPropertyClaimed = false;
        }
        if (deferred != null) throw deferred;
    }

    /**
     * The ONE {@code @Before}, calling the three steps in an order this class controls.
     *
     * <p>They were three separate {@code @Before} methods until it was noticed that JUnit 4 does not
     * define the order of {@code @Before} methods declared in the SAME class. That is not a style
     * point here: the fast-fail check must run BEFORE the reset, or a class whose client has died
     * pays the reset's full round-trip — on a HUNG client, the command channel's own multi-minute
     * timeout — once per remaining scenario, which is exactly the cost the fast-fail exists to
     * avoid. An undefined order made the guarantee a coin flip.
     */
    @Before
    public final void prepareScenario() throws Exception {
        enforceDeterministicOrder();
        ensureHarnessBooted();
        failFastWhenTheGroupIsAlreadyDown();
        resetBetweenScenarios();
    }

    private void enforceDeterministicOrder() {
        FixMethodOrder order = getClass().getAnnotation(FixMethodOrder.class);
        assertTrue(getClass().getName() + " extends " + AbstractSharedClientE2ETest.class.getSimpleName()
                        + " but does not carry @FixMethodOrder(MethodSorters.NAME_ASCENDING)."
                        + " The annotation is NOT @Inherited, so the base class cannot supply it, and"
                        + " without it JUnit does not guarantee method order — which makes every"
                        + " independence claim in a shared-harness class unverifiable.",
                order != null && order.value() == MethodSorters.NAME_ASCENDING);
    }

    private void failFastWhenTheGroupIsAlreadyDown() {
        if (HARNESS_DEAD.get()) {
            throw new AssertionError("E2E verdict=CASCADE scenario=" + testName.getMethodName()
                    + " — the shared harness died earlier in this class (first failure: "
                    + firstFailure + "). This scenario never ran; read that one instead.");
        }
    }

    private void resetBetweenScenarios() throws Exception {
        final Plot.Lane lane = lane();
        Plot plot = PLOTS.computeIfAbsent(testName.getMethodName(),
                name -> Plot.forScenario(nextPlotIndex++, name, 0, lane));
        scenario = new Scenario(testName.getMethodName(), subsystem(), plot);

        // SERVER side first: its commands echo harness markers into the chat the client reset is
        // about to clear. Doing it the other way round leaves the markers behind and the clean
        // assertion below fails for a reason that has nothing to do with the previous scenario.
        serverClient().execute("clear @a");
        // The harness server runs gamemode=1 (RealDedicatedServerHarness writes it into
        // server.properties), and a scenario that needs survival — a vacuum-damage or a
        // stack-consumption one — drops the player into it. Left behind, the NEXT scenario runs
        // under a different mode than the one its green runs were taken on. Restoring the
        // documented default is a no-op for every scenario that never touches it.
        serverClient().execute("gamemode creative @a");
        // Same argument for health: a damage scenario leaves the player short, and the next one's
        // "the player started at full health" precondition is then false through no fault of its
        // own. Both are un-restored global mutations of the SHARED subject, which is the one thing
        // this base class exists to stop.
        serverClient().execute("artest player set-health 20");
        // The pilot-input counters, on BOTH sides, because they are two copies of one class in two
        // processes and each half counts what its own side saw. They are cumulative for the life of
        // the JVM and are printed as ABSOLUTES into twenty-one failure messages across six classes,
        // so without this a red in the fourteenth scenario of a class reports the totals of all
        // fourteen — and since nothing ASSERTS on them, that has never shown up as a red. A leaking
        // diagnostic damages only the diagnosis, which is why it survives so long.
        //
        // The reset method existed and had no caller at all. That is the shape to recognise: the
        // price for keeping a single-writer diagnostic static is the leak stated in its javadoc AND
        // a reset owned by somebody, and half of it had been paid. This is the owner.
        serverClient().execute("artest diag reset");
        bot().invokeStaticInt("zmaster587.advancedRocketry.command.test.SeatDiag", "reset");
        // A family of scenarios can carry a channel this base knows nothing about — a seat the
        // player is still riding, a subsystem flag it switched on. It runs HERE, before the
        // teleport, because a player still bound to a vehicle is not moved by /tp: the plot
        // assertion below would then fail naming coordinates, which is the symptom and not the
        // cause. Everything the hook does is asserted by the hook itself.
        resetFamilyStateBeforeTeleport();
        // DIMENSION, and it must come before the teleport: vanilla /tp moves the player WITHIN the
        // world he is in, so a scenario left behind in the space dim or on a planet would be placed
        // at the right X/Z in the WRONG world — and the plot assertion below, which reads X and Z,
        // would happily agree. The transfer is conditional because it is not free: a scenario that
        // never left dim 0 must not pay a dimension change and a chunk re-send every time.
        //
        // ASKED OF THE SERVER, not of the client. The client's own weather report is where this read
        // used to come from, and it is a channel that LAGS: right after a scenario that crossed a
        // dimension the client can still name the world it left, the transfer is then skipped as
        // unnecessary, and the teleport below places the body at the plot's X/Z inside the world it
        // was actually in. Measured 2026-08-23 in a full tier run: a scenario opened with its body
        // teleported to y=151 in a SPACE CELL, where there is no ground — it fell, the substrate's
        // own entity-drag then took it, and the plot verdict reported it at x=3.0e7, thirty million
        // blocks out. The event log named every writer and the teleport itself was innocent.
        //
        // The server cannot be stale about which world it is ticking a player in.
        int serverDim = playerDimOnTheServer(plot.dim);
        if (serverDim != plot.dim) {
            // The CLIENT's far side of the transfer, marked one statement before the command that
            // causes it: the server tears the old world down and builds a new one over a round
            // trip nobody here can bound, and `client_dimension_changed` is recorded where the
            // client finishes doing exactly that.
            long transferMark = clientEvents().mark();
            serverClient().execute("artest tp " + plot.dim);
            // NO WAIT ON THE SERVER HALF, and that is a statement about the code rather than a
            // shortened budget: `PlayerList.transferPlayerToDimension` assigns `player.dimension`
            // in its first three lines and runs to the end on the server thread, and the probe
            // answers only after it returns. So the reply IS the receipt, and this read — a second
            // command, ordered behind the first on that same thread — cannot see the old world.
            // The twenty ticks that used to sit here were not buying the transfer; they were a
            // disguised assertion that twenty ticks is enough for one, which is a claim about the
            // box. Measured in vanilla source, `build/rfg/minecraft-src/…/PlayerList.java:650`.
            int afterTransfer = playerDimOnTheServer(plot.dim);
            assertEquals("the between-scenario transfer must actually move the player's world, or"
                    + " the teleport that follows puts him at the right coordinates in the wrong"
                    + " one; the server still ticks him in", plot.dim, afterTransfer);
            // And the client must ARRIVE, because the scenario about to run renders there. The
            // rendered-dim assertion at the end of this method reads that world once; this is what
            // makes the read honest, where before it was backed by whatever the three settles in
            // between happened to add up to.
            clientEvents().awaitMatching(transferMark, "client_dimension_changed",
                    reply -> Events.countRecords(reply, "\"dim\":" + plot.dim + ",") > 0,
                    "naming dim " + plot.dim,
                    "the client must follow the between-scenario transfer into the plot's world;"
                            + " a scenario that starts rendering the world it LEFT measures the"
                            + " previous one's surroundings", DIM_LINK_BUDGET_TICKS);
        }
        // Mark the event log HERE, one statement before the teleport, so that a plot miss can ask
        // the one question the diagnostic below could never answer: WHO wrote this body's position.
        // Everything else it asks names CANDIDATES (a ship near the plot, a ship near the body, a
        // deck capture, the client's resolver); `pos_jump` carries the writer's own caller trail.
        // Defensive on purpose - this runs before EVERY scenario, and a base class must not fail a
        // whole class because a recorder was unavailable. An unusable mark is REMEMBERED, not
        // thrown, so an empty log later reads as "the recorder was off" rather than as a finding.
        markThePositionRecorder();
        // The CLIENT's mark for the same write. A teleport's far side is the client APPLYING the
        // server's position packet, and the harness records that as `client_pos_look_applied` with
        // the absolute coordinates the client ends up holding — so the plot check below reads a
        // body that has demonstrably been placed, rather than one that has merely had long enough.
        long placedMark = clientEvents().mark();
        serverClient().execute("tp @a " + (plot.centerX() + 0.5) + " " + (Plot.DEFAULT_Y + 1)
                + " " + (plot.centerZ() + 0.5) + " 0 0");
        // Matched on WHERE, not merely on "a teleport happened": the packet is resent on every
        // movement rejection (the harness's own note on this seam says so), so a record alone would
        // close this wait on a rubber-band from the world he is leaving. The predicate is the same
        // region the plot check below uses, asked of the coordinates the client actually applied.
        clientEvents().awaitMatching(placedMark, "client_pos_look_applied",
                reply -> appliedInsidePlot(reply, plot),
                "placing the client inside " + plot,
                "the scenario's opening teleport must REACH the client — everything this method"
                        + " asserts afterwards is about a body at the plot, and a body still in"
                        + " flight fails those assertions in the previous scenario's name",
                PLACEMENT_LINK_BUDGET_TICKS);

        // Health is restored HERE: after the teleport, and with the settle wait below still between
        // it and the client reset. Both halves of that placement were paid for in a gate.
        //
        // AFTER THE TELEPORT, because the head's `set-health 20` heals the player where the PREVIOUS
        // scenario left him and he is then carried through a dimension change and a teleport before
        // anyone looks, so anything that hurts him on the way out silently undoes it. Measured
        // 2026-09-06: the first FULL-suite gate (186 tests, where each fork's neighbours differ from
        // the *VS* subset's) failed the health gate at 18.5.
        //
        // BEFORE THE RESET, and the ordering is kept even though the reason that forced it is gone.
        // `serverClient().execute` used to complete each command with a sentinel BROADCAST into the
        // client's chat, arriving a tick or two after the command returned: issued immediately
        // before the reset, these two left one marker behind and every scenario in the tier failed
        // its own backlog-is-empty guard. The server answers over its own control socket now and the
        // harness refuses to start without one, so no such line exists.
        long hurtMark = events().mark();
        // The CLIENT's mark for the same write, taken here because the packet it produces is what
        // the gate below waits for. It survives `resetClientState` — that resets the screen and the
        // chat, both client-owned display state, and does not touch the event log.
        long healthMark = clientEvents().mark();
        serverClient().execute("artest player set-health 20");
        // NO PACING BETWEEN THE WRITE AND THE RESET. The heal is gated below by a LINK on
        // `client_health_updated` since a mark taken BEFORE the write, so a packet still in flight
        // is what that wait is for; ten ticks here only decided whether the gate's cheap branch or
        // its link branch ran, and paid for that decision in every scenario of the tier.
        JsonObject cleared = bot().resetClientState();
        // NOR AFTER THE RESET, and again because of what the code does rather than to save time:
        // `reset_client_state` runs its whole body inside `runOnClientThread` — closes the screen,
        // clears the chat and the overlay, releases the keys — and answers only afterwards, so this
        // reply is the receipt for all four. The three assertions below can therefore be read as
        // assertions about the RESET. Two ticks of waiting could not make them truer; a late chat
        // line arriving inside that window could make them falser, which is the wrong direction for
        // a wait to be able to move a verdict.

        // Assert the reset, do not trust it. This is the shared harness's own contract, and it is
        // the assertion the spike that produced this class failed on before any of it existed.
        JsonObject state = bot().reportState();
        JsonObject chat = bot().reportChat(20);
        String screen = state.has("screen") ? state.get("screen").getAsString() : "";
        int overlayTicks = chat.has("overlayTicks") ? chat.get("overlayTicks").getAsInt() : -1;
        int chatLines = chat.has("count") ? chat.get("count").getAsInt() : -1;
        // IS THERE A CLIENT AT ALL — asked before anything is asserted ABOUT one, and it is a
        // different question from all three below. Every other read in this method is guarded; these
        // two were not, so a reportState() carrying no player — exactly what a crashed or
        // disconnected client answers — died here on a bare NullPointerException with no message.
        // That is the worst place in the file to lose the diagnosis: the assertions immediately
        // below exist to name what the previous scenario left behind, and none of them was ever
        // reached, so ONE failing scenario presented as N indistinguishable NPEs and reading it
        // cost a full control matrix to discover that all but the first were cascade.
        assertTrue("the client reports NO PLAYER, so it is GONE rather than dirty — a previous"
                + " scenario took it down, and this scenario plus every one after it is downstream"
                + " of that rather than failing on its own subject. Look at the FIRST red in this"
                + " class, not at this one. reportState()=" + state
                + ", resetClientState()=" + cleared,
                state != null && state.has("playerX") && state.has("playerZ"));
        double px = state.get("playerX").getAsDouble();
        double pz = state.get("playerZ").getAsDouble();

        assertEquals("a scenario must start with no screen open; the previous one left "
                + cleared + " behind", "", screen);
        assertEquals("a scenario must start with no action-bar overlay counting down"
                + " (the overlay STRING lingers after expiry, so the TICKS are the real gate);"
                + " reset reported " + cleared, 0, overlayTicks);
        assertEquals("a scenario must start with an empty chat backlog, or an assertion that"
                + " searches the last N lines can pass on a previous scenario's identical message;"
                + " reset reported " + cleared, 0, chatLines);
        if (!plot.contains(px, pz)) {
            // READ ONCE, AND IT IS THE VERDICT. The wait that used to live here was the recovery for
            // a read that could get in front of the teleport's round trip — and that race is gone:
            // the link above does not return until the client has APPLIED a position inside this
            // plot, so a body outside it now has exactly one meaning. Something moved him after he
            // was placed, and that is the interesting case, not the tolerable one.
            //
            // So the six-sample trail below is DIAGNOSIS and no longer a second chance. Keeping it
            // as one would re-introduce the defect in its most convincing form: a body that wanders
            // back inside the plot during the poll would pass, and the failure it hid is a scenario
            // running on a body somebody else owns.
            //
            // Measured 2026-08-12, before the link existed, four scenarios of one class in one run:
            // THREE reached their plot while being watched (early reads and nothing more) and one
            // never arrived at all, with its body below Y=-800 and falling. One message had been
            // reporting both; the link separates them at the source.
            String settle = diagnoseMissedPlot(plot);
            org.junit.Assert.fail("a scenario must start inside its own plot " + plot
                    + "; the client APPLIED a placement there and then reported the player at "
                    + px + "," + pz + " — so this body was moved after it was placed"
                    + settle + " resetCleared=" + cleared);
        }

        // Asserted on the CLIENT's own view, and polled rather than read once: the set-health above
        // (issued before the client reset, so its harness marker is cleared with everything else) is
        // a server write and the client learns it on the next update packet.
        double health = state.has("health") ? state.get("health").getAsDouble() : -1.0;
        if (health < FULL_HEALTH_BAR) {
            // THE LINK, and it is CONDITIONAL for a reason worth stating: a server write that
            // changes nothing sends nothing, so a client already at full health is never told
            // anything and an unconditional wait would burn its budget on every healthy scenario —
            // which is every scenario that did not hurt anybody. The read above is what separates
            // the two cases; only a client that is still short waits for the packet that heals it.
            //
            // What replaced the poll is the packet ITSELF: `client_health_updated` is recorded where
            // the client applies `SPacketUpdateHealth`, so this waits for the server's write
            // ARRIVING rather than for a sampled field to look right. The record carries what the
            // server SENT, which a locally predicted value cannot be mistaken for.
            clientEvents().awaitMatching(healthMark, "client_health_updated",
                    reply -> anyHealthAtLeast(reply, FULL_HEALTH_BAR),
                    "carrying health >= " + FULL_HEALTH_BAR,
                    "the reset heals the player on the server, and the client must be TOLD: a"
                            + " scenario that starts short of full health measures the previous"
                            + " one's leftovers", HEALTH_LINK_BUDGET_TICKS);
            // Re-read for the message below: the record says the packet arrived, this says what the
            // client holds now, and a disagreement between them is worth seeing in the failure text.
            JsonObject healed = bot().reportState();
            health = healed != null && healed.has("health")
                    ? healed.get("health").getAsDouble() : -1.0;
        }
        // And when it still fails, the message names WHAT hurt him rather than only how much is
        // left: `living_hurt` carries the source and the amount per hit, so a scenario left dying in
        // a vacuum, one taking fall damage off a deck and a client merely slow to render the heal
        // are three different texts instead of one number. Read after the poll so the window covers
        // it; an empty list with the health still short is itself the diagnosis — nothing hit him
        // here, so the shortfall arrived before this reset and the previous scenario owns it.
        //
        // The SERVER's own view goes in beside it, and it answers a different question: `living_hurt`
        // says what happened to him, this says WHO is wrong. "The client is stale" and "the player
        // really is hurt" need opposite fixes and are indistinguishable from the client's number
        // alone. Both are read only on the failing path, so a healthy scenario pays for neither.
        if (health < FULL_HEALTH_BAR) {
            org.junit.Assert.fail("a scenario must start at full health as the CLIENT renders it, or"
                    + " a damage-observing scenario measures the previous one's leftovers; client"
                    + " reports " + health
                    + "; server reports "
                    + String.join("\n", serverClient().execute("artest player health"))
                    + "\n  damage taken during this reset: "
                    + events().since(hurtMark, "living_hurt"));
        }

        // The world the CLIENT actually renders, asserted rather than inferred from the teleport
        // having been issued: the plot check above reads X and Z only, so without this a scenario
        // running in the wrong dimension at the right coordinates passes it.
        JsonObject renderedIn = bot().reportWeather();
        int renderedDim = renderedIn != null && renderedIn.has("dim")
                ? renderedIn.get("dim").getAsInt() : Integer.MIN_VALUE;
        assertEquals("a scenario must start in the world its plot lives in; the client renders "
                + renderedIn, plot.dim, renderedDim);

        // Held item is RECORDED, not asserted: `clear @a` is the reset, but a third-party mod in
        // the dev runtime may hand the player something on its own (TheOneProbe does), and pinning
        // an empty hand would make this base class fail for a reason that is not about sharing.
        scenario.record("plot", plot)
                .record("resetCleared", cleared)
                .record("heldAtStart", state.has("heldItem") ? state.get("heldItem").getAsString() : "?");
    }

    /**
     * The account every client harness launches under. The server keys his player data by it, and the
     * probes that answer ABOUT a player take it by name.
     */
    private static final String HARNESS_ACCOUNT = "ForgeTestClient";

    /**
     * Which world the SERVER is ticking the harness player in, or {@code fallback} when it cannot
     * say.
     *
     * <p>A fallback that equals the caller's expectation is deliberate: an unreadable answer must not
     * trigger a dimension transfer on a guess. The transfer that follows is verified, so a wrong
     * fallback fails loudly there instead of quietly moving a body nobody located.</p>
     */
    private int playerDimOnTheServer(int fallback) throws Exception {
        String reply = exec("artest oxygen player " + HARNESS_ACCOUNT);
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\"dim\":(-?\\d+)").matcher(reply);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    /**
     * Why the between-scenario teleport did not land, sampled ONLY once the check has failed.
     *
     * <p>One {@code x,z} pair cannot tell the two causes apart, and they need opposite fixes: a
     * client that never received the teleport is a round-trip that was read too early, while one
     * that received it and ended up elsewhere has a SECOND WRITER owning the body. Measured
     * 2026-08-12 — four scenarios of {@code VSShipFlightTelemetryE2ETest} red in two of three
     * identical tier runs, each printing one coordinate pair outside every plot in the lane, with
     * {@code harnessAlive=true}; nothing in the message distinguished the two, and the mode stayed
     * unattributable for a session.</p>
     *
     * <p><b>It runs after the verdict is already decided, and that is deliberate.</b> The first cut
     * of this made the plot check itself poll — which changes how many ticks every GREEN scenario
     * spends before it starts, and the class this was written for went from 8/8 to 5 reds in the
     * tier and 7/8 alone. An instrument that moves the arrangement is not an instrument. Everything
     * here is a client-side read (no server command, so no chat marker) on a path that is already
     * failing, so a passing scenario pays exactly nothing.</p>
     */
    private String diagnoseMissedPlot(Plot plot) throws Exception {
        StringBuilder trail = new StringBuilder();
        boolean arrived = false;
        JsonObject last = null;
        for (int sample = 0; sample < 6 && !arrived; sample++) {
            last = bot().reportState();
            trail.append(' ').append(describePlayerPoint(last));
            arrived = isInsidePlot(last, plot);
            if (!arrived) {
                bot().waitTicks(5);
            }
        }
        // WHO OWNS THIS BODY, asked of the server on a scenario that has already lost its verdict —
        // so the chat markers these commands echo can no longer disturb anything.
        //
        // Three questions, and the first two ask about different PLACES on purpose. A ship at the
        // PLOT would mean the teleport dropped the body into geometry and the physics mod ejected it.
        // A ship where the BODY actually is means the opposite and is far worse: the body is being
        // carried, so a capture outlived the scenario that made it and the teleport is being undone
        // every tick by whatever re-projects him onto his deck point. The deck capture answers which.
        // Asked with `ships-at`, which is CONTAINMENT and a COUNT — the right shape for "is a hull
        // here at all". The bounded nearest lookup this replaced answered with a winner and a
        // distance, so "one ship, 60 blocks away" and "two ships, both containing the point" printed
        // the same way, and a reader of the failure could not tell them apart.
        String shipOnPlot = askServer("artest vs ships-at " + plot.dim
                + " " + plot.centerX() + " " + Plot.DEFAULT_Y + " " + plot.centerZ());
        String shipOnBody = last != null && last.has("playerX")
                ? askServer("artest vs ships-at " + plot.dim
                        + " " + (int) Math.round(last.get("playerX").getAsDouble())
                        + " " + (int) Math.round(last.get("playerY").getAsDouble())
                        + " " + (int) Math.round(last.get("playerZ").getAsDouble()))
                : "(no player point to ask about)";
        String capture = askServer("artest vs deck-capture");
        // AND THE CLIENT'S OWN RESOLVER, because the server's answer is only half the question. A body
        // travelling at a CONSTANT delta per tick with its own motion at zero is not being moved by its
        // physics — it is being carried by a rigid transform. When the server then reports no capture
        // and no ship containing the body's point, the only remaining carrier is the client's own ship-frame
        // resolution continuing in a frame the server has already let go of. What is dumped below says
        // whether it is resolving at all, which is the difference between that and a fourth explanation.
        //
        // The resolver's half is its own RECORDS rather than its lifetime counters. `resolvedTicks`
        // and `declinedTicks` were cumulative and JVM-global, so on a shared client they carried
        // every body this side ever touched and a reader could not tell this one's story out of
        // them. The whole ring is asked for (`since(0)`), because a diagnostic wants the tail it can
        // get and each record names its body, its ship and where on the deck it landed.
        // No externalMoveDrops column either, and for the same reason plus one: it was a lifetime
        // count of guard drops over every body, and the releases it was counting are printed in full
        // on the next line — each naming its body and the gate's whole reason.
        String clientResolver =
                "client deck commits (whole ring): " + clientEvents().since(0, "deck_entered")
                + "\n  client deck releases (whole ring): " + clientEvents().since(0, "deck_released")
                // The body's own ship-frame point, per tick, instead of the three statics that used
                // to be sampled here: those held whatever the LAST resolved body left in them, which
                // on a shared client is not necessarily the body this diagnostic is about. The `B=`
                // column of each line is the same number, attributed.
                + "\n  client per-tick resolution: "
                + Events.fieldLines(clientEvents().since(0, "ship_frame_tick"), "line");
        return "\n  readings taken AFTER the verdict, oldest first:" + trail
                + "\n  reached its plot while being watched: " + arrived
                + (arrived
                        ? " — the teleport DID land and the check above read it too early;"
                          + " this is a round-trip budget, not a stray writer."
                        : " — the body never arrived at all; a second writer owns it, or the"
                          + " teleport never reached this client.")
                + "\n  every POSITION WRITE since the teleport, with the caller that made it — this"
                + " is the only line here that NAMES a writer instead of listing candidates: "
                + positionWritesSinceTheTeleport()
                + "\n  ships CONTAINING the PLOT centre: " + shipOnPlot
                + "\n  ships CONTAINING where the BODY ended up: " + shipOnBody
                + "\n  its deck capture, as the SERVER sees it: " + capture
                + "\n  the CLIENT's own ship-frame resolver: " + clientResolver
                + "\n  client world=" + bot().reportWeather()
                + " riding=" + bot().reportRidingEntity();
    }

    /** The event-log sequence taken immediately BEFORE the between-scenario teleport. Negative when
     *  the recorder could not be marked — and {@link #plotMarkFailure} then says why, because an
     *  empty log from a recorder that was never running is not evidence of anything. */
    private long plotMark = -1L;
    private String plotMarkFailure = "";

    /** Take the mark, or remember why it could not be taken. Never throws: this runs before every
     *  scenario in a shared class, and a harness-side gap must not present as a scenario failure. */
    private void markThePositionRecorder() {
        plotMark = -1L;
        plotMarkFailure = "";
        // The REFUSING mark: both honesty flags are read there, because they fail independently —
        // the bus recorder may be unsubscribed, or the launch-time coremod may never have queued the
        // test-only mixin that records a position write, and their silences are identical. It
        // refuses rather than asserts because a harness-side gap must not present as this
        // scenario's failure, which is the whole reason this method existed in longhand.
        Events.MarkOrWhyNot mark;
        try {
            mark = events().markIfInstrumented();
        } catch (Exception unreachable) {
            plotMarkFailure = "the event log could not be marked: " + unreachable;
            return;
        }
        if (mark.usable()) {
            plotMark = mark.seq;
        } else {
            plotMarkFailure = "position-write recorder unusable at the mark: " + mark.refusal;
        }
    }

    /** Every recorded position WRITE since the pre-teleport mark, with the caller trail that names
     *  the writer — or a sentence saying why there is none to show. */
    private String positionWritesSinceTheTeleport() {
        if (plotMark < 0) {
            return "(not asked: " + (plotMarkFailure.isEmpty() ? "no mark was taken" : plotMarkFailure)
                    + ")";
        }
        return askServer("artest events since " + plotMark + " pos_jump");
    }

    /** A server probe asked from a diagnostic: its own failure must never replace the one being told. */
    private String askServer(String command) {
        try {
            return String.valueOf(serverClient().execute(command));
        } catch (Exception unavailable) {
            return "(unavailable: " + unavailable + ")";
        }
    }

    /**
     * Is the client's own player point inside {@code plot}? Absent coordinates answer {@code false}
     * rather than throwing: a client with no player is a different failure, named by its own guard.
     */
    private static boolean isInsidePlot(JsonObject state, Plot plot) {
        return state != null && state.has("playerX") && state.has("playerZ")
                && plot.contains(state.get("playerX").getAsDouble(),
                        state.get("playerZ").getAsDouble());
    }

    /** One observation, short enough that a whole trail stays readable on one line. */
    private static String describePlayerPoint(JsonObject state) {
        if (state == null || !state.has("playerX") || !state.has("playerZ")) {
            return "(no-player)";
        }
        String motion = state.has("motionY")
                ? "/v=" + round2(state.get("motionX")) + "," + round2(state.get("motionY"))
                        + "," + round2(state.get("motionZ"))
                : "";
        return "(" + Math.round(state.get("playerX").getAsDouble()) + ","
                + (state.has("playerY") ? Math.round(state.get("playerY").getAsDouble()) : '?')
                + "," + Math.round(state.get("playerZ").getAsDouble()) + motion + ")";
    }

    /**
     * What counts as "the scenario starts at full health", in half-hearts.
     *
     * <p>Vanilla full is 20.0 and the reset writes exactly that, so the half-heart of slack is not
     * for the value — it is for a client that has applied a regeneration or absorption tick between
     * the write and the read. Below it, something hurt him.</p>
     */
    private static final double FULL_HEALTH_BAR = 19.5D;

    /**
     * How long the client is given to be TOLD about the reset's heal, in ticks.
     *
     * <p>A deadline for a packet, not a stand-in for it: the write has already happened on the
     * server when this starts, so what is being waited for is one round trip. The old poll's own
     * ceiling was 40 ticks and this keeps it — what changed is that the budget now bounds a wait for
     * a RECORD instead of forty ticks of asking a field how it looks.</p>
     */
    private static final int HEALTH_LINK_BUDGET_TICKS = 40;

    /**
     * How long the client is given to FOLLOW a between-scenario dimension transfer, in ticks.
     *
     * <p>Same kind of number as {@link #HEALTH_LINK_BUDGET_TICKS} and it is worth naming the kind:
     * the server has already performed the transfer when this starts, so what is bounded is one
     * round trip plus the client tearing down a world and building another. It is a ceiling on a
     * thing that HAPPENS — an expiry here says the client never arrived, which is news — rather
     * than a guess at how long arriving takes.</p>
     */
    private static final int DIM_LINK_BUDGET_TICKS = 200;

    /**
     * How long the client is given to APPLY the opening teleport, in ticks.
     *
     * <p>A cross-world transfer re-sends the chunks around the destination before the position
     * packet can be applied, so this is the longer of the two; within one world it returns on the
     * first record and costs nothing.</p>
     */
    private static final int PLACEMENT_LINK_BUDGET_TICKS = 200;

    /** Whether any {@code client_pos_look_applied} in a {@code since} reply put the client inside
     *  {@code plot} — the coordinates as the CLIENT applied them, which is the same region and the
     *  same body the plot assertion reads afterwards. A record that carries no finite X/Z (the seam
     *  writes JSON null for those) answers NaN, and NaN is inside nothing. */
    private static boolean appliedInsidePlot(String sinceReply, Plot plot) {
        for (String record : Events.records(sinceReply)) {
            if (plot.contains(Events.number(record, "x"), Events.number(record, "z"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wait until the CLIENT has APPLIED a server placement within one block of {@code x, z}.
     *
     * <p>The far side of a teleport, offered here because it is the same wait the prologue makes
     * and a subclass that hand-rolls it grows the fourth private copy of a shared idea. A stimulus
     * aimed from where the player stands — a ray-traced right-click, a look-direction command — is
     * dispatched by the CLIENT from the position the client holds, so "he has been teleported" is a
     * fact about the wrong process until this returns.</p>
     *
     * <p>Matched on WHERE rather than on a record of any kind: the seam re-sends this packet on
     * every movement rejection, so a bare type wait can close on a rubber-band. The tolerance is a
     * block because a placement above the surface may settle onto it.</p>
     *
     * @param mark the CLIENT's own mark, taken BEFORE the teleport command
     */
    protected final void awaitClientPlacedNear(long mark, double x, double z, String what)
            throws Exception {
        ClientEvents.awaitPlacedNear(clientEvents(), mark, x, z, what, PLACEMENT_LINK_BUDGET_TICKS);
    }

    /**
     * Wait until the CLIENT has APPLIED a server position-look write — for the rotation-only form,
     * {@code tp @a ~ ~ ~ <yaw> <pitch>}, where there is no destination to match on.
     *
     * <p>The packet applies position and rotation together and the record is written after that, so
     * a record since the mark means the aim the test is about to read is the one the server wrote.
     * <b>Its blind spot, because it has one:</b> the seam does not record yaw or pitch, so this
     * proves that <i>a</i> position-look write was applied, not that it was THIS one. A rubber-band
     * correction arriving inside the same window would also satisfy it — which is why the aiming
     * form uses it and the moving form does not
     * ({@link #awaitClientPlacedNear} matches on where the body ended up).</p>
     *
     * @param mark the CLIENT's own mark, taken BEFORE the aiming command
     */
    protected final void awaitClientLookApplied(long mark, String what) throws Exception {
        clientEvents().await(mark, "client_pos_look_applied", what, PLACEMENT_LINK_BUDGET_TICKS);
    }

    // The POINT form of `appliedInsidePlot` lives in ClientEvents.appliedNear, because the tier has
    // two class hierarchies — these shared bases and the harness's own AbstractClientE2ETest — and a
    // wait that belongs to both must not be solved by copying it into each.

    /** Whether any {@code client_health_updated} in a {@code since} reply carries at least {@code
     *  floor} health — the packet the server sends when it heals him, as the client applied it. */
    private static boolean anyHealthAtLeast(String sinceReply, double floor) {
        for (String record : Events.records(sinceReply)) {
            if (Events.number(record, "health") >= floor) {
                return true;
            }
        }
        return false;
    }

    private static double round2(com.google.gson.JsonElement value) {
        return Math.round(value.getAsDouble() * 100.0) / 100.0;
    }

    /**
     * Clear the CLIENT's chat/overlay immediately before a stimulus, and prove it is clear.
     *
     * <p>The per-scenario reset in {@link #resetBetweenScenarios} is not enough for a scenario that
     * OBSERVES chat: this tier shares one client, so an earlier scenario's messages are still in the
     * backlog, and a "the player was told X" assertion that searches the last N lines is searching a
     * window it does not control.</p>
     *
     * <p><b>The harness itself no longer contributes to that backlog.</b> It used to: each server
     * command was completed by a {@code FORGE_TEST_DONE} sentinel BROADCAST to every player, and a
     * six-command arrangement measurably left 13 lines in the chat before the stimulus. The server
     * now answers over its own control socket, and the harness REFUSES to start without one, so a
     * run that got this far produced no sentinel at all. What is left to clear is the game's own
     * output, which is reason enough on its own.</p>
     *
     * <p>This clears the chat channel ONLY. It deliberately does not use the full client reset,
     * which closes the open screen: a GUI scenario's stimulus is a click on that screen, so arming
     * the channel with the full reset would destroy the arrangement it was called to protect.</p>
     */
    protected final void armChatObservation() throws Exception {
        // DRAIN, then clear, then verify — in that order, and repeat until it takes.
        //
        // A server command's completion marker is delivered to the client ASYNCHRONOUSLY: the
        // command channel answers as soon as the server has run it, and the chat packet arrives at
        // the client some ticks later. Clearing the backlog the instant the last arrangement
        // command returns therefore clears everything EXCEPT the marker still in flight, which
        // lands immediately afterwards — measured 2026-08-07, one line, one marker, on a scenario
        // whose arrangement ended with a server command. Waiting first lets the tail land so the
        // clear can actually remove it.
        JsonObject cleared = null;
        JsonObject chat = null;
        int remaining = -1;
        for (int attempt = 0; attempt < 4; attempt++) {
            bot().waitTicks(5);
            cleared = bot().clearChat();
            bot().waitTicks(2);
            chat = bot().reportChat(20);
            remaining = chat.has("count") ? chat.get("count").getAsInt() : -1;
            if (remaining == 0) {
                break;
            }
        }
        scenario.record("armedChatObservation", cleared);
        scenario.requireArranged("the chat channel must be empty at the moment of the stimulus,"
                + " so a matching line can only have come from THIS stimulus; after four"
                + " drain-and-clear rounds it still holds " + remaining + " line(s): "
                + (chat == null ? "?" : chat.get("lines"))
                + " — is a server command running between armChatObservation() and the stimulus?",
                remaining == 0);
    }

    // ── what a subclass implements / uses ────────────────────────────────────

    /**
     * The subsystem this class's scenarios are about, as it should appear in a failure line
     * (e.g. {@code "seal-detector"}, {@code "free-flight"}). It is DECLARED because no amount of
     * stack-walking can infer which system a red belongs to.
     */
    protected abstract String subsystem();

    /**
     * Reset the state channels that belong to a FAMILY of scenarios rather than to every client —
     * run between scenarios, before the teleport, and expected to assert what it closed.
     *
     * <p>The default is a no-op, and deliberately so: this base class's own reset was measured
     * against the non-VS tier, and every command added to it is paid by all 89 scenarios there. A
     * family that leaves something else behind (a ship seat the player is still riding, a
     * subsystem flag it switched on for its own arrangement) closes it here instead, in a base
     * class of its own.</p>
     */
    protected void resetFamilyStateBeforeTeleport() throws Exception {
    }

    /**
     * Where this class's plots live. Override when the scenarios work at GROUND level, or when a
     * class is MIGRATING an existing test — keep the coordinates that test already proved green
     * rather than moving it onto fresh terrain, which is a change of subject disguised as a
     * refactor. See {@link Plot.Lane}.
     */
    protected Plot.Lane lane() {
        return Plot.Lane.DEFAULT;
    }

    protected final Scenario scenario() {
        return scenario;
    }

    protected final Plot plot() {
        return scenario.plot();
    }

    /**
     * WHERE THIS SCENARIO'S FIXTURE STANDS. Ask for it; do not choose coordinates.
     *
     * <p>Two guarantees arrive together and neither is something a scenario has to get right. The
     * PLOT is this scenario's own — allocated once per test method on a lane whose stride cannot be
     * narrower than a plot — so it cannot overlap a sibling's. The HEIGHT is the open-air band,
     * because {@link zmaster587.advancedRocketry.test.FixtureSite#openAir} has no Y parameter to
     * pass. And {@code requireClear} then ASSERTS that the volume actually cleared lies inside the
     * plot, so the non-overlap is checked rather than merely intended.</p>
     *
     * <p><b>This did not exist until 2026-09-14, and the hole it closes was costing point bugs.</b>
     * The allocator above had been here all along, but the ship classes never used it: each scenario
     * wrote its own {@code bx = 5220, bz = 5220}. Two scenarios of one class built at one site in
     * one world and each silently levelled the other's leavings with its pre-clear; nothing said so
     * until the pre-clear became an assertion. A hand-picked coordinate is a promise; this is a
     * mechanism.</p>
     */
    protected final zmaster587.advancedRocketry.test.FixtureSite site() {
        return plot().site();
    }

    protected final RealDedicatedServerHarness server() {
        return sharedServer;
    }

    protected final com.github.stannismod.forge.testing.server.TestClient serverClient() {
        return sharedServer.client();
    }

    protected final ClientBot bot() {
        return sharedClient.bot();
    }

    /** Runs a server probe and joins its reply — the shape every AR client test already uses. */
    protected final String exec(String command) throws Exception {
        return String.join("\n", serverClient().execute(command));
    }

    /**
     * The server's ordered event log, as a scenario should reach it: {@code mark} before the action,
     * {@code since}/{@code await} after, and a failure that prints the CHAIN rather than one last
     * sample.
     *
     * <p>Offered here because the alternative is what keeps happening: a scenario that needs one
     * trace reaches for {@code exec("artest events …")} and a regex of its own. That shape is right
     * in exactly one place — the between-scenario reset below, which must never fail a whole class
     * because a recorder was unavailable, and so REMEMBERS an unusable mark instead of throwing.
     * Copied into a scenario the exemption inverts: a silent empty log becomes "it never happened",
     * which is the one answer an instrument must not be able to fake. {@link Events#mark} asserts
     * the recorder is subscribed and {@link Events#markInstrumented} additionally asserts the
     * test-only mixins were woven — the two independent silences behind an empty position trace.
     */
    protected final Events events() {
        return new Events(this::exec, bot()::waitTicks);
    }

    /**
     * The CLIENT's ordered event log, behind the same verbs.
     *
     * <p>Offered beside {@link #events()} because the two logs answer different questions and a
     * scenario picks by SUBJECT, not by convenience: a body released and reclaimed inside a hull is
     * the client's fact — the server rebases an {@code EntityPlayerMP}'s position instead of
     * releasing at all, so its probe reports "still tracked" straight through a release the client
     * really performed — while an assembly or a dimension change is the server's.</p>
     *
     * <p>{@link Events#markInstrumented} must never be called on this one: the client reply carries
     * no {@code mixins} flag. {@link ClientEvents} says why, and what to assert instead.</p>
     */
    protected final Events clientEvents() {
        return ClientEvents.of(bot());
    }

    /**
     * Wait until a chat line the player was actually SHOWN contains {@code needle}, and return that
     * line's own text; or fail naming the link and printing every line the HUD was handed.
     *
     * <p>A chat message is the shape a poll can never see: it is handed to the HUD, counted down and
     * gone, so a reader arriving late cannot tell a message that was shown from one that was never
     * sent. It is also the half of a "the player is told" contract the server's own log cannot
     * reach — a {@code chat_message_sent} record says the server composed and dispatched it, not
     * that it landed on a screen.</p>
     *
     * <p><b>Matched without case, deliberately.</b> A chat line is prose, and its capitalisation
     * belongs to the translation rather than to the contract. A test that pinned the case would fail
     * on a language file edit that broke nothing.</p>
     *
     * <p>Four classes carried a copy of these twenty lines, each saying in its javadoc that it was
     * written locally only because no shared base offered it.</p>
     *
     * @param needle a fragment of the line the player must read, matched ignoring case
     * @param what   a player-facing sentence for what this message means, used in the failure
     * @return the {@code text} of the first matching line — the caller asserts on the line itself
     */
    protected final String awaitClientChat(long mark, String needle, int tickBudget, String what)
            throws Exception {
        String lower = needle.toLowerCase(Locale.ROOT);
        String reply;
        try {
            reply = clientEvents().awaitMatching(mark, "client_chat_received",
                    seen -> firstChatTextContaining(seen, lower) != null,
                    "carrying \"" + needle + "\"", what, tickBudget);
        } catch (AssertionError never) {
            // Which of the silences it was, as an assertion rather than as prose in a message: an
            // absent instrument means nobody was looking, and that must not read as "no such line".
            Events.assertInstrumentRan(clientEvents().since(mark, "client_chat_received"),
                    "client_chat_events", what);
            throw never;
        }
        return firstChatTextContaining(reply, lower);
    }

    /** The {@code text} of the first record in a client chat reply containing {@code lowerNeedle},
     *  or null. The needle is matched against the line's own text, never against the envelope. */
    private static String firstChatTextContaining(String reply, String lowerNeedle) {
        Matcher m = CHAT_TEXT.matcher(String.valueOf(reply));
        while (m.find()) {
            if (m.group(1).toLowerCase(Locale.ROOT).contains(lowerNeedle)) {
                return m.group(1);
            }
        }
        return null;
    }

    private static final Pattern CHAT_TEXT = Pattern.compile("\"text\":\"([^\"]*)\"");

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * Liveness ceiling for the failure-path ping. Deliberately NOT the command channel's own
     * timeout, which is two minutes scaled by the fork factor — six minutes at eight forks. That is
     * the right budget for a command and a terrible one for "should the rest of this class run",
     * because a HUNG client (socket open, nobody answering) would cost it once per scenario.
     *
     * <p>Five seconds against a round trip measured in milliseconds, still scaled so a genuinely
     * starved client is not mistaken for a corpse.</p>
     */
    private static final int PING_TIMEOUT_MS = 5_000;

    private boolean pingClient() {
        if (sharedClient == null) {
            return false;
        }
        return sharedClient.bot().isAlive(
                com.github.stannismod.forge.testing.TestTimeouts.scaledMillis(PING_TIMEOUT_MS));
    }

    private String renderStateBundle(Scenario s) {
        if (s.stateBundle().isEmpty()) {
            return "--- no state bundle declared (Scenario.describeOnFailureWith) ---\n";
        }
        StringBuilder sb = new StringBuilder("--- state bundle ---\n");
        for (String command : s.stateBundle()) {
            String reply;
            try {
                reply = exec(command);
            } catch (Throwable t) {
                reply = "<probe failed: " + t + ">";
            }
            sb.append("  ").append(command).append("\n    ").append(reply).append('\n');
        }
        return sb.toString();
    }
}
