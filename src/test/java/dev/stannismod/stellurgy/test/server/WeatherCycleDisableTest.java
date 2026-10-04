package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.DimList;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.DimWeather;
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
 * Disableability contract for the custom planet weather CYCLE.
 *
 * <p>{@code WorldProviderPlanet.updateWeather()} overrides the vanilla weather
 * cycle for planets whose XML carries non-default rain/thunder markers. The bug:
 * that override keyed only off the markers, so it kept forcing weather even with
 * {@code enableCustomPlanetWeather} off — overwriting the (un-wrapped) shared
 * overworld weather. The fix gates the override on the config flag too.</p>
 *
 * <p>Contract pinned here, deterministically, by driving {@code updateWeather()}
 * directly via a probe: with a forced-clear marker (rain = -1) set on a planet
 * that we've just made rain, one weather tick suppresses the rain when the flag
 * is ON (custom cycle runs) but leaves it raining when the flag is OFF (vanilla
 * delegation). The marker stays set across both cases; only the config flips.</p>
 *
 * <p>One server for the class, booted once over the galaxy {@link Galaxy} declares.</p>
 */
@SeededWorld(WeatherCycleDisableTest.Galaxy.class)
public class WeatherCycleDisableTest extends AbstractSharedServerTest {

    private static final int FIXTURE_DIM = 9301;


    /** The galaxy this class's one shared server boots over. */
    public static final class Galaxy implements WorldSeed {
        @Override
        public void seed(dev.stannismod.stellurgy.test.client.GameDirSeed seed) {
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<galaxy>\n"
                    + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                    + "          isBlackHole=\"false\" diskAngle=\"70\" "
                    + "          numPlanets=\"1\" numGasGiants=\"0\">\n"
                    + "        <planet name=\"WeatherDisablePlanet\" DIMID=\"" + FIXTURE_DIM + "\">\n"
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
                    + "        </planet>\n"
                    + "    </star>\n"
                    + "</galaxy>\n";
            seed.planetDefs(xml, WeatherCycleDisableTest.class);
        }
    }

    private String cmd(String c) throws Exception {
        return String.join("\n", client().execute(c));
    }

    /** One world's sky, refusing the {@code world not loaded} reply and the wrong dimension. */
    private DimWeather weather(int dim) throws Exception {
        return DimWeather.forDim(this::cmd, dim).requireDim(dim);
    }

    @Test
    public void customWeatherCycleRunsOnlyWhenConfigEnabled() throws Exception {

        DimList dimList = DimList.of(cmd("stellurgytest dim list"));
        assertTrue("fixture dim not registered: " + dimList, dimList.holds(FIXTURE_DIM));

        // Load the planet while custom weather is still ENABLED (boot default) so it
        // wraps with its own StellurgyDimensionWorldInfo. Wrapping is sticky for the dim's
        // lifetime, so the later config-off sub-case operates on the same wrapped,
        // overworld-isolated WorldInfo — isolating the updateWeather() gate from the
        // separate (already-tested) wrapping gate.
        // The flag is put back as this scenario found it: the server is shared with the class run.
        String flagBefore = Reply.of(cmd("stellurgytest config get enableCustomPlanetWeather")).text("value");
        try {
            customWeatherCycleRunsOnlyWhenConfigEnabledBody();
        } finally {
            cmd("stellurgytest config set enableCustomPlanetWeather " + flagBefore);
        }
    }

    private void customWeatherCycleRunsOnlyWhenConfigEnabledBody() throws Exception {
        assertTrue(Reply.of(cmd("stellurgytest config set enableCustomPlanetWeather true")).ok());
        DimWeather wrapped = weather(FIXTURE_DIM);
        // Anchor on the probe's named worldInfoClass field, not a bare substring
        // of the whole response.
        assertTrue("planet must be wrapped while custom weather is on: " + wrapped.raw(),
                wrapped.usesStellurgyWorldInfo());

        // Forced-clear marker (rain=-1, thunder=-1): the custom cycle, when it runs,
        // drives this planet to clear regardless of what we set.
        String marker = cmd("stellurgytest weather set-marker " + FIXTURE_DIM + " -1 -1");
        assertTrue("set-marker failed: " + marker, Reply.of(marker).bool("usesCustomWorldInfo"));

        // --- config ON: the forced-clear cycle runs and suppresses the rain ---
        // (No intermediate "is raining" assert — with the cycle active the natural
        // server tick clears it before we could observe it; the post-tick state is
        // the deterministic contract.)
        assertTrue("weather set rain failed",
                Reply.of(cmd("stellurgytest weather set " + FIXTURE_DIM + " rain 12000")).ok());
        assertTrue("tick-provider failed",
                Reply.of(cmd("stellurgytest weather tick-provider " + FIXTURE_DIM + " 3")).ok());
        DimWeather onAfterTick = weather(FIXTURE_DIM);
        assertFalse("with custom planet weather ON, the forced-clear cycle must suppress the "
                + "rain — got " + onAfterTick.raw(), onAfterTick.raining);

        // --- config OFF (the fix): updateWeather delegates to vanilla; the custom
        // forced-clear cycle does NOT run, so rain we set takes and survives ticks.
        // This fails if the fix is reverted (the marker cycle would clear it).
        assertTrue(Reply.of(cmd("stellurgytest config set enableCustomPlanetWeather false")).ok());
        assertTrue("weather set rain failed",
                Reply.of(cmd("stellurgytest weather set " + FIXTURE_DIM + " rain 12000")).ok());
        DimWeather offAfterSet = weather(FIXTURE_DIM);
        assertTrue("with custom planet weather OFF, set rain must take (no custom cycle to "
                + "suppress it) — got " + offAfterSet.raw(), offAfterSet.raining);
        assertTrue("tick-provider failed",
                Reply.of(cmd("stellurgytest weather tick-provider " + FIXTURE_DIM + " 3")).ok());
        DimWeather offAfterTick = weather(FIXTURE_DIM);
        assertTrue("with custom planet weather OFF, the rain must survive weather ticks "
                + "(vanilla delegation, marker ignored) — got " + offAfterTick.raw(),
                offAfterTick.raining);
    }
}
