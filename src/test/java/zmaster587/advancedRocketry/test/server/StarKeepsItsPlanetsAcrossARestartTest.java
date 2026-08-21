package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Does a system still contain its planets after the world is loaded rather than created?
 *
 * <p>A body carries a {@code starId} from the moment it is parsed, but it enters its star's own
 * planet map only when {@code setStar} resolves a REGISTERED star — and the XML loader cannot do
 * that, as its own comment says ("Star may not be registered at this time, use ID version
 * instead"). Something later has to finish the job, and on a FRESH world something does. This test
 * asks whether anything does on a LOADED one.</p>
 *
 * <p><b>Why the star's map is the thing asserted and not the dimension list.</b> Everything that
 * answers "what is in this system" walks {@code star.getPlanets()} — the system body list, the cell
 * sky, the navigation crystal's seeding — while the dimensions themselves stay registered and
 * perfectly reachable. So the failure this pins does not look like missing worlds; it looks like a
 * sky with three bodies in it and a first crystal that seeds nothing, which is exactly how it was
 * reported from play.</p>
 *
 * <p><b>And it is not only a read.</b> {@code XMLPlanetLoader.writeXML} builds the saved catalogue by
 * walking the same map, so a world that loads with an empty one writes an empty one back. The
 * assertion below therefore also guards the file: what the second boot holds is what the third boot
 * would inherit.</p>
 */
public class StarKeepsItsPlanetsAcrossARestartTest {

    private static final Pattern PLANETS = Pattern.compile("\"planets\":(\\d+)");
    private static final Pattern NAMING = Pattern.compile("\"dimsNamingThisStar\":(\\d+)");
    private static final Pattern RETINUE = Pattern.compile("\"maxRetinue\":(-?\\d+)");

    /** Two authored planets under Sol, well clear of the ids the stock world uses. */
    private static final int DIM_A = 480, DIM_B = 481;

    private Path workDir;
    private RealDedicatedServerHarness firstBoot;
    private RealDedicatedServerHarness secondBoot;

    @Before
    public void prepareWorkDir() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        workDir = Files.createTempDirectory("forge-server-star-planets-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\""
                + "          isBlackHole=\"false\" diskAngle=\"70\""
                + "          numPlanets=\"4\" numGasGiants=\"1\">\n"
                + planetXml("Probe Alpha", DIM_A)
                + planetXml("Probe Beta", DIM_B)
                + "    </star>\n"
                + "</galaxy>\n";
        Files.write(arConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));
    }

    @After
    public void closeAll() throws Exception {
        Exception deferred = null;
        if (firstBoot != null) {
            try {
                firstBoot.close();
            } catch (Exception e) {
                deferred = e;
            }
        }
        if (secondBoot != null) {
            secondBoot.close();
        }
        if (deferred != null) {
            throw deferred;
        }
    }

    private static String planetXml(String name, int dim) {
        return "        <planet name=\"" + name + "\" DIMID=\"" + dim + "\">\n"
                + "            <isKnown>true</isKnown>\n"
                + "            <fogColor>0.5,0.5,0.5</fogColor>\n"
                + "            <skyColor>0.4,0.6,0.9</skyColor>\n"
                + "            <gravitationalMultiplier>100</gravitationalMultiplier>\n"
                + "            <orbitalDistance>" + (100 + dim - DIM_A * 0) + "</orbitalDistance>\n"
                + "            <orbitalTheta>" + (dim - DIM_A) + "</orbitalTheta>\n"
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

    private static int intField(Pattern p, String json, String what) {
        Matcher m = p.matcher(json);
        assertTrue("probe answered without " + what + ": " + json, m.find());
        return Integer.parseInt(m.group(1));
    }

    private static String starGet(RealDedicatedServerHarness boot) throws Exception {
        return String.join("\n", boot.client().execute("artest star get 0"));
    }

    /**
     * This test fails if production breaks the contract that a system holds the same bodies after a
     * world is loaded as it did when the world was created — so the sky, the body list and a first
     * navigation crystal describe the same system on the second boot as on the first.
     */
    @Test
    public void aStarStillHoldsItsPlanetsWhenTheWorldIsLoadedRatherThanCreated() throws Exception {
        firstBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/false);
        String fresh = starGet(firstBoot);
        int freshPlanets = intField(PLANETS, fresh, "planets");
        int freshNaming = intField(NAMING, fresh, "dimsNamingThisStar");

        // The ARRANGEMENT: the authored bodies really did reach the star on a world that was CREATED.
        // Without this the comparison below could be satisfied by a fresh boot that was already empty,
        // which is the vacuous pass this test exists to avoid.
        assertTrue("the authored planets must be attached on a FRESH world (planets=" + freshPlanets
                + "): " + fresh, freshPlanets >= 2);

        firstBoot.close();
        firstBoot = null;

        secondBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/false);
        String loaded = starGet(secondBoot);
        int loadedPlanets = intField(PLANETS, loaded, "planets");
        int loadedNaming = intField(NAMING, loaded, "dimsNamingThisStar");

        // The dimensions are expected to survive either way — this is NOT a test about losing worlds,
        // and asserting it separately is what keeps the failure legible: an equal naming count beside
        // an unequal attached count says "registered but not attached" and nothing else.
        assertEquals("the same dimensions must still name this star after a reload"
                + " (fresh=" + fresh + " loaded=" + loaded + ")", freshNaming, loadedNaming);

        // The RETINUE SIZE is a separate survival question and the one that was actually broken:
        // a system's procedural content is generated only while this number is positive, so a reload
        // that starts it at zero leaves a sky holding nothing but what was explicitly authored — a
        // star and a home planet — while every dimension is still registered and every attach intact.
        // Asserted after the attach checks because it is the SUBTLER failure: the counts above can
        // agree perfectly while this one has silently gone to zero.
        int freshRetinue = intField(RETINUE, fresh, "maxRetinue");
        int loadedRetinue = intField(RETINUE, loaded, "maxRetinue");
        assertEquals("the authored retinue size must reach the star on a FRESH world"
                + " (numPlanets=4 + numGasGiants=1): " + fresh, 5, freshRetinue);
        assertEquals("a system's retinue size must survive a reload — the saved catalogue carries it,"
                + " and the load path restores it onto the star that is already registered"
                + " (fresh=" + fresh + " loaded=" + loaded + ")", freshRetinue, loadedRetinue);

        assertEquals("a star must hold the same planets after a reload as it did on creation —"
                + " everything that answers \"what is in this system\" walks that map, and"
                + " writeXML rebuilds the saved catalogue from it"
                + " (fresh=" + fresh + " loaded=" + loaded + ")", freshPlanets, loadedPlanets);
    }
}
