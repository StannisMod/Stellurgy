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
        // No "malformed?" assertion beside this any more: `extractCounts` refuses by field name and
        // prints the reply, so a null it could once return is now unreachable — and a check that
        // cannot fire reads as protection that is not there.
        firstCounts = extractCounts(regSummary, "blocks", "items", "entities", "biomes");

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

    /**
     * The registry counts this reply reports, refusing by NAME when one is missing.
     *
     * <p>It used to find each key by {@code indexOf("\"" + key + "\":")} and walk the digits after
     * it, answering {@code null} for the whole array when any one key was absent. The callers then
     * asserted "summary malformed" — one sentence for four fields, naming none of them, and the
     * same sentence for a reply that was not a reply at all. {@code Reply.integer} names the field
     * it could not find and prints what it was given.</p>
     */
    private static int[] extractCounts(String json, String... keys) {
        Reply reply = Reply.of("artest registry summary", json);
        int[] result = new int[keys.length];
        for (int i = 0; i < keys.length; i++) {
            result[i] = reply.integer(keys[i]);
        }
        return result;
    }

}
