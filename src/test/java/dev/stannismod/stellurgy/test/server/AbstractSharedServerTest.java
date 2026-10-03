package dev.stannismod.stellurgy.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.junit.ClassScope;
import com.github.stannismod.forge.testing.junit.ClassScopeRunner;
import com.github.stannismod.forge.testing.junit.ScopedTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.github.stannismod.forge.testing.server.TestClient;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import dev.stannismod.stellurgy.test.DimList;
import dev.stannismod.stellurgy.test.EvictionReports;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Plot;
import dev.stannismod.stellurgy.test.Reply;

/**
 * class-scoped harness lifecycle base class.
 *
 * <p>{@link AbstractHeadlessServerTest} starts a fresh dedicated-server JVM
 * per {@code @Test} method (its {@code @Before}/{@code @After} lifecycle),
 * so a class with N independent methods pays N server cold starts.</p>
 *
 * <p>This base class is the opt-in alternative: <strong>one</strong>
 * server JVM is started when the class starts and closed when it ends, by
 * the class run's {@link SharedServerScope} (owned by {@link ClassScopeRunner},
 * so no static holds it). All {@code @Test} methods in the subclass share
 * that harness, so the class pays ONE cold start however many methods it
 * carries. A fixture the whole class shares is kept in {@link #scope()}'s
 * memory, never in a static.</p>
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
 *       atmosphere density to 0 leaks to all subsequent methods unless the
 *       method puts it back in a {@code finally}, as
 *       {@link AtmosphereOxygenSmokeTest} does).
 *       JUnit 4 does not guarantee method execution order.</li>
 *   <li><b>Probe-only mutations</b>: any direct world-state mutation must
 *       go through the {@code /stellurgytest} probe surface, never through
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
@RunWith(ClassScopeRunner.class)
@ClassScope(SharedServerScope.class)
public abstract class AbstractSharedServerTest implements ScopedTest<SharedServerScope> {

    /** This class run's server and plots; handed over by the runner before any rule or {@code @Before}. */
    private SharedServerScope scope;

    @Override
    public final void attachScope(SharedServerScope scope) {
        this.scope = scope;
    }

    /** What this class run shares beyond the server itself — a fixture built once for the class. */
    protected final SharedServerScope scope() {
        return scope;
    }

    /**
     * The eviction announcements already made by this test instance's readers of the server log. Per
     * INSTANCE, not per class run: a subclass builds its {@link dev.stannismod.stellurgy.test.Events}
     * in field initialisers, which run in the constructor — before the runner attaches the scope.
     * No assertion reads it; the price is the cadence of the "RING EVICTED" lines.
     */
    private final EvictionReports evictions = new EvictionReports();

    /** Hand this to every {@link dev.stannismod.stellurgy.test.Events} this test builds. */
    protected final EvictionReports evictionReports() {
        return evictions;
    }

    /** The shared server's command client. */
    protected final TestClient client() {
        return scope.harness().client();
    }

    /** The shared harness. Available for the few cases that need the
     *  RealDedicatedServerHarness API beyond `client()`. */
    protected final RealDedicatedServerHarness harness() {
        return scope.harness();
    }

    /** Send a command to the shared server and return the concatenated console response. */
    protected final String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    /**
     * What time it is in the GAME, asked of the server.
     *
     * <p>The server's own tick counter. A test that needs to know how long it is willing to wait for
     * something asks here rather than looking at a watch. The implementation is {@link GameTicks}'s;
     * two readers of one clock is exactly one too many.</p>
     */
    protected final long serverTick() throws Exception {
        return GameTicks.read(client(), GameTicks.server());
    }

    /** Read an integer field out of {@code /stellurgytest planet info <dim>}
     *  JSON. Asserts the field is present. */
    protected final int planetIntField(int dim, String field) throws Exception {
        return Integer.parseInt(planetInfoField(dim, field));
    }

    /** Read a float/double field out of {@code /stellurgytest planet info <dim>}. */
    protected final double planetFloatField(int dim, String field) throws Exception {
        return Double.parseDouble(planetInfoField(dim, field));
    }

    /** True iff Stellurgy's planet registry knows the given dim, observed via
     *  {@code /ar planet list} (which iterates {@code getRegisteredDimensions()}
     *  &rarr; the underlying {@code dimensionList} keyset). Cannot use
     *  {@code /stellurgytest planet info} here because
     *  {@code DimensionManager.getDimensionProperties} falls back to
     *  {@code overworldProperties} for unknown dims, so the
     *  info probe is incapable of distinguishing "registered" from
     *  "absent" by itself. */
    protected final boolean planetExists(int dim) throws Exception {
        // `stellurgytest dim list` reports `DimensionManager.getRegisteredDimensions()`, the very
        // collection `/ar planet list` iterates, as a list of integers — so `DIM9` cannot match
        // `DIM90` the way a chat line could.
        return DimList.from(this::exec).holds(dim);
    }

    /**
     * One field of a planet-info reply, as text. Absence is the answer, and WHICH answer is the
     * CALLER's: this verb is handed a field name, so it cannot know what a missing one means.
     */
    private String planetInfoField(int dim, String field) throws Exception {
        String src = exec("stellurgytest planet info " + dim);
        String value = Reply.of("stellurgytest planet info", src).textOr(field, null);
        if (value == null) {
            throw new AssertionError("field \"" + field + "\" not found in: " + src);
        }
        return value;
    }

    // ---- position isolation, as a MECHANISM ----------------------------------------------------
    //
    // Contract item 1 above ("Position-isolated: each method picks a unique BASE_X offset ... or
    // includes a hash of its method name in the position") was prose, and prose is what every
    // method had to remember. It is now something a method can ASK for: `site()` hands out this
    // scenario's own plot, in the open-air band, and the clear that follows asserts the volume
    // stays inside it.
    //
    // Unique WITHIN THE CLASS is the whole requirement, because each class boots its own server and
    // therefore its own world — which is why the allocator lives in the class run's scope.

    @Rule
    public final TestName scenarioName = new TestName();

    /**
     * Where this class's plots live. Override for a class whose fixtures are wider than a plot, or
     * that must keep coordinates its green runs were taken on.
     */
    protected Plot.Lane lane() {
        return Plot.Lane.DEFAULT;
    }

    /** This scenario's own patch of world — allocated once, never recycled. */
    protected final Plot plot() {
        return scope.plot(getClass().getName() + "#" + scenarioName.getMethodName(), lane());
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
