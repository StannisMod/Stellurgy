package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;

import zmaster587.advancedRocketry.test.DimInfo;
import zmaster587.advancedRocketry.test.DimWeather;

import static org.junit.Assert.assertFalse;

/**
 * vanilla / non-AR dimension isolation.
 *
 * Asserts that:
 * <ul>
 *   <li>nether (-1) and end (1) are not mis-classified as AR planets;</li>
 *   <li>the B1 weather wrapper is NOT installed on them — they must keep
 *       their vanilla {@code DerivedWorldInfo} so unrelated mods that read
 *       weather state see exactly what they would in vanilla;</li>
 *   <li>the overworld (dim=0) is also NOT wrapped — the wrap policy
 *       explicitly excludes dim 0.</li>
 * </ul>
 *
 * The wrapper-class assertion is the new (post-B1) guard: without it, a future
 * regression to {@link zmaster587.advancedRocketry.world.weather.PlanetWeatherManager#shouldWrap}
 * that accidentally accepts vanilla dims would only surface as a subtle
 * weather glitch on the Nether ages later.
 */
public class NonARDimensionIsolationTest extends AbstractHeadlessServerTest {

    @Test
    public void netherAndEndAreNotARPlanets() throws Exception {
        DimInfo nether = dimInfo(-1);
        assertFalse("nether is mis-classified as an AR planet: " + nether.raw(), nether.arPlanet);

        DimInfo end = dimInfo(1);
        assertFalse("end is mis-classified as an AR planet: " + end.raw(), end.arPlanet);
    }

    @Test
    public void overworldAndVanillaDimsAreNotWrapped() throws Exception {
        // The sanity check the three claims below used to need — that the reply really is a weather
        // reading and not the probe's `{"error":"world not loaded"}` — is now the read itself, and
        // it covers all three rather than only the overworld: a NEGATED substring over the whole
        // reply is satisfied by a world that does not exist, which is how each of these could have
        // passed while proving nothing.
        DimWeather overworld = weather(0);
        assertFalse("overworld must NOT have the AR weather wrapper installed: " + overworld.raw(),
                overworld.usesARWorldInfo());

        DimWeather nether = weather(-1);
        assertFalse("nether must NOT have the AR weather wrapper installed: " + nether.raw(),
                nether.usesARWorldInfo());

        DimWeather end = weather(1);
        assertFalse("end must NOT have the AR weather wrapper installed: " + end.raw(),
                end.usesARWorldInfo());
    }

    /** One world's sky, refusing a world the probe could not bring up. */
    private DimWeather weather(int dim) throws Exception {
        return DimWeather.forDim(cmd -> String.join("\n", client().execute(cmd)), dim)
                .requireDim(dim);
    }

    /** What the server says about one dimension, read through the verb's own reader. */
    private DimInfo dimInfo(int dim) throws Exception {
        return DimInfo.forDim(cmd -> String.join("\n", client().execute(cmd)), dim);
    }
}
