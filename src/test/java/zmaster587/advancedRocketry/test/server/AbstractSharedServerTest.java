package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.github.stannismod.forge.testing.server.TestClient;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TestName;

import java.util.HashMap;
import java.util.Map;

import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.Plot;

/**
 * class-scoped harness lifecycle base class.
 *
 * <p>{@link AbstractHeadlessServerTest} starts a fresh dedicated-server JVM
 * per {@code @Test} method (its {@code @Before}/{@code @After} lifecycle),
 * so a class with N independent methods pays N server cold starts.</p>
 *
 * <p>This base class is the opt-in alternative: <strong>one</strong>
 * server JVM is started in {@code @BeforeClass} and closed in
 * {@code @AfterClass}. All {@code @Test} methods in the subclass share
 * that harness, so the class pays ONE cold start however many methods it
 * carries.</p>
 *
 * <p><b>What a cold start costs, measured 2026-09-21 rather than estimated:</b>
 * a single-class re-run took 74 s of wall clock against 25.7 s of test
 * execution, which leaves <b>~40 s per class</b> once the build tool's own
 * ~10 s is taken off. Across a full unfiltered server tier — 231 classes,
 * 669 tests, 26 m 41 s at 8 parallel forks — test execution is 5 016 s,
 * i.e. <b>39 % of the tier's wall clock; the rest is cold starts</b>. The
 * 174 classes on this base carry 569 of those tests and execute for 716 s
 * between them, against roughly 6 960 s of cold start. <b>So the win this
 * class exists for is real and it is nowhere near taken</b>: one class per
 * subject still means one server per subject, and the median class here
 * runs for 2.4 seconds.</p>
 *
 * <h2>Contract for subclasses</h2>
 *
 * Every {@code @Test} method MUST be:
 *
 * <ol>
 *   <li><b>Position-isolated — ASK for a site, do not choose coordinates.</b>
 *       {@link #site()} hands this scenario its own plot, in the open-air
 *       band, and the clear that follows asserts the volume stays inside
 *       it. <b>This used to read "each method picks a unique BASE_X
 *       offset, or includes a hash of its method name in the position",
 *       and that convention is what {@link #site()} replaced</b>: a
 *       convention is something every method has to remember, and on
 *       2026-09-21 one of them did not. Three scenarios of one class built
 *       at a single hand-picked site, and the second met the first one's
 *       scaffolding — the fixture's tower, its rocket builder and the
 *       creative plug are not taken into an assembled craft, so eight
 *       blocks were left standing where the next scenario built. It had
 *       been silent for as long as the class had existed and surfaced only
 *       once the pre-clear became an assertion.</li>
 *   <li><b>Id-fresh</b>: stations / satellites / rockets created via
 *       probes get auto-allocated ids; subclasses must read the new id
 *       from each create response and not assume a specific id range.</li>
 *   <li><b>No state-leak between methods</b>: a method MUST NOT mutate
 *       state that another method reads as a precondition (e.g. setting
 *       atmosphere density to 0 leaks to all subsequent methods —
 *       {@link AtmosphereOxygenSmokeTest} stays on the per-method base).
 *       JUnit 4 does not guarantee method execution order.</li>
 *   <li><b>Probe-only mutations</b>: any direct world-state mutation must
 *       go through the {@code /artest} probe surface, never through
 *       Bukkit/Forge APIs reflected into the test JVM.</li>
 * </ol>
 *
 * <h2>When NOT to use this base</h2>
 *
 * <ul>
 *   <li>Persistence-restart tests (a fresh workDir / a multi-boot
 *       sequence): stay on the per-method {@link AbstractHeadlessServerTest}
 *       or manage the harness manually. <b>Such a class is a candidate for
 *       SPLITTING, never for merging</b> — the restart is its subject.</li>
 *   <li>Tests with global mutations (atmosphere density, weather state)
 *       that are hard to clean up between methods.</li>
 *   <li>Tests that depend on the server's initial registry being pristine
 *       (e.g. counting fresh registry entries). <b>The precondition is
 *       ASSERTED, not obtained by ordering</b>: JUnit 4 promises no method
 *       order, so "it runs first" is not a property a test may hold.</li>
 *   <li>A reading that only ever INCREASES. A cumulative counter cannot be
 *       reset by any {@code @Before}, so a threshold on one is satisfied by
 *       whatever ran before you, and moving the counter test-side does not
 *       help — it is cumulative because of what it COUNTS.</li>
 * </ul>
 *
 * <p><b>"It has its own config / workDir" is NOT on that list, and never
 * was an axis.</b> When seven client classes were audited for staying off
 * their shared base "because of their config", three wrote no config at
 * all — their justification was a comment — and the four that did wanted
 * one key, the same value twice, and a flag that was already settable at
 * runtime. A constraint written in a comment is second-hand testimony;
 * check it at the source before it keeps a class alone.</p>
 *
 * <h2>Failure isolation</h2>
 *
 * One method's hard crash (e.g. NPE in the server JVM) brings the shared
 * server down. JUnit will report ALL remaining methods in the class as
 * failed against the same root cause. This is the trade-off — keep the
 * shared base only for classes whose methods are stable AND fast.
 */
