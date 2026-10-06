package dev.stannismod.stellurgy.command.test;

import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import dev.stannismod.stellurgy.Stellurgy;

/**
 * Conditional registration entry-point for the test-only {@code /stellurgytest} command
 * tree.
 *
 * <p>Call from {@code Stellurgy.serverStarting} (or any FMLServerStartingEvent
 * handler):</p>
 * <pre>{@code
 *   TestProbeCommandRegistration.registerIfTestMode(event);
 * }</pre>
 *
 * <p>The command is registered ONLY when the JVM was launched with
 * {@code -Dstellurgy.tests=true}. In normal gameplay the helper is a no-op
 * and the command is never visible.</p>
 */
public final class TestProbeCommandRegistration {

    private static final String FLAG = "stellurgy.tests";

    /**
     * Framework-set flag on dedicated server JVMs spawned by
     * {@code RealDedicatedServerHarness}. Stellurgy doesn't need to forward
     * {@link #FLAG} explicitly — being in a harness-spawned server is a
     * sufficient signal to register the probes.
     */
    private static final String HARNESS_FLAG = "forge.test.server";

    /**
     * The same signal on a harness-spawned CLIENT JVM. It is NOT optional: {@link #FLAG} is set on
     * the test JVM and forwarded to the server child, but never to the client, so without this every
     * client-side test-gated diagnostic Stellurgy has — the {@code [FF-TRACE/*]} lines, the per-tick
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
        // What belongs to THIS server's life: on the bus from here, off it when the server stops.
        ServerScoped scope = ServerScoped.start();
        TestProbeCommand command = new TestProbeCommand(scope.hold(new WeaponFireVetoProbe()));
        event.registerServerCommand(command);
        // register the rocket-event recorder at server start so
        // counters are accurate from the first rocket lifecycle event.
        command.rocketEvents.ensureRegistered();
        // The ordered event log, and the damage-occurrence listener every tile gets, both on the
        // test side.
        attachTestEventRecorder(event.getServer());
        Stellurgy.logger.info("Registered /stellurgytest test-only probe commands (-D" + FLAG + "=true)");
        bootstrapTestServerBridge();
    }

    /**
     * Test-only hook: subscribe the ordered event log's bus recorder and start the log of
     * {@code server}. Both live in the test source set, on the server's own trace object, so a
     * shipped game has no subscriber, builds no record and pays nothing for what only a test wants
     * to see; the {@link ClassNotFoundException} branch is what makes a production launch cost
     * nothing.
     *
     * <p>Called from HERE, by name, because a bus registration reads the active mod container and
     * this is a lifecycle event of this mod — the one moment that container is Stellurgy's.
     * {@code EventBus.register} logs a "should be impossible" error whenever it finds no active
     * container, which is the case a subscription made from the test side on its own would risk.</p>
     */
    private static void attachTestEventRecorder(net.minecraft.server.MinecraftServer server) {
        try {
            Class<?> recorder = Class.forName("dev.stannismod.stellurgy.test.trace.ServerEventRecorder");
            recorder.getMethod("attach", net.minecraft.server.MinecraftServer.class).invoke(null, server);
        } catch (ClassNotFoundException ignored) {
            // Test source set absent at runtime — no-op (production launch).
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to attach the test event recorder", e);
        }
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
