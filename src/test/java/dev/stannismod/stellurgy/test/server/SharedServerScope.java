package dev.stannismod.stellurgy.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.junit.TestClassScope;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import org.junit.Assume;

import java.util.HashMap;
import java.util.Map;

import dev.stannismod.stellurgy.test.Plot;

/**
 * One class run of an {@link AbstractSharedServerTest}: the ONE dedicated server every method of the
 * class shares, and the plots handed out in that server's world.
 *
 * <p>Owned by the runner's run of the class; booted before the class's first test and closed after its
 * last. Each class boots its own server and therefore its own world, so the plot index starts at zero
 * for every class.</p>
 */
public final class SharedServerScope extends TestClassScope {

    private RealDedicatedServerHarness harness;
    private final Map<String, Plot> plots = new HashMap<>();
    private int nextPlotIndex;

    @Override
    protected void open(Class<?> testClass) throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -D"
                        + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        // Cold-start once for the whole class.
        harness = RealDedicatedServerHarness.start();
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

    /** The class's server; throws when its boot failed. */
    RealDedicatedServerHarness harness() {
        if (harness == null) {
            throw new IllegalStateException(
                    "Shared harness not started — the class's boot failed or has already been closed.");
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
