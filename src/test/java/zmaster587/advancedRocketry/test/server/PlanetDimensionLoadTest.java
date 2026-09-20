package zmaster587.advancedRocketry.test.server;

// migrated to AbstractSharedServerTest
import zmaster587.advancedRocketry.test.DimInfo;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Assume;
import org.junit.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * planet/dimension lifecycle smoke.
 *
 * Walks {@code /artest dim list} to verify AR has registered at least one
 * planet, then drills into a representative AR dim to confirm the provider
 * wiring (provider class, biome provider, chunk generator, save folder) and
 * the celestial-angle math is deterministic and time-varying. Empty galaxy
 * configurations skip via {@link Assume} so an empty mod-pack doesn't gate
 * the suite.
 */
public class PlanetDimensionLoadTest extends AbstractSharedServerTest {

    private static final String AR_PROVIDER_FQN =
            "zmaster587.advancedRocketry.world.provider.WorldProviderPlanet";

    private static final String AR_DIM_PATTERN = "arDimensions";

    private static final String AR_DIMS_ARRAY_PATTERN = "arDimensions";

    private static final String ANGLE_PATTERN = "angle";

    @Test
    public void arPlanetsArePreloaded() throws Exception {
        String joined = String.join("\n", client().execute("artest dim list"));

        assertTrue("dim list missing arDimensions key — probe wiring broken: " + joined,
                (Reply.of(joined).arrayLength("arDimensions") >= 0));

        Assume.assumeFalse(
                "No AR dimensions registered — skipping (empty galaxy?)",
                (Reply.of(joined).arrayLength("arDimensions") == 0));
    }

    @Test
    public void dimLoadOnOverworldReportsLoaded() throws Exception {
        // /artest dim load <id> must force-load the world and
        // report `loaded:true` afterwards. Overworld (dim 0) is always loaded
        // on a fresh dedicated server, so this smoke pins the probe wiring
        // without depending on any AR-specific dim id. Deeper load behavior
        // (loading a not-yet-touched AR dim and back) belongs to a later phase.
        String joined = String.join("\n", client().execute("artest dim load 0"));

        assertTrue("dim load 0 did not echo dim:0 in response: " + joined,
                (Reply.of(joined).integer("dim") == 0));
        assertTrue("dim load 0 did not report loaded:true: " + joined,
                Reply.of(joined).bool("loaded"));
    }

    @Test
    public void providerClassIsWorldProviderPlanet() throws Exception {
        // AR registers Earth as dim 0 but keeps its vanilla WorldProviderSurface,
        // so this assertion targets the first NON-overworld AR planet — the
        // ones that actually exercise AR's WorldProviderPlanet wiring.
        int arDim = firstNonOverworldArDimOrSkip();
        DimInfo info = loadAndInfo(arDim);
        assertEquals(
                "dim " + arDim + " providerClass should be " + AR_PROVIDER_FQN + ": " + info.raw(),
                AR_PROVIDER_FQN, info.providerClass());
    }

    @Test
    public void biomeProviderIsNonNull() throws Exception {
        int arDim = firstNonOverworldArDimOrSkip();
        // The reader REFUSES both an absent field and the producer's literal "null", which is
        // exactly the pair of assertions this leg used to spell for itself.
        DimInfo info = loadAndInfo(arDim);
        assertFalse("biomeProviderClass reported null for AR dim " + arDim + ": " + info.raw(),
                info.biomeProviderClass().isEmpty());
    }

    @Test
    public void chunkGeneratorIsNonNull() throws Exception {
        int arDim = firstNonOverworldArDimOrSkip();
        DimInfo info = loadAndInfo(arDim);
        assertFalse("chunkGeneratorClass reported null for AR dim " + arDim + ": " + info.raw(),
                info.chunkGeneratorClass().isEmpty());
    }

