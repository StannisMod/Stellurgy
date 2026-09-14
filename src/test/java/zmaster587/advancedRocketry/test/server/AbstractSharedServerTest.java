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
 * per {@code @Test} method (its {@code @Before}/{@code @After} lifecycle).
 * For a class with N independent test methods, that's N × ~10-15 s of
 * server cold-start cost. With 136 server tests today, the total wall
 * time at {@code -Pforks=3} is ~17 min.</p>
 *
 * <p>This base class is the opt-in alternative: <strong>one</strong>
 * server JVM is started in {@code @BeforeClass} and closed in
 * {@code @AfterClass}. All {@code @Test} methods in the subclass share
 * that harness. For a 6-method class, this saves 5 × ~12 s ≈ 60 s of
 * wall time per class.</p>
 *
 * <h2>Contract for subclasses</h2>
 *
 * Every {@code @Test} method MUST be:
 *
 * <ol>
 *   <li><b>Position-isolated</b>: if the test places blocks, the
 *       positions must not collide with any other method in the same
 *       class. Convention: each method picks a unique {@code BASE_X}
 *       offset (e.g. method 1 at x=100, method 2 at x=200, etc.) or
 *       includes a hash of its method name in the position.</li>
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
 *   <li>Persistence-restart tests (need a fresh workDir / multi-boot
 *       sequence): stay on the per-method {@link AbstractHeadlessServerTest}
 *       or manage the harness manually.</li>
 *   <li>Tests with global mutations (atmosphere density, weather state)
 *       that are hard to clean up between methods.</li>
 *   <li>Tests that depend on the server's initial registry being pristine
 *       (e.g. counting fresh registry entries).</li>
 * </ul>
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
