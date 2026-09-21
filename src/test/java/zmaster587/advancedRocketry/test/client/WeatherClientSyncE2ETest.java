package zmaster587.advancedRocketry.test.client;

import zmaster587.advancedRocketry.test.Reply;
import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import zmaster587.advancedRocketry.test.DimWeather;
import zmaster587.advancedRocketry.test.Events;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * multi-planet weather isolation as exercised through a real
 * Minecraft client.
 *
 * <p><b>What this validates end-to-end.</b> Two AR planets are pre-staged via
 * XML into the harness workdir (deterministic dim ids 9301 and 9302), the
 * harness server boots and registers both, weather is set to OPPOSITE values
 * on the two dims, the real client gets cross-dim teleported via the
 * test-only {@code /artest tp <dim>} probe (which calls
 * {@code PlayerList.transferPlayerToDimension} just like the production
 * {@code /advancedrocketry goto} command would, firing
 * {@code PlayerChangedDimensionEvent} &rarr; {@code PlanetWeatherEventHandler
 * .syncToPlayer} &rarr; vanilla {@code SPacketChangeGameState}), and the
 * <em>client-side rendered</em> weather state is observed via the framework's
 * {@code report_weather} probe (forge-test-framework 0.4.1+) to match the
 * dim the player is currently in. That's the full server&rarr;packet&rarr;client&rarr;render
 * loop covered.</p>
 *
 * <p>The class does NOT extend {@link AbstractClientE2ETest} because that base
 * class's {@code @Before final} creates a fresh workdir with no AR planet
 * XML, and we need deterministic dim ids the test can target. The lifecycle
 * is reproduced inline ({@link #startBoth()} / {@link #stopBoth()}).</p>
 */
public class WeatherClientSyncE2ETest {

    private static final int DIM_A = 9301;
    private static final int DIM_B = 9302;
    /**
     * Deliberately NEVER touched by console probes before the phantom-fade leg
     * of the test: its WorldServer must be constructed mid-teleport (while the
     * overworld is raining) to exercise the constructor-seeding path.
     */
    private static final int DIM_C = 9303;

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

        workDir = Files.createTempDirectory("forge-client-weather-sync-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"3\" numGasGiants=\"0\">\n"
                + planetXml("ClientPlanetA", DIM_A)
                + planetXml("ClientPlanetB", DIM_B)
                + planetXml("ClientPlanetC", DIM_C)
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

    private static String planetXml(String name, int dim) {
        return "        <planet name=\"" + name + "\" DIMID=\"" + dim + "\">\n"
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
                + "        </planet>\n";
    }

    @Test
    public void weatherIsolatedAcrossDimsThroughRealClient() throws Exception {
        clientHarness.bot().waitForWorld();

        // Seed deterministic, opposite weather on the two planets. /artest
        // weather set goes through world.getWorldInfo().setRaining(...), which
        // on AR planets is our ARDimensionWorldInfo wrapper.
        String setA = String.join("\n", serverHarness.client().execute(
                "artest weather set " + DIM_A + " rain 12000"));
        assertTrue("set rain on dim A failed: " + setA, Reply.of(setA).ok());
        String setB = String.join("\n", serverHarness.client().execute(
                "artest weather set " + DIM_B + " clear 12000"));
        assertTrue("set clear on dim B failed: " + setB, Reply.of(setB).ok());

        // Confirm the wrapper is in place on BOTH AR dims — without this the
        // isolation assertion below could pass for the wrong reason (e.g.
        // vanilla shared weather happened to differ on the two dims this
        // sample tick).
        DimWeather getA = serverWeather(DIM_A);
        DimWeather getB = serverWeather(DIM_B);
        assertTrue("dim A WorldInfo class should be ARDimensionWorldInfo: " + getA.raw(),
                getA.usesARWorldInfo());
        assertTrue("dim B WorldInfo class should be ARDimensionWorldInfo: " + getB.raw(),
                getB.usesARWorldInfo());
        assertTrue("dim A should be raining after explicit set: " + getA.raw(), getA.raining);
        assertFalse("dim B should NOT be raining after explicit clear: " + getB.raw(),
                getB.raining);

        // Teleport the client to dim A. Vanilla 1.12 /tp doesn't cross dims,
        // and /advancedrocketry goto needs an Entity sender (unreachable from
        // the harness server console). /artest tp picks the connected player
        // and calls PlayerList.transferPlayerToDimension directly — same path
        // commandGoto uses internally, but driveable from the console.
        Events clientLog = clientEvents();
        long toA = clientLog.mark();
        serverHarness.client().execute("artest tp " + DIM_A);
        awaitClientDim(clientLog, toA, DIM_A);

        // The client now SEES dim A's wrapped weather. rainStrength is
        // server-driven via SPacketChangeGameState (begin/end raining +
        // strength edges), so it ramps up over a handful of ticks before
        // settling near 1.0. Poll a short window.
        ClientPoll.Result<JsonObject> rainA = waitForClientRainStrengthAtLeast(0.05f);
        JsonObject onA = rainA.value;
        assertTrue("client should be in dim A after goto: " + onA,
                onA.has("dim") && onA.get("dim").getAsInt() == DIM_A);
        assertTrue("client-visible isRaining must be true on dim A: " + onA,
                onA.get("isRaining").getAsBoolean());
        assertTrue("client rainStrength must climb above 0 on dim A; the ramp was watched as "
                        + rainA, onA.get("rainStrength").getAsFloat() > 0f);

        // Teleport to dim B. This is the path that fires
        // PlayerChangedDimensionEvent -> PlanetWeatherEventHandler.syncToPlayer,
        // pushing the new dim's weather via SPacketChangeGameState. The
        // explicit end-raining packet should drop client-visible rain
        // immediately.
        long toB = clientLog.mark();
        serverHarness.client().execute("artest tp " + DIM_B);
        awaitClientDim(clientLog, toB, DIM_B);

        JsonObject onB = clientHarness.bot().reportWeather();
        assertTrue("client should be in dim B after goto: " + onB,
                onB.has("dim") && onB.get("dim").getAsInt() == DIM_B);
        assertFalse("client-visible isRaining must be FALSE on dim B (isolation across "
                        + "teleport — A->B must not carry A's rain): " + onB,
                onB.get("isRaining").getAsBoolean());
        // Not just the flag: an end-raining packet alone leaves the client at
        // strength 1.0 (vanilla code-2 semantics). The transfer sync must zero
        // the strength too, or the player keeps seeing A's rain on B.
        assertEquals("client rainStrength must be 0 on clear dim B: " + onB,
                0f, onB.get("rainStrength").getAsFloat(), 0f);

        // Server-side wrapper guarantees on dim B persist too.
        DimWeather getBAgain = serverWeather(DIM_B);
        assertTrue("dim B wrapper must persist across teleports: " + getBAgain.raw(),
                getBAgain.usesARWorldInfo());
        assertFalse("server-side dim B must remain clear: " + getBAgain.raw(),
                getBAgain.raining);

        // ── Phantom-fade regression: fresh world constructed under overworld
        // rain. Vanilla /weather (and our artest equivalent) flags the
        // OVERWORLD; dim C's WorldServer does not exist yet and is only
        // constructed mid-teleport — at which point its constructor runs
        // calculateInitialWeather() against the pre-wrap DerivedWorldInfo and
        // seeds rainingStrength from the raining overworld. Without the
        // post-wrap reseed the client renders a ~5 s rain fade on arrival.
        String setOver = String.join("\n", serverHarness.client().execute(
                "artest weather set 0 rain 12000"));
        assertTrue("set rain on overworld failed: " + setOver, Reply.of(setOver).ok());

        long toC = clientLog.mark();
        serverHarness.client().execute("artest tp " + DIM_C);
        awaitClientDim(clientLog, toC, DIM_C);

        // Sample across the would-be fade window (~5 s = 100 ticks): the
        // client-visible strength must hold at exactly 0 the whole time. A
        // single non-zero sample means the seeded strength leaked to the
        // client (either via the transfer sync or the per-tick
        // SPacketChangeGameState(7) stream from the server lerp).
        //
        // STILL A SAMPLING WINDOW, and deliberately: what it guards against is a per-tick STREAM of
        // strength packets, so the pass is the window expiring with every sample at zero. The
        // vocabulary has no event for a vanilla game-state packet, so there is nothing here to
        // convert — the arrival in dim C above is the link, and this is the observation.
        for (int sample = 0; sample < 6; sample++) {
            JsonObject onC = clientHarness.bot().reportWeather();
            assertTrue("client should be in dim C (sample " + sample + "): " + onC,
                    onC.has("dim") && onC.get("dim").getAsInt() == DIM_C);
            assertFalse("client must not see rain on fresh clear dim C (sample "
                            + sample + "): " + onC,
                    onC.get("isRaining").getAsBoolean());
            assertEquals("client rainStrength must hold at 0 on fresh dim C (sample "
                            + sample + "): " + onC,
                    0f, onC.get("rainStrength").getAsFloat(), 0f);
            clientHarness.bot().waitTicks(20);
        }

        // The overworld itself must still be raining — dim C staying dry must
        // come from per-dim isolation, not from the rain set having failed.
        DimWeather overAfter = serverWeather(0);
        assertTrue("overworld should still be raining: " + overAfter.raw(), overAfter.raining);
    }

    /** What the SERVER says one world's sky is doing, as opposed to what the client is shown. */
    private DimWeather serverWeather(int dim) throws Exception {
        return DimWeather.forDim(
                        cmd -> String.join("\n", serverHarness.client().execute(cmd)), dim)
                .requireDim(dim);
    }

    // ── the CLIENT's own event log ────────────────────────────────────────────
    //
    // The three crossings this test drives are observed on the CLIENT: the far side of a transfer is
    // the respawn the player's own client performs. It runs its own harness rather than the shared
    // base's, so it reaches {@link ClientEvents} directly instead of through {@code clientEvents()}.

    private Events clientEvents() {
        return ClientEvents.of(clientHarness.bot());
    }

    /**
     * Wait for the CLIENT to be respawned into {@code expectedDim} — the far side of the transfer,
     * read off its own record rather than sampled ({@link ClientEvents#awaitDim}).
     *
     * <p>{@code mark} is taken BEFORE the transfer is ordered, which is the whole point.</p>
     */
    private void awaitClientDim(Events events, long mark, int expectedDim) throws Exception {
        ClientEvents.awaitDim(events, mark, expectedDim,
                "the weather these scenarios read is the weather of the world he is IN",
                DIM_LINK_BUDGET_TICKS,
                () -> "last weather report: " + clientHarness.bot().reportWeather());
    }

    /**
     * How long a dimension transfer's far side may take to reach the client — a deadline for a
     * discrete event, the same 200 ticks the poll it replaces was capped at.
     */
    private static final int DIM_LINK_BUDGET_TICKS = 200;

    /**
     * The client does NOT lerp weather itself in 1.12.2
     * ({@code WorldClient.updateWeather()} is an empty override) — the
     * client-visible ramp is the SERVER's lerp streamed one
     * {@code SPacketChangeGameState(7)} per tick to in-dim players. Poll
     * briefly so the test isn't flaky on the exact tick of the snapshot —
     * settling above {@code minStrength} confirms the rain packets actually
     * reach and apply client-side.
     *
     * <p>This one stays a POLL: a strength climbing towards 1.0 is a physical quantity converging
     * one server lerp step at a time, not a link production commits, and there is no event for a
     * vanilla game-state packet. What it no longer has to absorb is the crossing itself — that is
     * awaited as its own link above, so a red here can only be about the rain.</p>
     */
    private ClientPoll.Result<JsonObject> waitForClientRainStrengthAtLeast(float minStrength)
            throws Exception {
        // ClientPoll rather than a hand-rolled loop, and the difference is the SELF-REPORT: the
        // result carries whether the predicate ever held, how many of its iterations it spent and
        // what it last read, so a caller printing it says which of those it is complaining about.
        return ClientPoll.until(clientHarness.bot()::waitTicks,
                () -> clientHarness.bot().reportWeather(),
                report -> report.has("rainStrength")
                        && report.get("rainStrength").getAsFloat() >= minStrength,
                10, 20);
    }
}
