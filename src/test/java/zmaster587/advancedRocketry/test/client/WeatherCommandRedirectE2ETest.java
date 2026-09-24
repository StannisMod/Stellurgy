package zmaster587.advancedRocketry.test.client;

import zmaster587.advancedRocketry.test.DimWeather;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.GameTicks;

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

        // The player — standing on the planet — types vanilla /weather rain. Both logs are marked
        // first: the server's for the planet's own sky changing, the client's for being told.
        Events server = serverEvents();
        long rainMark = server.markInstrumented();
        long clientRainMark = clientEvents().mark();
        clientHarness.bot().sendChat("/weather rain 600");

        // Server truth: the PLANET's per-dim state flips to raining — a LINK on that planet's own
        // weather cycle announcing the edge, then one read of the state it announced.
        awaitPlanetSky(server, rainMark, true, "the player's /weather rain on a planet must start"
                + " rain on THAT planet (redirect to /advancedrocketry weather missing?)");
        DimWeather planetAfter = serverWeather(DIM);
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

        // Player truth: the client in the planet dim renders the rain the command asked for. Strength
        // streams per tick (code 7); the begin-raining FLAG (code 1) is only broadcast when the
        // server-side strength crosses the isRaining() threshold (> 0.2). Both are packets the
        // client records applying, so the wait is for BOTH to have arrived — past that threshold —
        // and the report is read once after it.
        String told = clientEvents().awaitMatching(clientRainMark, "client_game_state_changed",
                seen -> !Events.recordsWhere(seen, "state", ClientEvents.BEGIN_RAINING_STATE).isEmpty()
                        && ClientEvents.toldRainStrengthAtLeast(seen, 0.25),
                "telling the client it is raining, at a strength of at least 0.25",
                "the client standing on the planet must be told the rain its command started",
                RAIN_LINK_BUDGET_TICKS);
        JsonObject onPlanet = clientHarness.bot().reportWeather();
        assertTrue("client should still be in the planet dim: " + onPlanet,
                onPlanet.has("dim") && onPlanet.get("dim").getAsInt() == DIM);
        assertTrue("client-visible isRaining must flip true on the planet: " + onPlanet,
                onPlanet.get("isRaining").getAsBoolean());
        assertTrue("client rainStrength must start climbing on the planet; what it was told: "
                + told, onPlanet.get("rainStrength").getAsFloat() > 0f);

        // Reverse direction: /weather clear from the same spot clears the
        // planet (and the overworld stays untouched — still clear).
        long clearMark = server.markInstrumented();
        clientHarness.bot().sendChat("/weather clear 600");
        awaitPlanetSky(server, clearMark, false, "the player's /weather clear on a planet must end"
                + " the rain on THAT planet");
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
     * The SERVER's ordered event log, stepped on the server's own clock — this class runs its own
     * harness pair, so it builds the reader the shared bases would have handed it.
     */
    private Events serverEvents() {
        return new Events(cmd -> String.join("\n", serverHarness.client().execute(cmd)),
                ticks -> GameTicks.advance(serverHarness.client(), GameTicks.server(), ticks));
    }

    /**
     * Wait for the PLANET's own weather cycle to announce that its sky became {@code raining} since
     * {@code mark} ({@code planet_weather_changed}, edge-only, keyed by dimension).
     *
     * <p>It replaced a poll of the server flag, whose own note said "the fix is a recorder on the
     * weather write, not a longer budget" — written when no weather record existed. One exists now,
     * on the cycle's tick, and a command's write is announced by the next one. What it cannot see:
     * a sky that flipped and flipped back inside one cycle tick, which the cycle cannot either.</p>
     */
    private void awaitPlanetSky(Events server, long mark, boolean raining, String what)
            throws Exception {
        server.awaitRecordWithFields(mark, "planet_weather_changed", what, SKY_LINK_BUDGET_TICKS,
                "dim", String.valueOf(DIM), "raining", String.valueOf(raining));
    }

    /** How long a player's command may take to reach the planet's sky — the 200 ticks the poll it
     *  replaced was capped at. */
    private static final int SKY_LINK_BUDGET_TICKS = 200;

    /** How long the rain may take to reach the client once the planet is raining — the 200 ticks
     *  the poll it replaced was capped at (twenty reads ten apart). */
    private static final int RAIN_LINK_BUDGET_TICKS = 200;
}
