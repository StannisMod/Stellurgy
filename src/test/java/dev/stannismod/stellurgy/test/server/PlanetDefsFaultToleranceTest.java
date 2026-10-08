package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.DimList;
import dev.stannismod.stellurgy.test.Reply;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
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
 * Server-level regression guard for the tolerant planetDefs.xml loading
 * (dercodeKoenig/AdvancedRocketry#77).
 *
 * <p>The original report: a planetDefs.xml referencing content from a mod
 * that isn't installed crashed world creation, and the crash killed the JVM
 * via a silent {@code FMLCommonHandler.exitJava} — no crash report, the
 * window just closed. The parser-level guards are pinned in
 * {@code XMLPlanetLoaderTest} (reserved-but-empty ore name, per-planet
 * isolation); what only a real dedicated server can prove is the headline
 * behaviour: <b>the server still boots</b> with a dirty file, the malformed
 * planet is skipped, and the well-formed planets around it survive.</p>
 *
 * <p>The malformed trigger mirrors the integration fixture: a non-numeric
 * {@code <rainMarker>} throws deep inside {@code readPlanetFromNode}, which
 * the per-planet isolation must catch-and-skip.</p>
 *
 * <p>One server for the class, booted once over the galaxy {@link Galaxy} declares.</p>
 */
@SeededWorld(PlanetDefsFaultToleranceTest.Galaxy.class)
public class PlanetDefsFaultToleranceTest extends AbstractSharedServerTest {

    private static final int GOOD_DIM = 9401;
    private static final int BAD_DIM = 9402;


    /** The galaxy this class's one shared server boots over. */
    public static final class Galaxy implements WorldSeed {
        @Override
        public void seed(dev.stannismod.stellurgy.test.client.GameDirSeed seed) {
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<galaxy>\n"
                    + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                    + "          isBlackHole=\"false\" diskAngle=\"70\" "
                    + "          numPlanets=\"2\" numGasGiants=\"0\">\n"
                    + planetXml("GoodPlanet", GOOD_DIM,
                            "")
                    + planetXml("BadWeatherPlanet", BAD_DIM,
                            "            <rainMarker>NOT_A_NUMBER</rainMarker>\n")
                    + "    </star>\n"
                    + "</galaxy>\n";
            seed.planetDefs(xml, PlanetDefsFaultToleranceTest.class);
        }
    }

    private static String planetXml(String name, int dim, String extraElements) {
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
                + extraElements
                + "            <generateCraters>false</generateCraters>\n"
                + "            <generateCaves>true</generateCaves>\n"
                + "            <generateVolcanos>false</generateVolcanos>\n"
                + "        </planet>\n";
    }

    /** Pins INV-XML-01 (a malformed planet definition is skipped, not fatal). */
    @Test
    public void serverBootsWithMalformedPlanetSkipped() throws Exception {
        // The assertion that matters most is implicit in this line: before the
        // #77 fix a malformed planet killed the JVM during startup (silent
        // exitJava), so startWith() would fail with "server process exited
        // before becoming ready".

        // Membership of a SET of integers, asked of one. It used to be asked of the rendering —
        // `dimList.contains("9402")` — and those digits match anywhere in the blob: the negative
        // claim below would fail for a tick time carrying them, and the positive one above would
        // pass with the dimension absent as long as something printed 94010 or 19401.
        DimList dimList = DimList.of(String.join("\n", client().execute("stellurgytest dim list")));
        assertTrue("well-formed planet must survive a dirty planetDefs.xml: " + dimList,
                dimList.holds(GOOD_DIM));
        assertFalse("malformed planet must be skipped, not registered: " + dimList,
                dimList.holds(BAD_DIM));

        // The good planet is fully functional, not just listed.
        String info = String.join("\n",
                client().execute("stellurgytest planet info " + GOOD_DIM));
        assertTrue("good planet must round-trip its config: " + info,
                "GoodPlanet".equals(Reply.of(info).text("name")));
    }
}
