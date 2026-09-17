package zmaster587.advancedRocketry.test.client;

import zmaster587.advancedRocketry.test.DimWeather;
import zmaster587.advancedRocketry.test.Events;

import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * E2e regression guard for the vanilla {@code /weather} &rarr; per-dim
 * {@code /advancedrocketry weather} redirect
 * ({@code PlanetWeatherEventHandler.redirectWeatherCommand}).
 *
 * <p><b>Why a real client + real chat.</b> The bug this guards is
 * sender-position-dependent: vanilla {@code CommandWeather} hard-codes
 * {@code server.worlds[0]}, so a player standing on an AR planet who runs
 * {@code /weather rain} silently rains the OVERWORLD and leaves the planet
 * untouched. A console-driven command cannot reproduce that — the console
 * sender stands in the overworld. The framework's {@code send_chat} probe
 * routes through {@code EntityPlayerSP.sendChatMessage} (the real
 * {@code CPacketChatMessage} path), so the server handles the command with the
 * planet-standing player as sender and the {@code CommandEvent} redirect runs
 * its production path.</p>
 *
 * <p>Lifecycle is reproduced inline rather than via {@link AbstractClientE2ETest}
 * for the same reason as {@code WeatherClientSyncE2ETest}: the planet fixture
 * XML must exist in the workdir BEFORE the server boots.</p>
 */
public class WeatherCommandRedirectE2ETest {

    private static final int DIM = 9304;
    /** Must match the framework's single-client default username — the op grant keys on it. */
    private static final String PLAYER = "ForgeTestClient";

    private Path workDir;
    private RealDedicatedServerHarness serverHarness;
    private RealClientHarness clientHarness;

