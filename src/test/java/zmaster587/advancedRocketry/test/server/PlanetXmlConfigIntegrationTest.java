package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.DimList;
import zmaster587.advancedRocketry.test.Reply;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * planet XML config integration test.
 *
 * Pre-writes a deterministic fixture {@code planetDefs.xml} into
 * {@code <workDir>/config/advRocketry/} BEFORE the harness boots, then asserts
 * that {@code /artest planet info <fixture-dim>} round-trips the values from
 * the XML.
 *
 * <p>Doesn't extend {@link AbstractHeadlessServerTest} because the standard
 * harness lifecycle pre-creates its workDir via {@code Files.createTempDirectory}
 * AFTER spawning. We need to write the XML BEFORE startup, so the harness is
 * managed manually via {@link RealDedicatedServerHarness#startWith}.</p>
 */
public class PlanetXmlConfigIntegrationTest {

    /** Dim id we declare in the fixture. Must be outside vanilla 0/-1/1 + AR's
     *  defaults (Sol=0, AR uses 2+ for first planet). 9001 is well clear. */
    private static final int FIXTURE_DIM = 9001;
    private static final String FIXTURE_PLANET_NAME = "ARTestPlanet";
    private static final int FIXTURE_GRAVITY_HUNDREDTHS = 75;          // 0.75 multiplier
    private static final int FIXTURE_ORBITAL_DISTANCE = 250;
    private static final int FIXTURE_ATM_DENSITY = 50;
    private static final int FIXTURE_ROTATIONAL_PERIOD = 16000;

    private Path workDir;
    private RealDedicatedServerHarness harness;

    @Before
    public void writeFixtureXml() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));

        workDir = Files.createTempDirectory("forge-server-planet-xml-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);

        String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<galaxy>\n" +
                "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" " +
                "          isBlackHole=\"false\" diskAngle=\"70\" " +
                "          numPlanets=\"1\" numGasGiants=\"0\">\n" +
                "        <planet name=\"" + FIXTURE_PLANET_NAME + "\" DIMID=\"" + FIXTURE_DIM + "\">\n" +
                "            <isKnown>true</isKnown>\n" +
                "            <fogColor>0.5,0.5,0.5</fogColor>\n" +
                "            <skyColor>0.4,0.6,0.9</skyColor>\n" +
                "            <gravitationalMultiplier>" + FIXTURE_GRAVITY_HUNDREDTHS + "</gravitationalMultiplier>\n" +
                "            <orbitalDistance>" + FIXTURE_ORBITAL_DISTANCE + "</orbitalDistance>\n" +
                "            <orbitalTheta>0</orbitalTheta>\n" +
                "            <orbitalPhi>0</orbitalPhi>\n" +
                "            <retrograde>false</retrograde>\n" +
                "            <averageTemperature>250</averageTemperature>\n" +
                "            <rotationalPeriod>" + FIXTURE_ROTATIONAL_PERIOD + "</rotationalPeriod>\n" +
                "            <atmosphereDensity>" + FIXTURE_ATM_DENSITY + "</atmosphereDensity>\n" +
                "            <generateCraters>false</generateCraters>\n" +
                "            <generateCaves>true</generateCaves>\n" +
                "            <generateVolcanos>false</generateVolcanos>\n" +
                "        </planet>\n" +
                "    </star>\n" +
                "</galaxy>\n";

        Files.write(arConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));
    }

    @After
    public void stopHarness() throws Exception {
        if (harness != null) harness.close();
    }

    @Test
    public void fixtureXmlRoundTripsThroughServerStart() throws Exception {
        harness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);

        // `holds` refuses a reply carrying no `arDimensions` of its own, so the malformed-reply
        // claim that used to stand here as a separate line is the same claim, made where it bites.
        DimList dimList = DimList.of(String.join("\n", harness.client().execute("artest dim list")));
        assertTrue("fixture dim " + FIXTURE_DIM + " not in arDimensions: " + dimList,
                dimList.holds(FIXTURE_DIM));

        String planetInfo = String.join("\n",
                harness.client().execute("artest planet info " + FIXTURE_DIM));
        assertTrue("planet info errored: " + planetInfo,
                !Reply.of(planetInfo).has("error"));

        // Five fields, read by name. As substring needles they were five renderings — the numeric
        // ones matched a PREFIX, so `"orbitalDistance":250` was satisfied by 2500, and `0.75`
        // depended on how gson chose to print the double that tick.
        Reply info = Reply.of("artest planet info", planetInfo);
        assertEquals("planet name did not round-trip: " + planetInfo,
                FIXTURE_PLANET_NAME, info.text("name"));
        assertEquals("orbitalDistance did not round-trip: " + planetInfo,
                FIXTURE_ORBITAL_DISTANCE, info.integer("orbitalDistance"));
        assertEquals("atmosphereDensity did not round-trip: " + planetInfo,
                FIXTURE_ATM_DENSITY, info.integer("atmosphereDensity"));
        assertEquals("rotationalPeriod did not round-trip: " + planetInfo,
                FIXTURE_ROTATIONAL_PERIOD, info.integer("rotationalPeriod"));
        assertEquals("gravity did not round-trip: " + planetInfo,
                FIXTURE_GRAVITY_HUNDREDTHS / 100.0, info.number("gravity"), 1e-9);
    }
}