    @Test
    public void saveFolderResolvesToExpectedPath() throws Exception {
        int arDim = firstNonOverworldArDimOrSkip();
        DimInfo info = loadAndInfo(arDim);
        // WorldProviderPlanet.getSaveFolder() returns "advRocketry/" + super.getSaveFolder().
        assertTrue("saveDir for AR planet " + arDim + " should be under advRocketry/: " + info.raw(),
                info.saveDir().startsWith("advRocketry/"));
    }

    @Test
    public void celestialAngleStableAcrossSameWorldTime() throws Exception {
        int arDim = firstNonOverworldArDimOrSkip();
        // The probe is a pure function of (dim, worldTime), so two calls with
        // identical inputs must produce identical angles. We compare extracted
        // numeric values rather than full response strings — the dedicated
        // server prefixes each console echo with a timestamp, so byte-level
        // response equality would race on tick boundaries.
        loadDim(arDim);
        double first = extractAngle(client().execute(
                "artest dim celestial-angle " + arDim + " 0"));
        double second = extractAngle(client().execute(
                "artest dim celestial-angle " + arDim + " 0"));

        assertEquals(
                "celestial-angle must be deterministic for identical inputs",
                first, second, 0.0);
    }

    @Test
    public void celestialAngleProgressesAcrossDifferentWorldTimes() throws Exception {
        int arDim = firstNonOverworldArDimOrSkip();
        loadDim(arDim);
        double a0 = extractAngle(client().execute(
                "artest dim celestial-angle " + arDim + " 0"));
        double a6k = extractAngle(client().execute(
                "artest dim celestial-angle " + arDim + " 6000"));
        double a12k = extractAngle(client().execute(
                "artest dim celestial-angle " + arDim + " 12000"));

        // Soft assertion: three meaningfully different world times must not
        // collapse to the same angle. The celestial cycle wraps modulo the
        // rotational period, so strict monotonicity isn't safe to assert
        // without first pinning AR's exact rotational-period math; that
        // belongs to a future test once the rocket assembly suite is in.
        assertNotEquals("celestial-angle did not change between t=0 and t=6000 (a0=" + a0
                + ", a6k=" + a6k + ")", a0, a6k, 0.0);
        assertNotEquals("celestial-angle did not change between t=6000 and t=12000 (a6k=" + a6k
                + ", a12k=" + a12k + ")", a6k, a12k, 0.0);
    }

    private int firstArDimOrSkip() throws Exception {
        String joined = String.join("\n", client().execute("artest dim list"));
        Assume.assumeFalse(
                "No AR dimensions registered — skipping (empty galaxy?)",
                (Reply.of(joined).arrayLength("arDimensions") == 0));
        int[] dims = Reply.of("artest dim list", joined).intArray(AR_DIM_PATTERN);
        assertTrue("could not parse first AR dim id from probe response: " + joined, dims.length > 0);
        return dims[0];
    }

    private int firstNonOverworldArDimOrSkip() throws Exception {
        String joined = String.join("\n", client().execute("artest dim list"));
        Assume.assumeFalse(
                "No AR dimensions registered — skipping (empty galaxy?)",
                (Reply.of(joined).arrayLength("arDimensions") == 0));
        Reply listed = Reply.of("artest dim list", joined);
        assertTrue("could not parse arDimensions array from probe response: " + joined,
                listed.has(AR_DIMS_ARRAY_PATTERN));
        Integer found = null;
        for (int dim : listed.intArray(AR_DIMS_ARRAY_PATTERN)) {
            if (dim != 0) {
                found = dim;
                break;
            }
        }
        Assume.assumeTrue(
                "Only overworld (dim 0) is registered as an AR planet — skipping " +
                        "WorldProviderPlanet-specific assertions",
                found != null);
        return found;
    }

    private DimInfo loadAndInfo(int dim) throws Exception {
        loadDim(dim);
        return DimInfo.forDim(cmd -> String.join("\n", client().execute(cmd)), dim);
    }

    private void loadDim(int dim) throws Exception {
        // Force the dim loaded before any property/angle probe — AR dims are
        // not in DimensionManager-loaded state on fresh boot.
        client().execute("artest dim load " + dim);
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
