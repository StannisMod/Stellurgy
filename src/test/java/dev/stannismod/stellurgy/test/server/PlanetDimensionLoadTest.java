package dev.stannismod.stellurgy.test.server;

// migrated to AbstractSharedServerTest
import dev.stannismod.stellurgy.test.DimInfo;
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
    public void stellurgyPlanetsArePreloaded() throws Exception {
        String joined = String.join("\n", client().execute("stellurgytest dim list"));

        assertTrue("dim list missing stellurgyDimensions key — probe wiring broken: " + joined,
                (Reply.of(joined).arrayLength("stellurgyDimensions") >= 0));

        Assume.assumeFalse(
                "No Stellurgy dimensions registered — skipping (empty galaxy?)",
                (Reply.of(joined).arrayLength("stellurgyDimensions") == 0));
    }

    @Test
    public void dimLoadOnOverworldReportsLoaded() throws Exception {
        // /stellurgytest dim load <id> must force-load the world and
        // report `loaded:true` afterwards. Overworld (dim 0) is always loaded
        // on a fresh dedicated server, so this smoke pins the probe wiring
        // without depending on any Stellurgy-specific dim id. Deeper load behavior
        // (loading a not-yet-touched Stellurgy dim and back) belongs to a later phase.
        String joined = String.join("\n", client().execute("stellurgytest dim load 0"));

        assertTrue("dim load 0 did not echo dim:0 in response: " + joined,
                (Reply.of(joined).integer("dim") == 0));
        assertTrue("dim load 0 did not report loaded:true: " + joined,
                Reply.of(joined).bool("loaded"));
    }

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
    public void biomeProviderIsNonNull() throws Exception {
        int stellurgyDim = firstNonOverworldStellurgyDimOrSkip();
        // The reader REFUSES both an absent field and the producer's literal "null", which is
        // exactly the pair of assertions this leg used to spell for itself.
        DimInfo info = loadAndInfo(stellurgyDim);
        assertFalse("biomeProviderClass reported null for Stellurgy dim " + stellurgyDim + ": " + info.raw(),
                info.biomeProviderClass().isEmpty());
    }

    @Test
    public void chunkGeneratorIsNonNull() throws Exception {
        int stellurgyDim = firstNonOverworldStellurgyDimOrSkip();
        DimInfo info = loadAndInfo(stellurgyDim);
        assertFalse("chunkGeneratorClass reported null for Stellurgy dim " + stellurgyDim + ": " + info.raw(),
                info.chunkGeneratorClass().isEmpty());
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
    public void celestialAngleStableAcrossSameWorldTime() throws Exception {
        int stellurgyDim = firstNonOverworldStellurgyDimOrSkip();
        // The probe is a pure function of (dim, worldTime), so two calls with
        // identical inputs must produce identical angles. We compare extracted
        // numeric values rather than full response strings — the dedicated
        // server prefixes each console echo with a timestamp, so byte-level
        // response equality would race on tick boundaries.
        loadDim(stellurgyDim);
        double first = extractAngle(client().execute(
                "stellurgytest dim celestial-angle " + stellurgyDim + " 0"));
        double second = extractAngle(client().execute(
                "stellurgytest dim celestial-angle " + stellurgyDim + " 0"));

        assertEquals(
                "celestial-angle must be deterministic for identical inputs",
                first, second, 0.0);
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

    private int firstStellurgyDimOrSkip() throws Exception {
        String joined = String.join("\n", client().execute("stellurgytest dim list"));
        Assume.assumeFalse(
                "No Stellurgy dimensions registered — skipping (empty galaxy?)",
                (Reply.of(joined).arrayLength("stellurgyDimensions") == 0));
        int[] dims = Reply.of("stellurgytest dim list", joined).intArray(AR_DIM_PATTERN);
        assertTrue("could not parse first Stellurgy dim id from probe response: " + joined, dims.length > 0);
        return dims[0];
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