public abstract class AbstractSharedServerTest {

    private static volatile RealDedicatedServerHarness shared;

    @BeforeClass
    public static void startSharedHarness() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -D"
                        + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        // Cold-start once for the whole class.
        shared = RealDedicatedServerHarness.start();
    }

    @AfterClass
    public static void stopSharedHarness() throws Exception {
        if (shared != null) {
            try {
                shared.close();
            } finally {
                shared = null;
            }
        }
    }

    /** The shared server's command client. Safe to call from any
     *  {@code @Test} method; null between @AfterClass and the next class's
     *  @BeforeClass. */
    protected static TestClient client() {
        if (shared == null) {
            throw new IllegalStateException(
                    "Shared harness not started — @BeforeClass setup failed "
                            + "or test called from outside a JUnit lifecycle.");
        }
        return shared.client();
    }

    /** The shared harness. Available for the few cases that need the
     *  RealDedicatedServerHarness API beyond `client()`. */
    protected static RealDedicatedServerHarness harness() {
        return shared;
    }

    // ---- position isolation, as a MECHANISM ----------------------------------------------------
    //
    // Contract item 1 above ("Position-isolated: each method picks a unique BASE_X offset ... or
    // includes a hash of its method name in the position") was prose, and prose is what every
    // method had to remember. It is now something a method can ASK for: `site()` hands out this
    // scenario's own plot, in the open-air band, and the clear that follows asserts the volume
    // stays inside it.
    //
    // Unique WITHIN THE CLASS is the whole requirement, because each class boots its own server in
    // @BeforeClass and therefore its own world. The key carries the class anyway, so a fork that
    // runs several classes in one JVM never hands two of them the same patch either.

    @Rule
    public final TestName scenarioName = new TestName();

    private static final Map<String, Plot> PLOTS = new HashMap<>();
    private static int nextPlotIndex;

    /**
     * Where this class's plots live. Override for a class whose fixtures are wider than a plot, or
     * that must keep coordinates its green runs were taken on.
     */
    protected Plot.Lane lane() {
        return Plot.Lane.DEFAULT;
    }

    /** This scenario's own patch of world — allocated once, never recycled. */
    protected final Plot plot() {
        String key = getClass().getName() + "#" + scenarioName.getMethodName();
        synchronized (PLOTS) {
            Plot existing = PLOTS.get(key);
            if (existing == null) {
                existing = Plot.forScenario(nextPlotIndex++, key, 0, lane());
                PLOTS.put(key, existing);
            }
            return existing;
        }
    }

    /**
     * WHERE THIS SCENARIO'S FIXTURE STANDS. Ask for it; do not choose coordinates.
     *
     * <p>The plot decides WHERE and cannot overlap a sibling's; {@link FixtureSite#openAir} decides
     * the HEIGHT and takes no Y at all; and {@code requireClear} asserts the volume actually cleared
     * lies inside the plot. None of the three is a thing the scenario has to get right.</p>
     */
    protected final FixtureSite site() {
        return plot().site();
    }
}
