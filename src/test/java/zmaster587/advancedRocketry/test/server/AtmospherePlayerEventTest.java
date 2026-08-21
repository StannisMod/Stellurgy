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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the per-entity atmosphere gate answers for a player, and that the answer is the one belonging
 * to the dimension he is STANDING IN — server tier.
 *
 * <p>These three used to assert the shape of a per-player cache: that an AR dim populated it, that a
 * dimension change cleared it. That cache is gone — it existed only so an edge-triggered sync packet
 * could compare against the previous answer, and the sync is periodic now — and asserting its
 * bookkeeping was pinning an implementation detail in the first place. What a player can actually
 * feel is the resolution itself, so that is what is asserted: the gate answers his current
 * dimension's air, and nothing of the dimension he left survives the move.</p>
 *
 * <p>Player supply: {@code ensure-fake} (a cross-dim move fires the same event Forge's transfer
 * fires); {@code tick-living} supplies the per-tick {@code LivingUpdateEvent} cadence
 * {@code AtmosphereHandler.onTick} subscribes to.</p>
 */
public class AtmospherePlayerEventTest {

    private static final int DIM_VAC = 9411;
    private static final int DIM_AIR = 9412;

    private static final Pattern PLAYER_ATMOS = Pattern.compile("\"atmosphere\":\"([^\"]*)\"");
    private static final Pattern PLAYER_BREATHABLE = Pattern.compile("\"breathable\":(true|false)");

    private Path workDir;
    private RealDedicatedServerHarness harness;

    @Before
    public void startServer() throws Exception {
        Assume.assumeTrue("Server harness disabled",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        workDir = Files.createTempDirectory("forge-server-atm-player-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"2\" numGasGiants=\"0\">\n"
                + planetXml("VacuumPlanet", DIM_VAC, 0)
                + planetXml("AirPlanet", DIM_AIR, 100)
                + "    </star>\n"
                + "</galaxy>\n";
        Files.write(arConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));
        harness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);
    }

    private static String planetXml(String name, int dim, int atmosDensity) {
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
                + "            <atmosphereDensity>" + atmosDensity + "</atmosphereDensity>\n"
                + "            <generateCraters>false</generateCraters>\n"
                + "            <generateCaves>true</generateCaves>\n"
                + "            <generateVolcanos>false</generateVolcanos>\n"
                + "        </planet>\n";
    }

    @After
    public void stopServer() throws Exception {
        if (harness != null) harness.close();
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", harness.client().execute(cmd));
    }

    /** Stations the fake player in {@code dim} and ticks it {@code ticks} times. */
    private void enterDimAndTick(int dim, int ticks) throws Exception {
        String fake = exec("artest player ensure-fake " + dim + " 8.5 120 8.5");
        assertTrue("ensure-fake must succeed: " + fake, fake.contains("\"ok\":true"));
        assertTrue(exec("artest player tick-living " + ticks).contains("\"ok\":true"));
        // Off-thread wait — the server free-runs the ticks meanwhile.
        Thread.sleep(ticks * 50L + 500L);
    }

    private String field(Pattern p, String src) {
        Matcher m = p.matcher(src);
        assertTrue("field " + p.pattern() + " missing in: " + src, m.find());
        return m.group(1);
    }

    /** The overworld is breathable, and the gate says so for a player standing in it. */
    @Test
    public void aPlayerInTheOverworldResolvesBreathableAir() throws Exception {
        enterDimAndTick(0, 10);
        String resp = exec("artest atmosphere for-player");
        assertFalse("the gate must answer SOMETHING for a player in the overworld: " + resp,
                field(PLAYER_ATMOS, resp).isEmpty());
        assertEquals("the overworld must resolve as breathable for a player standing in it: "
                + resp, "true", field(PLAYER_BREATHABLE, resp));
    }

    /** An airless AR planet resolves as unbreathable for a player standing on it. */
    @Test
    public void aPlayerOnAnAirlessPlanetResolvesUnbreathableAir() throws Exception {
        enterDimAndTick(DIM_VAC, 40);
        String resp = exec("artest atmosphere for-player");
        assertFalse("the gate must answer SOMETHING for a player on an AR planet: " + resp,
                field(PLAYER_ATMOS, resp).isEmpty());
        assertEquals("a planet declared with zero atmosphere must resolve as unbreathable for a "
                + "player standing on it: " + resp, "false", field(PLAYER_BREATHABLE, resp));
    }

    /**
     * The answer follows the player across a dimension change: nothing of the airless planet he
     * left survives into the breathable one he arrives on.
     */
    @Test
    public void aDimChangeMakesAPlayerResolveTheNewDimsAir() throws Exception {
        enterDimAndTick(DIM_VAC, 40);
        String onVacuum = exec("artest atmosphere for-player");
        String atmoVac = field(PLAYER_ATMOS, onVacuum);
        assertEquals("the airless planet must resolve as unbreathable before the move: "
                + onVacuum, "false", field(PLAYER_BREATHABLE, onVacuum));

        enterDimAndTick(DIM_AIR, 40);
        String onAir = exec("artest atmosphere for-player");
        String atmoAir = field(PLAYER_ATMOS, onAir);
        assertEquals("the breathable planet must resolve as breathable after the move: " + onAir,
                "true", field(PLAYER_BREATHABLE, onAir));
        assertFalse("the airless planet's atmosphere must not survive the move; before=" + atmoVac
                + " after=" + atmoAir, atmoVac.equals(atmoAir));
    }
}
