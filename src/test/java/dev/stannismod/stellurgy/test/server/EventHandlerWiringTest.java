package dev.stannismod.stellurgy.test.server;

// migrated to AbstractSharedServerTest
import org.junit.Assume;

import dev.stannismod.stellurgy.test.DimWeather;
import dev.stannismod.stellurgy.test.Reply;
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
 *      {@code WorldEvents}) wraps every Stellurgy planet dim the moment it loads.
 *      We've been *inferring* this from WeatherBaselineTest; here we test
 *      it standalone — load a Stellurgy dim with no prior `/stellurgytest weather set`,
 *      probe immediately, assert the wrapper class is in place.
 *
 *   2. Symmetric counter-test: the same event handler does NOT wrap
 *      non-Stellurgy dims (overworld stays vanilla WorldInfo). Already weakly
 *      asserted by NonStellurgyDimensionIsolationTest; included here for the
 *      explicit "event handler discriminates by dim type" intent.
 *
 * If either assertion regresses, the entire B1 weather chain silently
 * stops working without the WeatherBaselineTest failing in the same way
 * — those tests force-set rain first, masking the wrapping-is-missing
 * cause behind a more specific symptom.
 */
public class EventHandlerWiringTest extends AbstractSharedServerTest {

    private static final String AR_DIMS_ARRAY_PATTERN = "stellurgyDimensions";

    private int firstNonOverworldStellurgyDimOrSkip() throws Exception {
        String joined = String.join("\n", client().execute("stellurgytest dim list"));
        Assume.assumeFalse(
                "No Stellurgy dimensions registered — skipping (empty galaxy?)",
                (Reply.of(joined).arrayLength("stellurgyDimensions") == 0));
        Reply dims = Reply.of("stellurgytest dim list", joined);
        assertTrue("could not parse stellurgyDimensions array: " + joined, dims.has(AR_DIMS_ARRAY_PATTERN));
        for (int dim : dims.intArray(AR_DIMS_ARRAY_PATTERN)) {
            if (dim != 0) return dim;
        }
        Assume.assumeTrue(
                "Only overworld is a Stellurgy planet — skipping wrapper assertion",
                false);
        return -1;
    }

    @Test
    public void loadingStellurgyDimImmediatelyTriggersWeatherWrapperInstall() throws Exception {
        int dim = firstNonOverworldStellurgyDimOrSkip();
        // Fresh load via the dedicated probe — first call MUST install the
        // wrapper via WorldEvent.Load. No `/stellurgytest weather set` between the
        // load and the probe — we're testing the event chain, not the
        // setRain path that follows it.
        String loaded = String.join("\n", client().execute("stellurgytest dim load " + dim));
        assertTrue("dim load probe did not report loaded=true: " + loaded,
                Reply.of(loaded).bool("loaded"));

        DimWeather weather = weather(dim);
        assertTrue("WeatherEventHandler did not install the B1 wrapper on Stellurgy dim load: "
                        + weather.raw(),
                weather.usesStellurgyWorldInfo());
    }

    @Test
    public void overworldStaysVanillaAfterLoad() throws Exception {
        // Counter-test: WorldEvent.Load on a non-Stellurgy dim must NOT wrap.
        // (The wrapping decision lives in PlanetWeatherManager.shouldWrap,
        // and this fixes the polarity of that gate.)
        client().execute("stellurgytest dim load 0");
        DimWeather weather = weather(0);
        // Vanilla overworld WorldInfo class — neither StellurgyDimensionWorldInfo
        // nor anything that contains "StellurgyWeather".
        assertFalse("overworld was incorrectly wrapped — wrapping gate broken: " + weather.raw(),
                weather.usesStellurgyWorldInfo());
    }

    /** One world's sky, refusing a world the probe could not bring up. */
    private DimWeather weather(int dim) throws Exception {
        return DimWeather.forDim(cmd -> String.join("\n", client().execute(cmd)), dim)
                .requireDim(dim);
    }
}
