package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.DimInfo;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.StationInfo;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * full persistence/restart smoke.
 *
 * Boot 1 creates a station + satellite + mutates Earth atmosphere density.
 * Boot 2 (same workDir) verifies every mutation survived save/load + registry
 * counts are stable.
 */
public class PersistenceRestartSmokeTest {

    /** The station's own id. The regex this replaces anchored on the NEXT field so as not
     *  to match some other `id`; reading by name needs no such anchor. */
    private static final String STATION_ID = "id";
    private static final String SAT_ID_FALLBACK = "id";
    private static final String ATM_DENSITY = "atmosphereDensity";

    private Path workDir;
    private RealDedicatedServerHarness firstBoot;
    private RealDedicatedServerHarness secondBoot;

    @Before
    public void prepareWorkDir() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        workDir = Files.createTempDirectory("forge-server-persistence-restart-");
    }

    @After
    public void closeAll() throws Exception {
        if (firstBoot != null) firstBoot.close();
        if (secondBoot != null) secondBoot.close();
    }

    @Test
    public void stationAndSatelliteAndDensitySurviveRestart() throws Exception {
        long stationId;
        long satelliteId;
        int targetDensity = 33;
        int[] firstCounts;

        firstBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/false);

        String regSummary = String.join("\n", firstBoot.client().execute("artest registry summary"));
        firstCounts = extractCounts(regSummary, "blocks", "items", "entities", "biomes");
        assertTrue("first boot registry summary malformed: " + regSummary, firstCounts != null);

        // Mutation A: station orbiting Earth.
        String createStation = String.join("\n", firstBoot.client().execute("artest station create 0"));
        Reply created = Reply.of("artest station create", createStation);
        assertTrue("could not extract station id: " + createStation, created.has(STATION_ID));
        stationId = created.integer(STATION_ID);

        // Mutation B: satellite on Earth.
        String createSat = String.join("\n", firstBoot.client().execute(
                "artest satellite create 0 mass 300 6000 2048"));
        Reply satReply = Reply.of(createSat);
        assertTrue("could not extract satellite id: " + createSat, satReply.has(SAT_ID_FALLBACK));
        satelliteId = Long.parseLong(satReply.text(SAT_ID_FALLBACK));

        // Mutation C: atmosphere density.
        firstBoot.client().execute("artest atmosphere set-density 0 " + targetDensity);

        firstBoot.close();
        firstBoot = null;

        secondBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);

        String secondSummary = String.join("\n", secondBoot.client().execute("artest registry summary"));
        int[] secondCounts = extractCounts(secondSummary, "blocks", "items", "entities", "biomes");
        assertTrue("second boot registry summary malformed: " + secondSummary, secondCounts != null);
        for (int i = 0; i < firstCounts.length; i++) {
            assertEquals("registry count mismatch at idx " + i,
                    firstCounts[i], secondCounts[i]);
        }

        DimInfo dimInfo = DimInfo.forDim(
                cmd -> String.join("\n", secondBoot.client().execute(cmd)), 0);
        assertTrue("Earth lost AR-managed status after restart: " + dimInfo.raw(),
                dimInfo.arPlanet);

        String stations = String.join("\n", secondBoot.client().execute("artest station list"));
        Reply.of(stations).element("stations", "id", String.valueOf(stationId));
        StationInfo stationInfo = StationInfo.byId(
                cmd -> String.join("\n", secondBoot.client().execute(cmd)), (int) stationId);
        assertEquals("station's orbitingPlanetId did not survive: " + stationInfo.raw(),
                0, stationInfo.orbitingPlanetId);

        String sats = String.join("\n", secondBoot.client().execute("artest satellite list 0"));
        Reply.of(sats).element("satellites", "id", String.valueOf(satelliteId));
        String satInfo = String.join("\n",
                secondBoot.client().execute("artest satellite info 0 " + satelliteId));
        assertTrue("satellite type did not survive restart: " + satInfo,
                "mass".equals(Reply.of(satInfo).text("type")));

        String planet = String.join("\n", secondBoot.client().execute("artest planet info 0"));
        Reply amReply = Reply.of(planet);
        assertTrue("planet info missing atmosphereDensity: " + planet, amReply.has(ATM_DENSITY));
        assertEquals("atmosphereDensity did not survive",
                targetDensity, Integer.parseInt(amReply.text(ATM_DENSITY)));
    }

    private static int[] extractCounts(String json, String... keys) {
        int[] result = new int[keys.length];
        for (int i = 0; i < keys.length; i++) {
            String needle = "\"" + keys[i] + "\":";
            int idx = json.indexOf(needle);
            if (idx < 0) return null;
            int start = idx + needle.length();
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
            try {
                result[i] = Integer.parseInt(json.substring(start, end));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return result;
    }
}
