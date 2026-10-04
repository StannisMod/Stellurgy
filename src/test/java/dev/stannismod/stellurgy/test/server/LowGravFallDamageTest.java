package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.client.GameDirSeed;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@code PlanetEventHandler.fallEvent} fall-distance scaling — server tier.
 * Relabeled down the pyramid from the old client-harness
 * {@code LowGravFallDamageE2ETest} the contract
 * (LivingFallEvent.distance × gravity multiplier on IPlanetaryProvider dims,
 * untouched elsewhere) is server-authoritative event-handler logic, and the
 * old client test drove it exclusively through the {@code try-fall} probe
 * anyway. Player supply: {@code ensure-fake}.
 *
 * <p>One server for the class, over the galaxy {@link Galaxy} declares; both scenarios only station
 * the fake player and read one posted event.</p>
 */
@SeededWorld(LowGravFallDamageTest.Galaxy.class)
public class LowGravFallDamageTest extends AbstractSharedServerTest {

    private static final int DIM_LOW_GRAV = 9701;
    private static final String IS_PLANETARY = "isPlanetaryProvider";
    private static final String INPUT_DIST = "inputDistance";
    private static final String RESULT_DIST = "resultDistance";
    private static final String GRAVITY = "gravityMultiplier";

    /** One low-gravity planet at gravity multiplier 17 (0.17 g). */
    public static final class Galaxy implements WorldSeed {
        @Override
        public void seed(GameDirSeed seed) {
            seed.planetDefs("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<galaxy>\n"
                    + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                    + "          isBlackHole=\"false\" diskAngle=\"70\" "
                    + "          numPlanets=\"1\" numGasGiants=\"0\">\n"
                    + "        <planet name=\"LowGravPlanet\" DIMID=\"" + DIM_LOW_GRAV + "\">\n"
                    + "            <isKnown>true</isKnown>\n"
                    + "            <fogColor>0.5,0.5,0.5</fogColor>\n"
                    + "            <skyColor>0.4,0.6,0.9</skyColor>\n"
                    + "            <gravitationalMultiplier>17</gravitationalMultiplier>\n"
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
                    + "</galaxy>\n", LowGravFallDamageTest.class);
        }
    }

    /**
     * Stations the fake player in {@code dim}. Nothing is waited for after it: {@code ensure-fake}
     * initialises the dimension and moves the player into it on the server thread before it
     * replies, and {@code try-fall} posts its event synchronously against that same player — the
     * next command reads the write itself.
     */
    private void stationFake(int dim) throws Exception {
        String fake = exec("stellurgytest player ensure-fake " + dim + " 8.5 120 8.5");
        assertTrue("ensure-fake must succeed: " + fake, Reply.of(fake).ok());
    }

    /** Overworld: not an IPlanetaryProvider &rarr; distance untouched. */
    @Test
    public void overworldDoesNotScaleFallDistance() throws Exception {
        stationFake(0);
        String resp = exec("stellurgytest player try-fall 20");
        assertEquals("overworld must NOT be an IPlanetaryProvider; " + resp,
                false, boolField(IS_PLANETARY, resp));
        assertEquals("overworld fall distance must be unchanged by the Stellurgy handler; " + resp,
                doubleField(INPUT_DIST, resp), doubleField(RESULT_DIST, resp), 0.001);
    }

    /** Low-grav Stellurgy dim: distance × multiplier (17 &rarr; 0.17). */
    @Test
    public void lowGravDimScalesFallDistanceByGravityMultiplier() throws Exception {
        stationFake(DIM_LOW_GRAV);
        String resp = exec("stellurgytest player try-fall 20");
        assertEquals("low-grav Stellurgy dim must report as IPlanetaryProvider; " + resp,
                true, boolField(IS_PLANETARY, resp));
        double input = doubleField(INPUT_DIST, resp);
        double result = doubleField(RESULT_DIST, resp);
        double gravity = doubleField(GRAVITY, resp);
        assertEquals("gravity multiplier must be ~0.17; " + resp, 0.17, gravity, 0.02);
        assertEquals("low-grav Stellurgy dim must scale fall distance by gravity; " + resp,
                input * gravity, result, 0.05);
        assertTrue("scaled distance must be strictly less than input; input=" + input
                + " result=" + result, result < input);
    }

    private static boolean boolField(String field, String src) {
        Reply reply = Reply.of(src);
        assertTrue("field `" + field + "` not found in: " + src, reply.has(field));
        return reply.bool(field);
    }

    private static double doubleField(String field, String src) {
        double value = Reply.of(src).number(field);
        return value;
    }
}
