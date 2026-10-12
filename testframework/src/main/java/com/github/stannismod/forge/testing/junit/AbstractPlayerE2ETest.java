package com.github.stannismod.forge.testing.junit;

import com.github.stannismod.forge.testing.client.ClientReads;
import com.github.stannismod.forge.testing.client.PlayerInput;
import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.github.stannismod.forge.testing.server.TestClient;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The base of an E2E: a player's path, walked on a fresh server and client of its own, both in E2E MODE.
 *
 * <p><b>What it hands a test, and what it does not.</b> {@link #input()} is a player's input and nothing
 * else; {@link #client()} reads the client; {@link #server()} sends server commands. There is no
 * {@code ClientBot} here, so a verb that names its target ({@code interactBlock}, {@code useItem},
 * {@code sendChat}) or writes what a player's input would have produced does not compile in a subclass.
 * And there is no {@link HarnessBoot}: every harness start takes one, so a subclass cannot start a server
 * or client of its own, outside e2e mode, either — that does not compile, and a nested class reaching a
 * mechanics base's factory is refused at the call (see {@link HarnessBoot}). Both children are started in
 * e2e mode ({@link com.github.stannismod.forge.testing.E2EMode}), so what the types cannot stop — a
 * command sent by reflection, a string built at run time, a helper anywhere — is refused by the child,
 * at the call: the client bridge by its command kinds, the server by the consuming mod's own gate.</p>
 *
 * <p><b>Its own boot, every test, from a start that is typed.</b> A player's path starts from a fresh
 * game; a world another scenario touched carries its discoveries, items and config. What the player's
 * start differs in from the game as it ships is {@link #playerStart()} — config settings and harvested
 * world files the consuming project's allowlist grants ({@link PlayerStart}) — written by this base into
 * a fresh game directory before the server boots, since a mod reads its config once, at load. A harvested
 * file comes from a throwaway server THIS base boots on the game as it ships, so no e2e has a reason to
 * start a plain server of its own.</p>
 *
 * <p><b>Its player is in SURVIVAL</b> (the e2e server forces game mode 0), and what is built for him is
 * built before his first input: the server admits a build only until his walk begins, in his dimension and
 * within reach of him — {@link PlayerInput} tells it, before the first input it sends.</p>
 *
 * <p><b>What its code may reach is checked in the build.</b> The test bindings check walks the bytecode of
 * every class on this base and of every test-source class it reaches, and fails the build on any reference
 * outside the e2e's surface: this base's protected API, {@link PlayerInput}, {@link ClientReads},
 * {@link TestClient#execute}, {@link PlayerStart}, JUnit's assertions and plain JDK value types. No
 * reflection, no system property, no thread, no file, no rule or runner, no other harness class.</p>
 *
 * <p>Skipped unless {@code -Dforge.test.harness.enabled=true} and {@code -Dforge.test.client.enabled=true}
 * — and the milestone tier counts a skipped e2e as a FAILURE, unless the tier was disabled by name.</p>
 */
public abstract class AbstractPlayerE2ETest {

    private RealDedicatedServerHarness serverHarness;
    private RealClientHarness clientHarness;

    /**
     * What the player's start differs in from the game as it ships. The default is nothing: a player's
     * start is the game as it ships. Asked once per test, before anything is booted.
     */
    protected PlayerStart playerStart() {
        return PlayerStart.asShipped();
    }

    @Before
    public final void startBothInE2EMode() throws Exception {
        Assume.assumeTrue("Server harness disabled — set -D" + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED
                        + "=true to enable",
                Boolean.parseBoolean(System.getProperty(AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue("Client harness disabled — set -D" + AbstractClientE2ETest.PROP_CLIENT_ENABLED
                        + "=true to enable",
                Boolean.parseBoolean(System.getProperty(AbstractClientE2ETest.PROP_CLIENT_ENABLED, "false")));

        PlayerStart start = playerStart();
        Path gameDir = Files.createTempDirectory("forge-player-e2e-");
        start.writeSettings(gameDir);
        if (start.needsHarvest()) {
            harvestFromTheGameAsItShips(start, gameDir);
        }
        serverHarness = RealDedicatedServerHarness.startE2E(new HarnessBoot(), gameDir);
        try {
            clientHarness = RealClientHarness.startE2E(new HarnessBoot(), serverHarness);
        } catch (Exception startupException) {
            try {
                serverHarness.close();
            } catch (Exception cleanupException) {
                startupException.addSuppressed(cleanupException);
            }
            serverHarness = null;
            throw startupException;
        }
    }

    /**
     * Boot a throwaway server on the game as it ships — the same seed every harness world has — let it save
     * and stop, and take the start's harvested files out of its world. Its directory is deleted after.
     */
    private static void harvestFromTheGameAsItShips(PlayerStart start, Path gameDir)
            throws Exception {
        Path harvestRoot = Files.createTempDirectory("forge-player-e2e-shipped-");
        try {
            RealDedicatedServerHarness.startWith(new HarnessBoot(), harvestRoot, /*cleanupOnClose=*/false).close();
            start.writeHarvests(harvestRoot.resolve("world"), gameDir);
        } finally {
            try (java.util.stream.Stream<Path> walk = Files.walk(harvestRoot)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @After
    public final void stopBoth() throws Exception {
        Exception deferred = null;
        if (clientHarness != null) {
            try {
                clientHarness.close();
            } catch (Exception e) {
                deferred = e;
            }
            clientHarness = null;
        }
        if (serverHarness != null) {
            try {
                serverHarness.close();
            } catch (Exception e) {
                if (deferred == null) {
                    deferred = e;
                } else {
                    deferred.addSuppressed(e);
                }
            }
            serverHarness = null;
        }
        if (deferred != null) {
            throw deferred;
        }
    }

    /** A player's input to the client — keys, buttons, the cursor, typing, where he looks. */
    protected final PlayerInput input() {
        return clientHarness.bot().input();
    }

    /** What the test may read of the client, and its clock. */
    protected final ClientReads client() {
        return clientHarness.bot().reads();
    }

    /** The server's command channel; the server refuses, at the call, what an e2e may not send. */
    protected final TestClient server() {
        return serverHarness.client();
    }
}
