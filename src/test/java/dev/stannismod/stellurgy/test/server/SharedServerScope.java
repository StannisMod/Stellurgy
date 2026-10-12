package dev.stannismod.stellurgy.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.junit.TestClassScope;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.github.stannismod.forge.testing.server.ServerRoot;
import org.junit.Assume;

import java.util.HashMap;
import java.util.Map;

import dev.stannismod.stellurgy.test.Plot;
import dev.stannismod.stellurgy.test.client.GameDirSeed;

/**
 * One class run of an {@link AbstractSharedServerTest}: the ONE dedicated server every method of the
 * class shares, and the plots handed out in that server's world.
 *
 * <p>Owned by the runner's run of the class; closed after its last test. Each class boots its own
 * server and therefore its own world, so the plot index starts at zero for every class.</p>
 *
 * <p><b>Booted on the first scenario that asks for it, not when the scope opens</b>: the game directory
 * a class declares ({@link AbstractSharedServerTest#seedGameDirectory}) is asked of a test INSTANCE, and
 * none exists when the runner opens the scope. A boot that fails is not retried: every later scenario of
 * the class is handed the same failure.</p>
 */
public final class SharedServerScope extends TestClassScope {

    private RealDedicatedServerHarness harness;
    private RuntimeException bootFailure;
    private final Map<String, Plot> plots = new HashMap<>();
    private int nextPlotIndex;

    @Override
    protected void open(Class<?> testClass) {
        Assume.assumeTrue(
                "Server harness disabled — set -D"
                        + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
    }

    @Override
    protected void close() throws Exception {
        if (harness != null) {
            try {
                harness.close();
            } finally {
                harness = null;
            }
        }
    }

    /**
     * The class's server, booted on the first call over the game directory {@code asking}'s class
     * declares; throws the boot's failure, on this call and every later one, when it did not start.
     */
    synchronized RealDedicatedServerHarness harness(AbstractSharedServerTest asking) {
        if (bootFailure != null) {
            throw bootFailure;
        }
        if (harness == null) {
            try {
                GameDirSeed seed = GameDirSeed.forTheSharedHarness();
                asking.seedGameDirectory(seed);
                ServerRoot root = ServerRoot.fresh("forge-shared-server-").seed(seed);
                harness = RealDedicatedServerHarness.startWith(harnessBoot(), root, /*cleanupOnClose=*/true);
                // A scenario whose premise is a seeded galaxy must be able to show the galaxy was there.
                System.out.println("[shared-server] " + asking.getClass().getSimpleName() + " seeded:"
                        + seed.describe());
            } catch (Exception e) {
                bootFailure = new IllegalStateException("the shared server of "
                        + asking.getClass().getSimpleName() + " did not boot", e);
                throw bootFailure;
            }
        }
        return harness;
    }

    /** {@code key}'s own plot on {@code lane}, allocated once in this class run, never recycled. */
    synchronized Plot plot(String key, Plot.Lane lane) {
        Plot existing = plots.get(key);
        if (existing == null) {
            existing = Plot.forScenario(nextPlotIndex++, key, 0, lane);
            plots.put(key, existing);
        }
        return existing;
    }
}
