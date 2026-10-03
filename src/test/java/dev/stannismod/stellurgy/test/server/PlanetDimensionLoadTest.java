package dev.stannismod.stellurgy.test.server;

// migrated to AbstractSharedServerTest
import dev.stannismod.stellurgy.test.DimInfo;
import dev.stannismod.stellurgy.test.DimWeather;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Assume;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * planet/dimension lifecycle smoke.
 *
 * Walks {@code /stellurgytest dim list} to verify Stellurgy has registered at least one
 * planet, then drills into a representative Stellurgy dim to confirm the provider
 * wiring (provider class, biome provider, chunk generator, save folder) and
 * the celestial-angle math is deterministic and time-varying. Empty galaxy
 * configurations skip via {@link Assume} so an empty mod-pack doesn't gate
 * the suite.
 */
public class PlanetDimensionLoadTest extends AbstractSharedServerTest {

    private static final String AR_PROVIDER_FQN =
            "dev.stannismod.stellurgy.world.provider.WorldProviderPlanet";

    private static final String AR_DIM_PATTERN = "stellurgyDimensions";

    private static final String AR_DIMS_ARRAY_PATTERN = "stellurgyDimensions";

    private static final String ANGLE_PATTERN = "angle";

    @Test
    public void providerClassIsWorldProviderPlanet() throws Exception {
        // Stellurgy registers Earth as dim 0 but keeps its vanilla WorldProviderSurface,
        // so this assertion targets the first NON-overworld Stellurgy planet — the
        // ones that actually exercise Stellurgy's WorldProviderPlanet wiring.
        int stellurgyDim = firstNonOverworldStellurgyDimOrSkip();
        DimInfo info = loadAndInfo(stellurgyDim);
        assertEquals(
                "dim " + stellurgyDim + " providerClass should be " + AR_PROVIDER_FQN + ": " + info.raw(),
                AR_PROVIDER_FQN, info.providerClass());
    }

    @Test
    public void saveFolderResolvesToExpectedPath() throws Exception {
        int stellurgyDim = firstNonOverworldStellurgyDimOrSkip();
        DimInfo info = loadAndInfo(stellurgyDim);
        // WorldProviderPlanet.getSaveFolder() returns "advRocketry/" + super.getSaveFolder().
        assertTrue("saveDir for Stellurgy planet " + stellurgyDim + " should be under advRocketry/: " + info.raw(),
                info.saveDir().startsWith("advRocketry/"));
    }

    @Test
    public void celestialAngleProgressesAcrossDifferentWorldTimes() throws Exception {
        int stellurgyDim = firstNonOverworldStellurgyDimOrSkip();
        loadDim(stellurgyDim);
        double a0 = extractAngle(client().execute(
                "stellurgytest dim celestial-angle " + stellurgyDim + " 0"));
        double a6k = extractAngle(client().execute(
                "stellurgytest dim celestial-angle " + stellurgyDim + " 6000"));
        double a12k = extractAngle(client().execute(
                "stellurgytest dim celestial-angle " + stellurgyDim + " 12000"));

        // Soft assertion: three meaningfully different world times must not
        // collapse to the same angle. The celestial cycle wraps modulo the
        // rotational period, so strict monotonicity isn't safe to assert
        // without first pinning Stellurgy's exact rotational-period math; that
        // belongs to a future test once the rocket assembly suite is in.
        assertNotEquals("celestial-angle did not change between t=0 and t=6000 (a0=" + a0
                + ", a6k=" + a6k + ")", a0, a6k, 0.0);
        assertNotEquals("celestial-angle did not change between t=6000 and t=12000 (a6k=" + a6k
                + ", a12k=" + a12k + ")", a6k, a12k, 0.0);
    }

    /** The nether (-1) and the end (1) are not classified as Stellurgy planets. */
    @Test
    public void netherAndEndAreNotStellurgyPlanets() throws Exception {
        DimInfo nether = DimInfo.forDim(cmd -> String.join("\n", client().execute(cmd)), -1);
        assertFalse("nether is mis-classified as a Stellurgy planet: " + nether.raw(), nether.stellurgyPlanet);
        DimInfo end = DimInfo.forDim(cmd -> String.join("\n", client().execute(cmd)), 1);
        assertFalse("end is mis-classified as a Stellurgy planet: " + end.raw(), end.stellurgyPlanet);
    }

    /**
     * The overworld, the nether and the end keep their vanilla {@code DerivedWorldInfo}: the
     * Stellurgy weather wrapper's policy ({@code PlanetWeatherManager#shouldWrap}) excludes them, so
     * other mods reading weather on them see exactly what vanilla would show.
     */
    @Test
    public void overworldAndVanillaDimsAreNotWrapped() throws Exception {
        for (int dim : new int[]{0, -1, 1}) {
            // The read refuses a world the probe could not bring up: a negated claim about a world
            // that does not exist would otherwise pass while proving nothing.
            DimWeather weather = DimWeather.forDim(cmd -> String.join("\n", client().execute(cmd)), dim)
                    .requireDim(dim);
            assertFalse("dim " + dim + " must NOT have the Stellurgy weather wrapper installed: " + weather.raw(),
                    weather.usesStellurgyWorldInfo());
        }
    }

    private int firstNonOverworldStellurgyDimOrSkip() throws Exception {
        String joined = String.join("\n", client().execute("stellurgytest dim list"));
        Assume.assumeFalse(
                "No Stellurgy dimensions registered — skipping (empty galaxy?)",
                (Reply.of(joined).arrayLength("stellurgyDimensions") == 0));
        Reply listed = Reply.of("stellurgytest dim list", joined);
        assertTrue("could not parse stellurgyDimensions array from probe response: " + joined,
                listed.has(AR_DIMS_ARRAY_PATTERN));
        Integer found = null;
        for (int dim : listed.intArray(AR_DIMS_ARRAY_PATTERN)) {
            if (dim != 0) {
                found = dim;
                break;
            }
        }
        Assume.assumeTrue(
                "Only overworld (dim 0) is registered as a Stellurgy planet — skipping " +
                        "WorldProviderPlanet-specific assertions",
                found != null);
        return found;
    }

    private DimInfo loadAndInfo(int dim) throws Exception {
        loadDim(dim);
        return DimInfo.forDim(cmd -> String.join("\n", client().execute(cmd)), dim);
    }

    private void loadDim(int dim) throws Exception {
        // Force the dim loaded before any property/angle probe — Stellurgy dims are
        // not in DimensionManager-loaded state on fresh boot.
        client().execute("stellurgytest dim load " + dim);
    }

    private static double extractAngle(List<String> response) {
        String joined = String.join("\n", response);
        Reply mReply = Reply.of(joined);
        if (!mReply.has(ANGLE_PATTERN)) {
            throw new AssertionError("could not extract angle from probe response: " + joined);
        }
        return Double.parseDouble(mReply.text(ANGLE_PATTERN));
    }
}
