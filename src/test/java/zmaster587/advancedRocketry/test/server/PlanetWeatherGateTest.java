package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import zmaster587.advancedRocketry.test.DimList;
import zmaster587.advancedRocketry.test.DimWeather;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.GameTicks;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * planet weather compatibility gates (feature/better_weather).
 *
 * <p>Pins two player-visible contracts of {@code WorldProviderPlanet.updateWeather}:</p>
 * <ol>
 *   <li><b>Atmosphere gate.</b> A planet whose atmosphere is thinner than
 *       {@code minAtmosphereDensityForRain} (default 75) must NOT rain, even with
 *       {@code rainMarker=1} ("always rain"). The same marker on a thick-atmosphere
 *       planet DOES rain — the contrast proves the gate, not a dead tick.</li>
 *   <li><b>Thunder requires rain.</b> {@code thunderMarker=1} combined with
 *       {@code rainMarker=-1} must leave the planet not thundering — vanilla
 *       couples thunder to rain and AR must not create a dry storm.</li>
 * </ol>
 *
 * <p>All three fixture planets keep a non-default marker so
 * {@code usesCustomWorldInfo()} engages the custom cycle; the live state is read
 * back through {@code artest weather get}.</p>
 */
public class PlanetWeatherGateTest {

    /**
     * Ticks the weather cycle is given to announce a dimension.
     *
     * <p>25 = the five rounds of five the previous helper spent unconditionally, kept so this
     * change moves the FORM of the wait and not its size. It is a DEADLINE for every read here —
     * the positive one waits for rain, the negative ones for the cycle's first tick — and not a
     * measured figure for how long {@code updateWeather} needs after a mid-test
     * {@code initDimension}; nothing in this file has established that.</p>
     */
    private static final int SETTLE_BUDGET_TICKS = 25;

    private static final int DIM_THIN_RAIN   = 9111; // density 10, rainMarker 1   -> must stay clear
    private static final int DIM_THICK_RAIN  = 9112; // density 100, rainMarker 1  -> must rain
    private static final int DIM_DRY_THUNDER = 9113; // density 100, thunder 1 / rain -1 -> no thunder

    private Path workDir;
    private RealDedicatedServerHarness harness;

