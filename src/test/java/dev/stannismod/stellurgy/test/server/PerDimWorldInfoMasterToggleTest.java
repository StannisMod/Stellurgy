package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.DimWeather;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.client.GameDirSeed;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Disableability contract for the {@code perDimWorldInfo} MASTER switch
 * (the single gate over Stellurgy's per-dimension WorldInfo subsystem: per-planet
 * weather + per-planet time/sleep + wrapper install).
 *
 * <p>Two observable contracts, both pinned by lazily loading a planet under a
 * specific config and reading the probe's named {@code worldInfoClass} field.
 * The flag is flipped at runtime BEFORE the fixture dim is ever loaded — wrapping
 * is decided at dim load and is sticky for the dim's lifetime, so the load order is
 * what makes each case deterministic.</p>
 *
 * <ul>
 *   <li><b>OFF &rarr; vanilla.</b> With {@code perDimWorldInfo=false}, a freshly
 *       loaded planet must keep the vanilla shared-overworld WorldInfo — NO
 *       {@code StellurgyDimensionWorldInfo} wrapper. Fails if the master gate in
 *       {@code PlanetWeatherManager.shouldWrap} is reverted.</li>
 *   <li><b>Weather sub-toggle OFF, master ON &rarr; wrapper survives (the leak fix).</b>
 *       With {@code perDimWorldInfo=true} but {@code enableCustomPlanetWeather=false},
 *       the wrapper — which owns per-dimension TIME, not just weather — must STILL
 *       install. Fails if {@code shouldWrap}/{@code isWeatherManaged} are
 *       re-gated on the weather flag (the bug where turning weather off also
 *       killed per-dim time).</li>
 * </ul>
 *
 * <p>One server for the class. Because the decision is made at a dim's FIRST load and sticks, each
 * scenario owns a planet of its own in the seeded {@link Galaxy} that nothing else ever loads, and
 * puts both flags back as it read them.</p>
 */
@SeededWorld(PerDimWorldInfoMasterToggleTest.Galaxy.class)
public class PerDimWorldInfoMasterToggleTest extends AbstractSharedServerTest {

    /** First loaded with the master switch OFF. */
    private static final int MASTER_OFF_DIM = 9311;
    /** First loaded with the master ON and the weather sub-toggle OFF. */
    private static final int WEATHER_OFF_DIM = 9312;
    /** The class a wrapped world reports; asked of the FIELD rather than matched in the
     *  reply, so a class name mentioned anywhere else cannot answer for it. */
    private static final String WORLD_INFO_CLASS = "worldInfoClass";

    /** Two otherwise identical planets, one per scenario. */
    public static final class Galaxy implements WorldSeed {
        @Override
        public void seed(GameDirSeed seed) {
            seed.planetDefs("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<galaxy>\n"
                    + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                    + "          isBlackHole=\"false\" diskAngle=\"70\" "
                    + "          numPlanets=\"2\" numGasGiants=\"0\">\n"
                    + planetXml("PerDimMasterPlanet", MASTER_OFF_DIM)
                    + planetXml("PerDimWeatherOffPlanet", WEATHER_OFF_DIM)
                    + "    </star>\n"
                    + "</galaxy>\n", PerDimWorldInfoMasterToggleTest.class);
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

    /**
     * Whether the world a {@code dim time} reply is about carries Stellurgy's own {@code WorldInfo}.
     * The clock probe owes a reader of its own and does not have one yet, so this stays and names the
     * one verb it serves.
     */
    private static boolean dimTimeIsWrapped(String reply) {
        return Reply.of("stellurgytest dim time", reply).text(WORLD_INFO_CLASS)
                .endsWith("StellurgyDimensionWorldInfo");
    }

    /** One world's sky, refusing a world the probe could not bring up. */
    private DimWeather weather(int dim) throws Exception {
        return DimWeather.forDim(this::exec, dim).requireDim(dim);
    }

    private void assertDimRegistered(int dim) throws Exception {
        String dimList = exec("stellurgytest dim list");
        // Asked of Stellurgy's own registry list, by element: a substring of the whole reply also
        // matched `-2` for 2, `12` for 1, and the same id in the Forge list.
        assertTrue("fixture dim " + dim + " not registered: " + dimList,
                java.util.Arrays.stream(Reply.of("stellurgytest dim list", dimList)
                        .intArray("stellurgyDimensions")).anyMatch(d -> d == dim));
    }

    /** A boolean config flag as the server holds it now, so a scenario can put it back. */
    private String configValue(String key) throws Exception {
        String resp = exec("stellurgytest config get " + key);
        Reply reply = Reply.of(resp);
        assertTrue("could not read config " + key + ": " + resp, reply.has("value"));
        return reply.text("value");
    }

    /** Pins INV-WGEN-14 (with perDimWorldInfo on one dimension's weather does not leak to another). */
    @Test
    public void masterOffLeavesPlanetOnVanillaWorldInfo() throws Exception {
        assertDimRegistered(MASTER_OFF_DIM);
        String masterBefore = configValue("perDimWorldInfo");
        try {
            // Master OFF before the dim is EVER loaded -> shouldWrap runtime-gates it
            // out, so the first load keeps the vanilla DerivedWorldInfo.
            assertTrue(Reply.of(exec("stellurgytest config set perDimWorldInfo false")).ok());

            DimWeather info = weather(MASTER_OFF_DIM); // first load
            assertFalse("with perDimWorldInfo OFF a freshly-loaded planet must NOT be "
                    + "wrapped (vanilla shared-overworld WorldInfo) — got " + info.raw(),
                    info.usesStellurgyWorldInfo());
        } finally {
            exec("stellurgytest config set perDimWorldInfo " + masterBefore);
        }
    }

    @Test
    public void weatherOffButMasterOnKeepsTheWrapperForPerDimTime() throws Exception {
        assertDimRegistered(WEATHER_OFF_DIM);
        String masterBefore = configValue("perDimWorldInfo");
        String weatherBefore = configValue("enableCustomPlanetWeather");
        try {
            // Master ON (the boot default, set explicitly) but the weather SUB-toggle OFF — the
            // leak-fix contract: the wrapper that owns per-dim TIME must still install even though
            // custom weather is disabled.
            assertTrue(Reply.of(exec("stellurgytest config set perDimWorldInfo true")).ok());
            assertTrue(Reply.of(exec("stellurgytest config set enableCustomPlanetWeather false")).ok());

            DimWeather info = weather(WEATHER_OFF_DIM); // first load
            assertTrue("perDimWorldInfo ON + weather OFF must STILL wrap the planet "
                    + "(per-dim time rides the wrapper) — got " + info.raw(),
                    info.usesStellurgyWorldInfo());

            // Tie the contract to TIME explicitly: the per-dim clock probe sees the wrapper with
            // weather off (proves the time mechanism was not collateral damage of disabling weather).
            String time = exec("stellurgytest dim time " + WEATHER_OFF_DIM);
            assertTrue("dim-time probe must report the per-dim wrapper with weather "
                    + "OFF — got " + time, dimTimeIsWrapped(time));
        } finally {
            exec("stellurgytest config set enableCustomPlanetWeather " + weatherBefore);
            exec("stellurgytest config set perDimWorldInfo " + masterBefore);
        }
    }
}
