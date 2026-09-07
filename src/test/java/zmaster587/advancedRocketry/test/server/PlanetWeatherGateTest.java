package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import zmaster587.advancedRocketry.test.GameTicks;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/**
 * planet weather compatibility gates (feature/better_weather).
 *
 * <p>Pins two player-visible contracts of {@code WorldProviderPlanet.updateWeather}:</p>
 * <ol>
 *   <li><b>Atmosphere gate.</b> A planet whose atmosphere is thinner than
 *       {@code minAtmosphereDensityForRain} (default 75) must NOT rain, even with
 *       {@code rainMarker=1} ("always rain"). The same marker on a thick-atmosphere
 *       planet DOES rain — the contrast proves the gate, not a dead tick.</li>
 *   <li><b>Thunder requires rain.</b> {@code thunderMarker=1} combined with
 *       {@code rainMarker=-1} must leave the planet not thundering — vanilla
 *       couples thunder to rain and AR must not create a dry storm.</li>
 * </ol>
 *
 * <p>All three fixture planets keep a non-default marker so
 * {@code usesCustomWorldInfo()} engages the custom cycle; the live state is read
 * back through {@code artest weather get}.</p>
 */
public class PlanetWeatherGateTest {

    /**
     * Ticks the weather cycle is given to apply a marker.
     *
     * <p>25 = the five rounds of five the previous helper spent unconditionally, kept so this
     * change moves the FORM of the wait and not its size. It is a DEADLINE for the positive read
     * and a WINDOW for the negative ones; neither is a measured figure for how long
     * {@code updateWeather} actually needs after a mid-test {@code initDimension}, and nothing in
     * this file has ever established that.</p>
     */
    private static final int SETTLE_BUDGET_TICKS = 25;

    private static final int DIM_THIN_RAIN   = 9111; // density 10, rainMarker 1   -> must stay clear
    private static final int DIM_THICK_RAIN  = 9112; // density 100, rainMarker 1  -> must rain
    private static final int DIM_DRY_THUNDER = 9113; // density 100, thunder 1 / rain -1 -> no thunder

    private Path workDir;
    private RealDedicatedServerHarness harness;

    @Before
    public void writeFixture() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));

        workDir = Files.createTempDirectory("forge-server-weather-gate-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"3\" numGasGiants=\"0\">\n"
                + planetXml("ThinRainPlanet",  DIM_THIN_RAIN,   /*density*/ 10,  /*rainMarker*/ 1,  /*thunderMarker*/ 0)
                + planetXml("ThickRainPlanet", DIM_THICK_RAIN,  /*density*/ 100, /*rainMarker*/ 1,  /*thunderMarker*/ 0)
                + planetXml("DryThunderPlanet", DIM_DRY_THUNDER, /*density*/ 100, /*rainMarker*/ -1, /*thunderMarker*/ 1)
                + "    </star>\n"
                + "</galaxy>\n";
        Files.write(arConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String planetXml(String name, int dim, int density, int rainMarker, int thunderMarker) {
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
                + "            <atmosphereDensity>" + density + "</atmosphereDensity>\n"
                + "            <rainMarker>" + rainMarker + "</rainMarker>\n"
                + "            <thunderMarker>" + thunderMarker + "</thunderMarker>\n"
                + "            <generateCraters>false</generateCraters>\n"
                + "            <generateCaves>true</generateCaves>\n"
                + "            <generateVolcanos>false</generateVolcanos>\n"
                + "        </planet>\n";
    }

    @After
    public void stopHarness() throws Exception {
        if (harness != null) harness.close();
    }

    @Test
    public void atmosphereGatesRainAndThunderRequiresRain() throws Exception {
        harness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);

        String dimList = String.join("\n", harness.client().execute("artest dim list"));
        for (int dim : new int[]{DIM_THIN_RAIN, DIM_THICK_RAIN, DIM_DRY_THUNDER}) {
            assertTrue("fixture dim " + dim + " not registered: " + dimList,
                    dimList.contains(String.valueOf(dim)));
        }

        // Read the live state of each planet. The three claims are not the same SHAPE, so they are
        // not read the same way: the thick planet MUST reach rain, which is a state to wait for and
        // to stop waiting at; the other two must never reach it, which nothing can confirm early.
        String thick  = weatherOnce(DIM_THICK_RAIN, "\"isRaining\":true");
        String thin   = weatherAfterWindow(DIM_THIN_RAIN);
        String dry    = weatherAfterWindow(DIM_DRY_THUNDER);

        // Contrast: same rainMarker=1, opposite atmosphere -> opposite rain state.
        assertTrue("thick-atmosphere planet with rainMarker=1 must rain (gate baseline): " + thick,
                thick.contains("\"isRaining\":true"));
        assertTrue("thin-atmosphere planet must stay clear despite rainMarker=1 "
                        + "(atmosphere gate): " + thin,
                thin.contains("\"isRaining\":false"));

        // Thunder cannot exist without rain: dry planet (rainMarker=-1) must not thunder.
        assertTrue("dry planet (rainMarker=-1) must not rain: " + dry,
                dry.contains("\"isRaining\":false"));
        assertTrue("thunderMarker=1 with no rain must NOT thunder (vanilla couples them): " + dry,
                dry.contains("\"isThundering\":false"));
    }

    /**
     * The live weather of {@code dim}, once {@code wanted} appears in the reply or the budget runs
     * out — the POSITIVE form, for a state the cycle is supposed to reach.
     *
     * <p>A single helper used to serve all three planets by reading five times with a fixed advance
     * between reads, testing nothing. Its own javadoc named the condition — "so the server has
     * ticked {@code updateWeather} at least once" — and the code never asked it, so a planet that
     * had rained on the first read still paid the whole window.</p>
     *
     * <p><b>The first read comes before any advancing, and that order is load-bearing.</b>
     * {@code artest weather get} is not a read: it pins the dimension and calls
     * {@code initDimension} before answering, so it is what makes the world exist and tick. A
     * version that advanced first would be ticking a world nobody had constructed.</p>
     */
    private String weatherOnce(int dim, String wanted) throws Exception {
        String[] last = {String.join("\n", harness.client().execute("artest weather get " + dim))};
        GameTicks.until(harness.client(), GameTicks.server(), SETTLE_BUDGET_TICKS, () -> {
            last[0] = String.join("\n", harness.client().execute("artest weather get " + dim));
            return last[0].contains(wanted);
        });
        return last[0];
    }

    /**
     * The live weather of {@code dim} after the cycle has been given a full window to act — the
     * NEGATIVE form, for a claim that something must NOT happen.
     *
     * <p>An absence has no event to wait for and no condition that can exit early: the only way to
     * be wrong about "it never rained" is to look too soon, so this one spends its whole budget on
     * purpose. That is the difference between the two helpers, and it is why there are two.</p>
     */
    private String weatherAfterWindow(int dim) throws Exception {
        // The constructing read: its VALUE is discarded, its side effect is the point — this is
        // what pins the dimension and calls initDimension, so the window below ticks a real world.
        harness.client().execute("artest weather get " + dim);
        GameTicks.advance(harness.client(), GameTicks.server(), SETTLE_BUDGET_TICKS);
        return String.join("\n", harness.client().execute("artest weather get " + dim));
    }
}