    @Before
    public void writeFixture() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));

        workDir = Files.createTempDirectory("forge-server-weather-gate-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"3\" numGasGiants=\"0\">\n"
                + planetXml("ThinRainPlanet",  DIM_THIN_RAIN,   /*density*/ 10,  /*rainMarker*/ 1,  /*thunderMarker*/ 0)
                + planetXml("ThickRainPlanet", DIM_THICK_RAIN,  /*density*/ 100, /*rainMarker*/ 1,  /*thunderMarker*/ 0)
                + planetXml("DryThunderPlanet", DIM_DRY_THUNDER, /*density*/ 100, /*rainMarker*/ -1, /*thunderMarker*/ 1)
                + "    </star>\n"
                + "</galaxy>\n";
        Files.write(arConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String planetXml(String name, int dim, int density, int rainMarker, int thunderMarker) {
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
                + "            <atmosphereDensity>" + density + "</atmosphereDensity>\n"
                + "            <rainMarker>" + rainMarker + "</rainMarker>\n"
                + "            <thunderMarker>" + thunderMarker + "</thunderMarker>\n"
                + "            <generateCraters>false</generateCraters>\n"
                + "            <generateCaves>true</generateCaves>\n"
                + "            <generateVolcanos>false</generateVolcanos>\n"
                + "        </planet>\n";
    }

    @After
    public void stopHarness() throws Exception {
        if (harness != null) harness.close();
    }

    @Test
    public void atmosphereGatesRainAndThunderRequiresRain() throws Exception {
        harness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);

        DimList dimList = DimList.of(String.join("\n", harness.client().execute("artest dim list")));
        for (int dim : new int[]{DIM_THIN_RAIN, DIM_THICK_RAIN, DIM_DRY_THUNDER}) {
            assertTrue("fixture dim " + dim + " not registered: " + dimList,
                    dimList.holds(dim));
        }

        // Read the live state of each planet. The three claims are not the same SHAPE, so they are
        // not read the same way: the thick planet MUST reach rain, which is an outcome to wait for;
        // the other two must never reach it, which is read once the cycle has decided.
        DimWeather thick = weatherUntilRaining(DIM_THICK_RAIN);
        DimWeather thin  = weatherAfterCycle(DIM_THIN_RAIN);
        DimWeather dry   = weatherAfterCycle(DIM_DRY_THUNDER);

        // Contrast: same rainMarker=1, opposite atmosphere -> opposite rain state.
        assertTrue("thick-atmosphere planet with rainMarker=1 must rain (gate baseline): "
                + thick.raw(), thick.raining);
        assertFalse("thin-atmosphere planet must stay clear despite rainMarker=1 "
                        + "(atmosphere gate): " + thin.raw(),
                thin.raining);

        // Thunder cannot exist without rain: dry planet (rainMarker=-1) must not thunder.
        assertFalse("dry planet (rainMarker=-1) must not rain: " + dry.raw(), dry.raining);
        assertFalse("thunderMarker=1 with no rain must NOT thunder (vanilla couples them): "
                + dry.raw(), dry.thundering);
    }

    /**
     * The live weather of {@code dim}, once it is RAINING or the budget runs out — the POSITIVE
     * form, for a state the cycle is supposed to reach.
     *
     * <p>A single helper used to serve all three planets by reading five times with a fixed advance
     * between reads, testing nothing. Its own javadoc named the condition — "so the server has
     * ticked {@code updateWeather} at least once" — and the code never asked it, so a planet that
     * had rained on the first read still paid the whole window.</p>
     *
     * <p><b>The first read comes before any advancing, and that order is load-bearing.</b>
     * {@code artest weather get} is not a read: it pins the dimension and calls
     * {@code initDimension} before answering, so it is what makes the world exist and tick. A
     * version that advanced first would be ticking a world nobody had constructed.</p>
     */
    private DimWeather weatherUntilRaining(int dim) throws Exception {
        // MARKED BEFORE THE CONSTRUCTING READ, which is what makes the mark safe here: the world
        // does not exist until that read pins it, so no weather change for this dimension can have
        // been announced before this line, and the wait below cannot open a window the record has
        // already passed through.
        long mark = events().mark();
        DimWeather first = weather(dim);
        if (first.raining) {
            return first; // it is already raining; there is no transition left to wait for
        }
        // Linked on the cycle's own tick: `planet_weather_changed` is written from
        // `WorldProviderPlanet.updateWeather`'s return, on the transition and once per dimension.
        // The poll it replaces asked the probe for the state every few ticks — a reading of a LEVEL
        // where the subject is an EDGE, so it could tell neither when the rain began nor, on the
        // dimension that never rains, whether the cycle had run at all.
        events().awaitMatching(mark, "planet_weather_changed",
                reply -> Events.recordsWhereAll(reply,
                        "dim", String.valueOf(dim), "raining", "true").size() > 0,
                "carrying dim = " + dim + " and raining = true",
                "a thick-atmosphere planet with rainMarker=1 must reach rain", SETTLE_BUDGET_TICKS);
        return weather(dim);
    }

    /**
     * The live weather of {@code dim} once its weather cycle has run — the NEGATIVE form, for a
     * claim that something must NOT happen.
     *
     * <p>The absence itself has no event, but the cycle that decides it does: its first tick on a
     * dimension is recorded whatever the sky, so this waits for the decision to have been taken and
     * then reads what it decided. The positive form waits for a specific OUTCOME instead, which is
     * why there are two.</p>
     */
    private DimWeather weatherAfterCycle(int dim) throws Exception {
        // Marked before the constructing read, for the reason weatherUntilRaining gives.
        long mark = events().mark();
        // The constructing read: its VALUE is discarded, its side effect is the point — this is
        // what pins the dimension and calls initDimension, so the cycle below runs on a real world.
        // Read through the reader even so: a world that could not be brought up must fail HERE and
        // not as "it never rained", which is what this helper's callers would otherwise report.
        weather(dim);
        // Linked on the cycle's FIRST tick of this dimension, which the recorder announces whatever
        // the state (`first:true`), so a record is owed on the healthy path too. One tick is the
        // whole question for these two planets: the atmosphere gate and a -1 rain marker each force
        // the sky clear on EVERY tick rather than accumulating toward it, so the state the first
        // tick leaves is the state every later tick leaves.
        events().awaitMatching(mark, "planet_weather_changed",
                reply -> !Events.recordsWhere(reply, "dim", String.valueOf(dim)).isEmpty(),
                "carrying dim = " + dim,
                "the weather cycle must run on dim " + dim + " before its sky can be judged",
                SETTLE_BUDGET_TICKS);
        DimWeather now = weather(dim);
        // And across the whole stretch, not only at the read: a sky that rained on one tick and
        // cleared on the next is the defect, and the read alone would call it clear.
        assertTrue("dim " + dim + " rained at some tick since its world came up, whatever it reads"
                        + " now (" + now.raw() + "): " + events().since(mark, "planet_weather_changed"),
                Events.recordsWhereAll(events().since(mark, "planet_weather_changed"),
                        "dim", String.valueOf(dim), "raining", "true").isEmpty());
        return now;
    }

    /** One world's sky, refusing the {@code world not loaded} reply and the wrong dimension. */
    private DimWeather weather(int dim) throws Exception {
        return DimWeather.forDim(cmd -> String.join("\n", harness.client().execute(cmd)), dim)
                .requireDim(dim);
    }

    /** This boot's reader of the server's ordered event log — the harness is this class's own. */
    private Events events() {
        return new Events(cmd -> String.join("\n", harness.client().execute(cmd)),
                ticks -> GameTicks.advance(harness.client(), GameTicks.server(), ticks));
    }
}
