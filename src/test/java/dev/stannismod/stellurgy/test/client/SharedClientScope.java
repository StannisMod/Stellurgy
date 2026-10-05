package dev.stannismod.stellurgy.test.client;

import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.TestClassScope;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;

import java.util.HashMap;
import java.util.Map;

import dev.stannismod.stellurgy.test.Plot;

/**
 * One class run of an {@link AbstractSharedClientE2ETest}: the ONE server JVM and ONE client JVM its
 * scenarios share, whether the pair has died, and the plots handed out in that pair's world.
 *
 * <p>Owned by the runner's run of the class. The pair is booted LAZILY by the class's first scenario
 * ({@link AbstractSharedClientE2ETest#prepareScenario}), because the boot has to ask the test instance
 * what it needs — its seeded game directory, its framebuffer — and is closed when the class run ends.
 * A boot that failed leaves nothing up, so the next scenario tries again, exactly as before.</p>
 */
public final class SharedClientScope extends TestClassScope {

    /** The client's start-time framebuffer switch, read by the harness as it launches the child. */
    private static final String CLIENT_FBO_PROPERTY = "forge.test.client.fbo";

    RealDedicatedServerHarness server;
    RealClientHarness client;
    /** Set once the shared harness stops answering; every later scenario then fails FAST. */
    boolean harnessDead;
    String firstFailure;
    /** Scenario name -> its plot. Stable within a run because the method order is pinned. */
    final Map<String, Plot> plots = new HashMap<>();
    int nextPlotIndex;
    /**
     * What {@link #CLIENT_FBO_PROPERTY} held before this class claimed it, and whether it claimed it
     * at all. CLEAR MEANS RESTORE: a null here is a real state (the property was unset), so the flag
     * is what says "we changed it", never the value.
     */
    private String displacedFboProperty;
    private boolean fboPropertyClaimed;

    @Override
    protected void open(Class<?> testClass) {
        // Nothing yet: the pair is booted by the first scenario, which can be asked what it needs.
    }

    boolean booted() {
        return server != null;
    }

    /** Boot the pair for {@code test}'s class. */
    void boot(AbstractSharedClientE2ETest test, int plotOffset) throws Exception {
        harnessDead = false;
        firstFailure = null;
        plots.clear();
        nextPlotIndex = plotOffset;

        GameDirSeed seed = GameDirSeed.forTheSharedHarness();
        test.seedGameDirectory(seed);

        long startedNanos = System.nanoTime();
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("forge-shared-client-");
        String seeded = seed.writeInto(root);
        server = RealDedicatedServerHarness.startWith(root, /*cleanupOnClose=*/true);
        // The client's start-time options come from system properties the harness reads as it
        // launches the child, so a class that needs one sets it HERE, around the boot, and the value
        // it displaced goes back in close(). Set, not assumed: a scenario that measures pixels
        // asserts the option took (see ClientBot.setFramebuffer's own `previous`).
        if (test.clientNeedsFramebuffer() && !fboPropertyClaimed) {
            displacedFboProperty = System.getProperty(CLIENT_FBO_PROPERTY);
            fboPropertyClaimed = true;
            System.setProperty(CLIENT_FBO_PROPERTY, "true");
        }
        try {
            client = RealClientHarness.start(server);
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
}