    @Before
    public void startBoth() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -D" + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue(
                "Client harness disabled — set -D" + AbstractClientE2ETest.PROP_CLIENT_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractClientE2ETest.PROP_CLIENT_ENABLED, "false")));

        workDir = Files.createTempDirectory("forge-client-weather-redirect-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"1\" numGasGiants=\"0\">\n"
                + "        <planet name=\"RedirectPlanet\" DIMID=\"" + DIM + "\">\n"
                + "            <isKnown>true</isKnown>\n"
                + "            <fogColor>0.5,0.5,0.5</fogColor>\n"
                + "            <skyColor>0.4,0.6,0.9</skyColor>\n"
                + "            <gravitationalMultiplier>100</gravitationalMultiplier>\n"
                + "            <orbitalDistance>100</orbitalDistance>\n"
                + "            <orbitalTheta>0</orbitalTheta>\n"
                + "            <orbitalPhi>0</orbitalPhi>\n"
                + "            <retrograde>false</retrograde>\n"
                + "            <averageTemperature>250</averageTemperature>\n"
                + "            <rotationalPeriod>24000</rotationalPeriod>\n"
                + "            <atmosphereDensity>100</atmosphereDensity>\n"
                + "            <generateCraters>false</generateCraters>\n"
                + "            <generateCaves>true</generateCaves>\n"
                + "            <generateVolcanos>false</generateVolcanos>\n"
                + "        </planet>\n"
                + "    </star>\n"
                + "</galaxy>\n";
        Files.write(arConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));

        serverHarness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/false);
        try {
            clientHarness = RealClientHarness.start(serverHarness);
        } catch (Exception startupException) {
            try {
                serverHarness.close();
            } catch (Exception cleanup) {
                startupException.addSuppressed(cleanup);
            }
            serverHarness = null;
            throw startupException;
        }
    }

    @After
    public void stopBoth() throws Exception {
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
                if (deferred == null) deferred = e;
                else deferred.addSuppressed(e);
            }
            serverHarness = null;
        }
        if (deferred != null) throw deferred;
    }

    @Test
    public void slashWeatherOnPlanetRainsThePlanetNotTheOverworld() throws Exception {
        clientHarness.bot().waitForWorld();

        // /weather (and the redirect target /advancedrocketry weather) require
        // permission level 2 — grant it the way a server admin would.
        serverHarness.client().execute("op " + PLAYER);

        // Known baseline: both dims explicitly clear. The set/get probes also
        // load + pin the planet dim before the teleport.
        serverHarness.client().execute("artest weather set 0 clear 12000");
        serverHarness.client().execute("artest weather set " + DIM + " clear 12000");
        DimWeather before = serverWeather(DIM);
        assertTrue("planet must be wrapped before the command test: " + before.raw(),
                before.usesARWorldInfo());
        assertFalse("planet must start clear: " + before.raw(), before.raining);

        long transferMark = clientEvents().mark();
        serverHarness.client().execute("artest tp " + DIM);
        awaitClientDim(transferMark, DIM);

        // The player — standing on the planet — types vanilla /weather rain.
        clientHarness.bot().sendChat("/weather rain 600");

        // Server truth: the PLANET's per-dim state flips to raining...
        DimWeather planetAfter = waitForServerRaining(DIM, true);
        assertTrue("planet did not start raining after player /weather rain "
                        + "(redirect to /advancedrocketry weather missing?): " + planetAfter.raw(),
                planetAfter.raining);

        // ...and the OVERWORLD stays clear. Without the redirect vanilla
        // CommandWeather writes to server.worlds[0] — this is the assertion
        // that fails on the unfixed build.
        DimWeather overworld = serverWeather(0);
        assertFalse("player /weather rain on a planet leaked to the overworld "
                        + "(vanilla worlds[0] path, redirect not applied): " + overworld.raw(),
                overworld.raining);

        // Player truth: the client in the planet dim renders the rain the
        // command asked for. Strength streams per tick (code 7); the
        // begin-raining FLAG (code 1) is only broadcast when the server-side
        // strength crosses the isRaining() threshold (> 0.2), so wait past
        // that before asserting the flag.
        JsonObject onPlanet = waitForClientRainStrengthAtLeast(0.25f);
        assertTrue("client should still be in the planet dim: " + onPlanet,
                onPlanet.has("dim") && onPlanet.get("dim").getAsInt() == DIM);
        assertTrue("client-visible isRaining must flip true on the planet: " + onPlanet,
                onPlanet.get("isRaining").getAsBoolean());
        assertTrue("client rainStrength must start climbing on the planet: " + onPlanet,
                onPlanet.get("rainStrength").getAsFloat() > 0f);

        // Reverse direction: /weather clear from the same spot clears the
        // planet (and the overworld stays untouched — still clear).
        clientHarness.bot().sendChat("/weather clear 600");
        waitForServerRaining(DIM, false);
        DimWeather overworldAfterClear = serverWeather(0);
        assertFalse("overworld must remain clear after planet /weather clear: "
                + overworldAfterClear.raw(), overworldAfterClear.raining);
    }

    /** What the SERVER says one world's sky is doing, as opposed to what the client is shown. */
    private DimWeather serverWeather(int dim) throws Exception {
        return DimWeather.forDim(
                        cmd -> String.join("\n", serverHarness.client().execute(cmd)), dim)
                .requireDim(dim);
    }

    /**
     * The client is IN {@code expectedDim}, waited for as the RESPAWN packet that puts it there —
     * {@link ClientEvents#awaitDim}, which is where the wait and its narrative live.
     *
     * @param transferMark the CLIENT's own mark, taken BEFORE the command that transfers him
     */
    private void awaitClientDim(long transferMark, int expectedDim) throws Exception {
        ClientEvents.awaitDim(clientEvents(), transferMark, expectedDim,
                "he must be standing on the planet before he types the command this test is about,"
                        + " since the redirect is keyed to the world he is IN",
                DIM_LINK_BUDGET_TICKS,
                () -> "last weather report: " + clientHarness.bot().reportWeather());
    }

    /** The CLIENT's own event log, behind the shared verbs. */
    private Events clientEvents() {
        return ClientEvents.of(clientHarness.bot());
    }

    /** How long the client is given to FOLLOW a transfer the server has already performed. */
    private static final int DIM_LINK_BUDGET_TICKS = 200;

    /**
     * Polls the SERVER-side wrapped weather flag of {@code dim} until it equals
     * {@code raining} (~10 s cap) — the chat command travels client &rarr; server and
     * lands on the next tick, so a one-shot read would race it. Returns a JSON
     * object with the final raw probe output under {@code raw}.
     *
     * <p><b>A POLL, deliberately, and here is what it cannot see.</b> Nothing publishes a weather
     * record: neither AR's test mixins nor the harness records a weather write on either side
     * (searched both source roots, 2026-09-15), so there is no link to wait on and this samples a
     * flag instead. Two consequences a reader must carry: an expiry here cannot tell "the command
     * never reached the server" from "it reached it and the redirect did nothing", and a flag that
     * flipped and flipped BACK inside one ten-tick gap is invisible to it. The fix is a recorder on
     * the weather write, not a longer budget.</p>
     */
    private DimWeather waitForServerRaining(int dim, boolean raining) throws Exception {
        DimWeather last = null;
        for (int waited = 0; waited < 200; waited += 10) {
            last = serverWeather(dim);
            if (last.raining == raining) {
                return last;
            }
            clientHarness.bot().waitTicks(10);
        }
        throw new AssertionError("server dim " + dim + " never reached isRaining="
                + raining + "; last probe: " + (last == null ? "none" : last.raw()));
    }

    /**
     * Polls until client-visible rainStrength reaches {@code minStrength} (~10 s cap, soft).
     *
     * <p><b>A VALUE, not a link, and it stays a poll for that reason.</b> Rain strength RAMPS — the
     * client moves it a little each tick toward the server's target — so there is no instant at
     * which it "happens" and no record that could carry one. What it cannot see: the ramp's shape
     * between two samples, and a strength that rose and fell inside one ten-tick gap. The caller
     * asserts on the returned report, so a wait that ends short is a value the caller can judge
     * rather than a verdict this method invents.</p>
     */
    private JsonObject waitForClientRainStrengthAtLeast(float minStrength) throws Exception {
        JsonObject latest = clientHarness.bot().reportWeather();
        for (int waited = 0; waited < 200; waited += 10) {
            if (latest.has("rainStrength") && latest.get("rainStrength").getAsFloat() >= minStrength) {
                return latest;
            }
            clientHarness.bot().waitTicks(10);
            latest = clientHarness.bot().reportWeather();
        }
        return latest; // soft wait — caller asserts and prints the report
    }
}
