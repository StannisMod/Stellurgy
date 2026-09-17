package zmaster587.advancedRocketry.test.server;

// migrated to AbstractSharedServerTest
import org.junit.Assume;

import zmaster587.advancedRocketry.test.DimWeather;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * event-handler wiring smokes.
 *
 * The full Phase 1 plan asks for deep PlanetEventHandler / RocketEventHandler
 * tests (player dim-change side effects, launch/land counters, etc.) which
 * need either a player entity injected via the harness or new probe verbs.
 * Those are deferred.
 *
 * This file covers the two cheapest wiring assertions:
 *
 *   1. {@code PlanetWeatherEventHandler.onWorldLoad} (subscribed via Forge
 *      {@code WorldEvents}) wraps every AR planet dim the moment it loads.
 *      We've been *inferring* this from WeatherBaselineTest; here we test
 *      it standalone — load an AR dim with no prior `/artest weather set`,
 *      probe immediately, assert the wrapper class is in place.
 *
 *   2. Symmetric counter-test: the same event handler does NOT wrap
 *      non-AR dims (overworld stays vanilla WorldInfo). Already weakly
 *      asserted by NonARDimensionIsolationTest; included here for the
 *      explicit "event handler discriminates by dim type" intent.
 *
 * If either assertion regresses, the entire B1 weather chain silently
 * stops working without the WeatherBaselineTest failing in the same way
 * — those tests force-set rain first, masking the wrapping-is-missing
 * cause behind a more specific symptom.
 */
public class EventHandlerWiringTest extends AbstractSharedServerTest {

    private static final String AR_DIMS_ARRAY_PATTERN = "arDimensions";

    private int firstNonOverworldArDimOrSkip() throws Exception {
        String joined = String.join("\n", client().execute("artest dim list"));
        Assume.assumeFalse(
                "No AR dimensions registered — skipping (empty galaxy?)",
                joined.contains("\"arDimensions\":[]"));
        Reply dims = Reply.of("artest dim list", joined);
        assertTrue("could not parse arDimensions array: " + joined, dims.has(AR_DIMS_ARRAY_PATTERN));
        for (int dim : dims.intArray(AR_DIMS_ARRAY_PATTERN)) {
            if (dim != 0) return dim;
        }
        Assume.assumeTrue(
                "Only overworld is an AR planet — skipping wrapper assertion",
                false);
        return -1;
    }

    @Test
    public void loadingArDimImmediatelyTriggersWeatherWrapperInstall() throws Exception {
        int dim = firstNonOverworldArDimOrSkip();
        // Fresh load via the dedicated probe — first call MUST install the
        // wrapper via WorldEvent.Load. No `/artest weather set` between the
        // load and the probe — we're testing the event chain, not the
        // setRain path that follows it.
        String loaded = String.join("\n", client().execute("artest dim load " + dim));
        assertTrue("dim load probe did not report loaded=true: " + loaded,
                loaded.contains("\"loaded\":true"));

        DimWeather weather = weather(dim);
        assertTrue("WeatherEventHandler did not install the B1 wrapper on AR dim load: "
                        + weather.raw(),
                weather.usesARWorldInfo());
    }

    @Test
    public void overworldStaysVanillaAfterLoad() throws Exception {
        // Counter-test: WorldEvent.Load on a non-AR dim must NOT wrap.
        // (The wrapping decision lives in PlanetWeatherManager.shouldWrap,
        // and this fixes the polarity of that gate.)
        client().execute("artest dim load 0");
        DimWeather weather = weather(0);
        // Vanilla overworld WorldInfo class — neither ARDimensionWorldInfo
        // nor anything that contains "ARWeather".
        assertFalse("overworld was incorrectly wrapped — wrapping gate broken: " + weather.raw(),
                weather.usesARWorldInfo());
    }

    /** One world's sky, refusing a world the probe could not bring up. */
    private DimWeather weather(int dim) throws Exception {
        return DimWeather.forDim(cmd -> String.join("\n", client().execute(cmd)), dim)
                .requireDim(dim);
    }
}
