package zmaster587.advancedRocketry.command.test;

import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import zmaster587.advancedRocketry.AdvancedRocketry;

/**
 * Conditional registration entry-point for the test-only {@code /artest} command
 * tree.
 *
 * <p>Call from {@code AdvancedRocketry.serverStarting} (or any FMLServerStartingEvent
 * handler):</p>
 * <pre>{@code
 *   TestProbeCommandRegistration.registerIfTestMode(event);
 * }</pre>
 *
 * <p>The command is registered ONLY when the JVM was launched with
 * {@code -Dadvancedrocketry.tests=true}. In normal gameplay the helper is a no-op
 * and the command is never visible.</p>
 */
public final class TestProbeCommandRegistration {

    private static final String FLAG = "advancedrocketry.tests";

    /**
     * Framework-set flag on dedicated server JVMs spawned by
     * {@code RealDedicatedServerHarness}. AR doesn't need to forward
     * {@link #FLAG} explicitly — being in a harness-spawned server is a
     * sufficient signal to register the probes.
     */
    private static final String HARNESS_FLAG = "forge.test.server";

    /**
     * The same signal on a harness-spawned CLIENT JVM. It is NOT optional: {@link #FLAG} is set on
     * the test JVM and forwarded to the server child, but never to the client, so without this every
     * client-side test-gated diagnostic AR has — the {@code [FF-TRACE/*]} lines, the per-tick
     * ship-frame history — was silently dead. An empty client-side diagnostic then reads exactly
     * like "the code never ran", which is the one reading a diagnostic must never be able to fake.
     */
    private static final String HARNESS_CLIENT_FLAG = "forge.test.client";

    private TestProbeCommandRegistration() {}

    public static boolean isTestMode() {
        return Boolean.getBoolean(FLAG) || Boolean.getBoolean(HARNESS_FLAG)
                || Boolean.getBoolean(HARNESS_CLIENT_FLAG);
    }

    public static void registerIfTestMode(FMLServerStartingEvent event) {
        if (!isTestMode()) {
            return;
        }
        event.registerServerCommand(new TestProbeCommand());
        // register the rocket-event recorder at server start so
        // counters are accurate from the first rocket lifecycle event.
        TestProbeCommand.RocketEventRecorder.ensureRegistered();
        // The ordered event log. Subscribed here and nowhere else, so a shipped game has no
        // subscriber, builds no record and pays nothing for what only a test wants to see.
        TestEventLog.ServerRecorder.ensureRegistered();
        AdvancedRocketry.logger.info("Registered /artest test-only probe commands (-D" + FLAG + "=true)");
        bootstrapTestServerBridge();
    }

    /**
     * Test-only hook, the server-side twin of the client proxy's bridge start: when the JVM was
     * launched with a control port, reflectively start the test framework's in-JVM server bridge so
     * the harness can run a command and read that command's own reply over a socket, instead of
     * writing it into the console and slicing the answer out of the log behind a chat-broadcast
     * sentinel.
     *
     * <p>Started HERE because this is the first point at which the command tree above exists and
     * the {@code MinecraftServer} instance is in hand — the bridge executes through the server's own
     * command manager, so anything it can run, the console can run identically.</p>
     *
     * <p>Inert in normal gameplay twice over: this whole method is reached only in test mode, and
     * the bridge itself no-ops without its port property. The bridge class lives in the test-only
     * framework and is NOT on the production runtime classpath; the {@link ClassNotFoundException}
     * branch is what makes a production launch cost nothing.</p>
     */
    private static void bootstrapTestServerBridge() {
        try {
            Class<?> bridge = Class.forName(
                    "com.github.stannismod.forge.testing.server.bridge.ForgeTestServerBootstrap");
            bridge.getMethod("bootstrap").invoke(null);
        } catch (ClassNotFoundException ignored) {
            // Test framework absent at runtime — no-op (production launch).
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to bootstrap forge test server bridge", e);
        }
    }
}
