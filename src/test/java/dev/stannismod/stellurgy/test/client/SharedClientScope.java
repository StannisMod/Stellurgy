package dev.stannismod.stellurgy.test.client;

import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.TestClassScope;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.github.stannismod.forge.testing.server.ServerRoot;
import com.github.stannismod.forge.testing.server.TestClient;
import dev.stannismod.stellurgy.test.CraftCensus;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.EvictionReports;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Plot;
import dev.stannismod.stellurgy.test.Reply;

import java.util.HashMap;
import java.util.Map;

/**
 * One class run of an {@link AbstractSharedClientTest}: the ONE server JVM and ONE client JVM its
 * scenarios share, whether the pair has died, and the plots handed out in that pair's world.
 *
 * <p>Owned by the runner's run of the class. The pair is booted LAZILY by the class's first scenario
 * ({@link AbstractSharedClientTest#prepareScenario}), because the boot has to ask the test instance
 * what it needs — its seeded game directory, its framebuffer — and is closed when the class run ends.
 * A boot that failed leaves nothing up, so the next scenario tries again, exactly as before.</p>
 */
public final class SharedClientScope extends TestClassScope {

    RealDedicatedServerHarness server;
    RealClientHarness client;
    /** Set once the shared harness stops answering; every later scenario then fails FAST. */
    boolean harnessDead;
    String firstFailure;
    /** Scenario name -> its plot. Stable within a run because the method order is pinned. */
    final Map<String, Plot> plots = new HashMap<>();
    int nextPlotIndex;
    /** The eviction announcements made by the census's reader of the pair's server log. */
    private final EvictionReports evictions = new EvictionReports();

    @Override
    protected void open(Class<?> testClass) {
        // Nothing yet: the pair is booted by the first scenario, which can be asked what it needs.
    }

    boolean booted() {
        return server != null;
    }

    /** Boot the pair for {@code test}'s class. */
    void boot(AbstractSharedClientTest test, int plotOffset) throws Exception {
        harnessDead = false;
        firstFailure = null;
        plots.clear();
        nextPlotIndex = plotOffset;

        GameDirSeed seed = GameDirSeed.forTheSharedHarness();
        test.seedGameDirectory(seed);

        long startedNanos = System.nanoTime();
        ServerRoot root = ServerRoot.fresh("forge-shared-client-").seed(seed);
        String seeded = seed.describe();
        server = RealDedicatedServerHarness.startWith(harnessBoot(), root, /*cleanupOnClose=*/true);
        // A class that needs the framebuffer from the first frame asks the harness's start for it, by
        // name -- never through the launch property the harness also reads, which is the test JVM's and
        // outlives this class. Asked, not assumed: a scenario that measures pixels asserts the option
        // took (see ClientBot.setFramebuffer's own `previous`).
        try {
            client = test.clientNeedsFramebuffer()
                    ? RealClientHarness.startWithFramebuffer(harnessBoot(), server)
                    : RealClientHarness.start(harnessBoot(), server);
        } catch (Exception startupFailure) {
            try {
                server.close();
            } catch (Exception cleanup) {
                startupFailure.addSuppressed(cleanup);
            }
            server = null;
            throw startupFailure;
        }
        // The number this whole base class exists to amortise — print it so a run can be audited
        // against the claim rather than against a memory of it. The seed is printed with it: a
        // scenario whose premise is a config value must be able to show that value was there.
        System.out.println("[shared-harness] boot ms="
                + (System.nanoTime() - startedNanos) / 1_000_000L
                + " — one server JVM + one client JVM for " + test.getClass().getSimpleName()
                + (seeded.isEmpty() ? " (no seeded game directory)" : " seeded:" + seeded));
    }

    /**
     * After every scenario the pair's server holds no craft — see {@link CraftCensus}. A pair that is not
     * up, or that stopped answering (the scenario that killed it has already failed), is not judged.
     */
    @Override
    protected void afterScenario(String scenario) throws Exception {
        if (server == null || harnessDead) {
            return;
        }
        TestClient serverClient = server.client();
        Events events = new Events(c -> Reply.answering(c, serverClient.read(c)).raw(),
                ticks -> GameTicks.advance(serverClient, GameTicks.server(), ticks), evictions);
        CraftCensus.requireNoneLeft(scenario, serverClient, events);
    }

    @Override
    protected void close() throws Exception {
        Exception deferred = null;
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                deferred = e;
            }
            client = null;
        }
        if (server != null) {
            try {
                server.close();
            } catch (Exception e) {
                if (deferred == null) deferred = e;
                else deferred.addSuppressed(e);
            }
            server = null;
        }
        if (deferred != null) throw deferred;
    }
}
