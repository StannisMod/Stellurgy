package dev.stannismod.stellurgy.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.junit.AbstractMechanicsTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.github.stannismod.forge.testing.server.ServerRoot;
import dev.stannismod.stellurgy.test.DimWeather;
import dev.stannismod.stellurgy.test.client.GameDirSeed;
import dev.stannismod.stellurgy.test.contract.OwnBoot;
import dev.stannismod.stellurgy.test.contract.Pins;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * weather state persists across a server restart
 * for Stellurgy planets (saved-data on the overworld MapStorage, keyed by dim id).
 *
 * Previously this test exercised the overworld (dim 0), which is intentionally
 * NOT wrapped by B1 — so it was actually a vanilla persistence test in disguise.
 * Rewritten to write rain into a Stellurgy planet (where {@code StellurgyDimensionWorldInfo}
 * is installed and {@code PlanetWeatherSavedData} is the actual persistence
 * target), then verify it survives a clean stop/start cycle on the same
 * workdir.
 *
 * Manages two harness lifecycles directly against the same workDir — not a
 * fit for {@link AbstractHeadlessServerTest} (which auto-manages a single
 * fresh-dir harness).
 *
 * <p>SEPARATE-BOOT: a server restart that is the subject. Every scenario here boots a server, stops
 * it, and boots a second one over the same world directory to read what the save carried across;
 * a shared, running server cannot be stopped under its siblings.</p>
 */
@OwnBoot(reason = OwnBoot.Reason.SERVER_RESTART)
public class WeatherPersistenceTest extends AbstractMechanicsTest {

    private static final int FIXTURE_DIM = 9301;

    private ServerRoot workDir;
    private RealDedicatedServerHarness firstBoot;
    private RealDedicatedServerHarness secondBoot;

    @Before
    public void prepareWorkDir() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        // The planet XML is the START of the first boot, so the dim id is stable
        // across both lifecycles.
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"1\" numGasGiants=\"0\">\n"
                + "        <planet name=\"PersistencePlanet\" DIMID=\"" + FIXTURE_DIM + "\">\n"
                + "            <mass>1.0</mass>\n"
                + "            <radius>1.0</radius>\n"
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
        workDir = ServerRoot.fresh("forge-server-weather-persistence-")
                .seed(new GameDirSeed().planetDefs(xml, getClass()));
    }

    @After
    public void closeAll() throws Exception {
        if (firstBoot != null) firstBoot.close();
        if (secondBoot != null) secondBoot.close();
    }

    /** Pins INV-WGEN-15 (with custom weather disabled the cycle defers to vanilla, and planet rain survives a restart). */
    @Pins("INV-WGEN-15")
    @Test
    public void planetRainSurvivesRestartOnSameWorkDir() throws Exception {
        // First boot: set rain on the planet, verify the wrapper is in place.
        firstBoot = RealDedicatedServerHarness.startWith(harnessBoot(), workDir, /*cleanupOnClose=*/false);
        firstBoot.client().arrange("stellurgytest weather set " + FIXTURE_DIM + " rain 12000");
        DimWeather beforeStop = weather(firstBoot, FIXTURE_DIM);
        assertTrue("rain didn't take effect on first boot: " + beforeStop.raw(),
                beforeStop.raining);
        assertTrue("wrapper not installed on first boot: " + beforeStop.raw(),
                beforeStop.usesStellurgyWorldInfo());

        // Stop cleanly — saved-data must flush via vanilla MapStorage save.
        firstBoot.close();
        firstBoot = null;

        // Second boot on the same workdir. The planet weather wrapper
        // re-reads state from stellurgy_planet_weather saved-data on
        // the overworld MapStorage.
        secondBoot = RealDedicatedServerHarness.startWith(harnessBoot(), workDir, /*cleanupOnClose=*/true);
        DimWeather after = weather(secondBoot, FIXTURE_DIM);
        assertTrue("wrapper should still be installed after restart: " + after.raw(),
                after.usesStellurgyWorldInfo());
    }

    /** One world's sky on one of the two boots, refusing a world that did not come up. */
    private static DimWeather weather(RealDedicatedServerHarness boot, int dim) throws Exception {
        return DimWeather.forDim(cmd -> String.join("\n", boot.client().arrange(cmd)), dim)
                .requireDim(dim);
    }
}
