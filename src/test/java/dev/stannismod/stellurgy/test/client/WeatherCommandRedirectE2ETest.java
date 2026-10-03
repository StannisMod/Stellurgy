package dev.stannismod.stellurgy.test.client;

import dev.stannismod.stellurgy.test.EvictionReports;
import dev.stannismod.stellurgy.test.DimWeather;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;

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
 * {@code /stellurgy weather} redirect
 * ({@code PlanetWeatherEventHandler.redirectWeatherCommand}).
 *
 * <p><b>Why a real client + real chat.</b> The bug this guards is
 * sender-position-dependent: vanilla {@code CommandWeather} hard-codes
 * {@code server.worlds[0]}, so a player standing on a Stellurgy planet who runs
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

    /**
     * The eviction announcements already made for this test's own logs. Per test INSTANCE: this class
     * boots its harness per test (or manages it itself), so the server and client whose counters it
     * compares live no longer than this instance.
     */
    private final EvictionReports evictions = new EvictionReports();

    private EvictionReports evictionReports() {
        return evictions;
    }

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
        Path stellurgyConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(stellurgyConfigDir);
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
                + "            <orbitalDistance>" + dev.stannismod.stellurgy.util.AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU + "</orbitalDistance>\n"
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
        Files.write(stellurgyConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));

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

    /**
     * <p>red-witnessed: one inversion per leg, 2026-09-28. RAIN — {@code WeatherCommand#execute} at {@code worldinfo.setRaining(true)} not
     * setting the flag: "no `planet_weather_changed` carrying dim = 9304 and raining = true". CLEAR —
     * the clear branch ({@code WeatherCommand#execute} at {@code if ("clear".equals(action))}) writing nothing: "… raining = false was recorded
     * within 200 ticks". Dropping only its {@code setRaining(false)} stays GREEN: the
     * {@code setRainTime(0)} beside it makes vanilla's weather cycle flip the flag off on the next
     * tick, so each write suffices alone.</p>
     */
    @Test
    public void slashWeatherOnPlanetRainsThePlanetNotTheOverworld() throws Exception {
        clientHarness.bot().waitForWorld();

        // /weather (and the redirect target /stellurgy weather) require
        // permission level 2 — grant it the way a server admin would.
        serverHarness.client().execute("op " + PLAYER);

        // Known baseline: both dims explicitly clear. The set/get probes also
        // load + pin the planet dim before the teleport.
        serverHarness.client().execute("stellurgytest weather set 0 clear 12000");
        serverHarness.client().execute("stellurgytest weather set " + DIM + " clear 12000");
        DimWeather before = serverWeather(DIM);
        assertTrue("planet must be wrapped before the command test: " + before.raw(),
                before.usesStellurgyWorldInfo());
        assertFalse("planet must start clear: " + before.raw(), before.raining);

        long transferMark = clientEvents().mark();
        serverHarness.client().execute("stellurgytest tp " + DIM);
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
                + " rain on THAT planet (redirect to /stellurgy weather missing?)");
        DimWeather planetAfter = serverWeather(DIM);
        assertTrue("planet did not start raining after player /weather rain "
                        + "(redirect to /stellurgy weather missing?): " + planetAfter.raw(),
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
        return ClientEvents.of(clientHarness.bot(), evictionReports());
    }

    /** How long the client is given to FOLLOW a transfer the server has already performed. */
    private static final int DIM_LINK_BUDGET_TICKS = 200;

    /**
     * The SERVER's ordered event log, stepped on the server's own clock — this class runs its own
     * harness pair, so it builds the reader the shared bases would have handed it.
     */
    private Events serverEvents() {
        return new Events(cmd -> String.join("\n", serverHarness.client().execute(cmd)),
                ticks -> GameTicks.advance(serverHarness.client(), GameTicks.server(), ticks), evictionReports());
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
