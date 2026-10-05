package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import dev.stannismod.stellurgy.test.DimWeather;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * direct cross-dim
 * isolation: rain set on planet A must NOT leak to planet B or the overworld,
 * and vice versa.
 *
 * Complementary to {@link WeatherBaselineTest}, which only checks the
 * "overworld &rarr; planets" direction. This test exercises the "planet &harr; planet"
 * and "planet &rarr; overworld" directions, which the B1 wrapper has to handle
 * symmetrically.
 *
 * Setup pattern mirrors {@code WeatherBaselineTest}: pre-stage a 2-planet
 * fixture XML in the harness workdir, start the harness, drive it via /stellurgytest.
 *
 * <p>One server for the class, booted once over the galaxy {@link Galaxy} declares.</p>
 */
@SeededWorld(PerDimensionWeatherIsolationTest.Galaxy.class)
public class PerDimensionWeatherIsolationTest extends AbstractSharedServerTest {

    private static final int FIXTURE_DIM_A = 9201;
    private static final int FIXTURE_DIM_B = 9202;


    /** The galaxy this class's one shared server boots over. */
    public static final class Galaxy implements WorldSeed {
        @Override
        public void seed(dev.stannismod.stellurgy.test.client.GameDirSeed seed) {
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<galaxy>\n"
                    + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                    + "          isBlackHole=\"false\" diskAngle=\"70\" "
                    + "          numPlanets=\"2\" numGasGiants=\"0\">\n"
                    + planetXml("PerDimPlanetA", FIXTURE_DIM_A)
                    + planetXml("PerDimPlanetB", FIXTURE_DIM_B)
                    + "    </star>\n"
                    + "</galaxy>\n";
            seed.planetDefs(xml, PerDimensionWeatherIsolationTest.class);
        }
    }

    private static String planetXml(String name, int dim) {
        return "        <planet name=\"" + name + "\" DIMID=\"" + dim + "\">\n"
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
                + "        </planet>\n";
    }

    @Test
    public void rainOnPlanetADoesNotLeakToBOrOverworld() throws Exception {

        // Clear everywhere first so the test starts from a known baseline.
        client().execute("stellurgytest weather set 0 clear 12000");
        client().execute("stellurgytest weather set " + FIXTURE_DIM_A + " clear 12000");
        client().execute("stellurgytest weather set " + FIXTURE_DIM_B + " clear 12000");

        // Rain on A only.
        String setA = String.join("\n",
                client().execute("stellurgytest weather set " + FIXTURE_DIM_A + " rain 12000"));
        assertTrue("set rain on A failed: " + setA, Reply.of(setA).ok());

        DimWeather wA = weather(FIXTURE_DIM_A);
        DimWeather wB = weather(FIXTURE_DIM_B);
        DimWeather w0 = weather(0);

        assertTrue("planet A should be raining after explicit set: " + wA.raw(), wA.raining);
        assertFalse("planet B must NOT be raining (rain set on A only): " + wB.raw(), wB.raining);
        assertFalse("overworld must NOT be raining (rain set on planet A only): " + w0.raw(),
                w0.raining);
        // Wrapper must actually be installed — otherwise the isolation above
        // could pass for the wrong reason (no propagation simply because we
        // changed nothing on the other dims yet).
        assertTrue("planet A WorldInfo class should be StellurgyDimensionWorldInfo: " + wA.raw(),
                wA.usesStellurgyWorldInfo());
        assertTrue("planet B WorldInfo class should be StellurgyDimensionWorldInfo: " + wB.raw(),
                wB.usesStellurgyWorldInfo());
    }

    @Test
    public void rainOnPlanetBDoesNotLeakToAOrOverworld() throws Exception {
        // The reverse direction — guards against a one-way leak bug where A
        // is properly wrapped but B silently writes to the overworld.

        client().execute("stellurgytest weather set 0 clear 12000");
        client().execute("stellurgytest weather set " + FIXTURE_DIM_A + " clear 12000");
        client().execute("stellurgytest weather set " + FIXTURE_DIM_B + " clear 12000");

        client().execute("stellurgytest weather set " + FIXTURE_DIM_B + " rain 12000");

        DimWeather wA = weather(FIXTURE_DIM_A);
        DimWeather wB = weather(FIXTURE_DIM_B);
        DimWeather w0 = weather(0);

        assertTrue("planet B should be raining after explicit set: " + wB.raw(), wB.raining);
        assertFalse("planet A must NOT be raining (rain set on B only): " + wA.raw(), wA.raining);
        assertFalse("overworld must NOT be raining (rain set on planet B only): " + w0.raw(),
                w0.raining);
    }

    @Test
    public void clearOnPlanetADoesNotClearB() throws Exception {
        // Symmetric to the rain test — clearing one planet must not clear the
        // other. Without the wrapper, /weather clear would propagate.

        // Rain on BOTH first.
        client().execute("stellurgytest weather set " + FIXTURE_DIM_A + " rain 12000");
        client().execute("stellurgytest weather set " + FIXTURE_DIM_B + " rain 12000");

        DimWeather beforeA = weather(FIXTURE_DIM_A);
        DimWeather beforeB = weather(FIXTURE_DIM_B);
        assertTrue("planet A must be raining as precondition: " + beforeA.raw(), beforeA.raining);
        assertTrue("planet B must be raining as precondition: " + beforeB.raw(), beforeB.raining);

        // Clear only A.
        client().execute("stellurgytest weather set " + FIXTURE_DIM_A + " clear 12000");

        DimWeather afterA = weather(FIXTURE_DIM_A);
        DimWeather afterB = weather(FIXTURE_DIM_B);

        assertFalse("planet A should be clear after explicit clear: " + afterA.raw(),
                afterA.raining);
        assertTrue("planet B must remain raining (clear set on A only): " + afterB.raw(),
                afterB.raining);
    }

    /** One world's sky, refusing the {@code world not loaded} reply and the wrong dimension. */
    private DimWeather weather(int dim) throws Exception {
        return DimWeather.forDim(cmd -> String.join("\n", client().execute(cmd)), dim)
                .requireDim(dim);
    }
}
