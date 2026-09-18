package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.DimInfo;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A planet dimension's chunk generator is chosen by its {@code terrainSource}, while in every mode the
 * dimension stays a {@link zmaster587.advancedRocketry.world.provider.WorldProviderPlanet} and keeps its
 * Advanced Rocketry atmosphere and gravity — those are dim-keyed and orthogonal to the chunk generator.
 *
 * <p>Each case clones an existing AR planet into a fresh dimension with a flipped terrainSource via the
 * {@code /artest worldgen create-terrain-dim} probe, loads it, and reads {@code dim info}:</p>
 * <ul>
 *   <li>MOD_WORLDTYPE {@code flat} dispatches to the foreign {@code ChunkGeneratorFlat}, yet the provider
 *       stays WorldProviderPlanet and gravity is preserved.</li>
 *   <li>TEMPLATE with no source region files dispatches to {@code ChunkProviderTemplate} and yields a void
 *       world (no stone across the scanned region).</li>
 *   <li>MOD_WORLDTYPE with an unregistered world-type name degrades to the native AR generator instead of
 *       crashing.</li>
 * </ul>
 */
public class PlanetTerrainSourceE2ETest extends AbstractSharedServerTest {

    // Below Constants.STAR_ID_OFFSET (10000): at/above it, DimensionManager.getDimensionProperties treats
    // the id as a star proxy and returns the overworld props, so a real planet dim must live under it.
    private static final int MOD_WT_DIM = 9990;
    private static final int TEMPLATE_DIM = 9991;
    private static final int FALLBACK_DIM = 9992;
    private static final int OPTIONS_DIM = 9993;

    /** The registered name of {@code AdvancedRocketry.planetWorldType} (see {@code WorldTypePlanetGen}). */
    private static final String AR_PLANET_WORLD_TYPE = "PlanetGen";

    /** A flat preset no default world could produce, so "the options arrived" is visible in blocks. */
    private static final String FLAT_DIAMOND_PRESET = "3;minecraft:bedrock,3*minecraft:diamond_block;1";

    /** The provider an AR planet runs — a class NAME, compared against the field that holds one. */
    private static final String AR_PLANET_PROVIDER =
            "zmaster587.advancedRocketry.world.provider.WorldProviderPlanet";

    private static final String AR_DIMS_ARRAY = "arDimensions";
    private static final String COUNT = "count";

    /** What the server says about one dimension, read through the verb's own reader. */
    private static DimInfo dimInfo(int dim) throws Exception {
        return DimInfo.forDim(WorldCommandFixtures::exec, dim);
    }

    @Test
    public void modWorldtypeUsesForeignGeneratorButStaysAnArPlanet() throws Exception {
        int template = firstTemplateArDimOrSkip();
        String create = exec("artest worldgen create-terrain-dim "
                + MOD_WT_DIM + " " + template + " MOD_WORLDTYPE flat");
        assertTrue("create-terrain-dim must succeed: " + create, Reply.of(create).ok());

        exec("artest dim load " + MOD_WT_DIM);
        DimInfo info = dimInfo(MOD_WT_DIM);

        assertEquals("the loaded world must see the authored terrainSource: " + info.raw(),
                "MOD_WORLDTYPE", info.terrainSource());
        assertEquals("MOD_WORLDTYPE dim must stay a WorldProviderPlanet: " + info.raw(),
                AR_PLANET_PROVIDER, info.providerClass());
        // Asked of the GENERATOR's field. The `contains` this replaces scanned the whole reply, in
        // which four other fields also carry class names and paths.
        assertTrue("MOD_WORLDTYPE 'flat' must dispatch to the foreign ChunkGeneratorFlat: "
                        + info.raw(),
                info.chunkGeneratorClass().endsWith("ChunkGeneratorFlat"));

        // Orthogonality: AR gravity is dim-keyed, so it must survive under the foreign generator.
        assertTrue("AR gravity must be preserved under MOD_WORLDTYPE, got " + info.gravity(),
                info.gravity() > 0);
    }

    @Test
    public void templateWithNoSourceGeneratesVoidViaTemplateGenerator() throws Exception {
        int template = firstTemplateArDimOrSkip();
        String create = exec("artest worldgen create-terrain-dim "
                + TEMPLATE_DIM + " " + template + " TEMPLATE noSuchTemplate");
        assertTrue("create-terrain-dim must succeed: " + create, Reply.of(create).ok());

        exec("artest dim load " + TEMPLATE_DIM);
        DimInfo info = dimInfo(TEMPLATE_DIM);
        assertEquals("the loaded world must see the authored terrainSource: " + info.raw(),
                "TEMPLATE", info.terrainSource());
        assertEquals("TEMPLATE dim must stay a WorldProviderPlanet: " + info.raw(),
                AR_PLANET_PROVIDER, info.providerClass());
        assertEquals("TEMPLATE must dispatch to ChunkProviderTemplate: " + info.raw(),
                "zmaster587.advancedRocketry.world.ChunkProviderTemplate",
                info.chunkGeneratorClass());

        // No source region files -> void: the scanned region has no fill (stone) blocks.
        String stats = exec("artest worldgen ore-stats " + TEMPLATE_DIM + " 0 0 2 minecraft:stone");
        Reply mReply = Reply.of(stats);
        assertTrue("ore-stats must report a count: " + stats, mReply.has(COUNT));
        int count = Integer.parseInt(mReply.text(COUNT));
        assertTrue("a TEMPLATE dim with no source region files must generate a void world "
                + "(0 stone across the scanned region); count=" + count + " stats=" + stats, count == 0);
    }

    @Test
    public void unregisteredModWorldtypeFallsBackToNativeGenerator() throws Exception {
        int template = firstTemplateArDimOrSkip();
        String create = exec("artest worldgen create-terrain-dim "
                + FALLBACK_DIM + " " + template + " MOD_WORLDTYPE definitelyNotAWorldType");
        assertTrue("create-terrain-dim must succeed: " + create, Reply.of(create).ok());

        exec("artest dim load " + FALLBACK_DIM);
        DimInfo info = dimInfo(FALLBACK_DIM);

        assertEquals("fallback dim must stay a WorldProviderPlanet: " + info.raw(),
                AR_PLANET_PROVIDER, info.providerClass());
        String generator = info.chunkGeneratorClass();
        assertTrue("an unregistered MOD_WORLDTYPE name must fall back to a native AR generator: "
                        + info.raw(),
                generator.equals("zmaster587.advancedRocketry.world.ChunkProviderPlanet")
                        || generator.equals(
                                "zmaster587.advancedRocketry.world.ChunkProviderCavePlanet"));
        assertFalse("the fallback must NOT use the foreign flat generator: " + info.raw(),
                generator.endsWith("ChunkGeneratorFlat"));
    }

    /**
     * A planet publishes ITS OWN world-generation identity through the vanilla {@code WorldInfo}
     * API, because that is the channel a third-party {@code WorldType} reads when it identifies and
     * configures itself — Advanced Rocketry cannot patch a foreign generator's read sites.
     *
     * <p>Vanilla's secondary-world {@code WorldInfo} answers this about the OVERWORLD: the getter
     * delegates and the setter is an empty method, so a planet's own stamp used to be dropped in
     * silence. The overworld's value is reported beside the planet's here to keep the assertion
     * honest — the two must now DIFFER, which is only meaningful because both name a real type.</p>
     */
    @Test
    public void planetPublishesItsOwnWorldTypeThroughWorldInfo() throws Exception {
        int planet = firstTemplateArDimOrSkip();
        exec("artest dim load " + planet);
        DimInfo info = dimInfo(planet);

        assertTrue("the dim must be loaded, or every field below is about a world that is not there: "
                + info.raw(), info.loaded);
        assertEquals("this case is about a NATIVE planet: " + info.raw(),
                "NATIVE", info.terrainSource());
        // Both values must name something REAL before they are compared: two absences would read as
        // agreement, and a comparison of two sources that are equal because neither exists cannot
        // fail. The reader refuses the probe's "null" marker for either, which is that guarantee.
        String published = info.worldType();
        String overworld = info.overworldWorldType();
        assertNamesAWorldType("worldType", published);
        assertNamesAWorldType("overworldWorldType", overworld);

        assertEquals("a NATIVE planet generates with AR's own world type and must say so: "
                        + info.raw(), AR_PLANET_WORLD_TYPE, published);
        assertFalse("the planet must no longer be answering with the SAVE's world type: "
                        + info.raw(), overworld.equals(published));
    }

    /**
     * The generator-options channel, which is what makes third-party terrain more than decorative:
     * a planet's chunk generator is configured from the planet's own settings string instead of the
     * empty one a secondary world's {@code WorldInfo} used to hand out.
     *
     * <p>Asserted at three depths, because the first two alone would pass on a build where the
     * string is published but never reaches the generator: the published value, the world type the
     * dimension runs, and the BLOCKS on the ground. The preset below is deliberately absurd — three
     * layers of diamond — so the last assertion cannot be satisfied by any default flat world.</p>
     */
    @Test
    public void modWorldtypePlanetConfiguresItsForeignGeneratorFromItsOwnOptions() throws Exception {
        int template = firstTemplateArDimOrSkip();
        String create = exec("artest worldgen create-terrain-dim "
                + OPTIONS_DIM + " " + template + " MOD_WORLDTYPE flat " + FLAT_DIAMOND_PRESET);
        assertTrue("create-terrain-dim must succeed: " + create, Reply.of(create).ok());

        exec("artest dim load " + OPTIONS_DIM);
        DimInfo info = dimInfo(OPTIONS_DIM);

        assertTrue("the dim must actually be running the foreign generator, else there is no "
                        + "options channel to measure: " + info.raw(),
                info.chunkGeneratorClass().endsWith("ChunkGeneratorFlat"));
        assertNamesAWorldType("worldType", info.worldType());
        assertEquals("a MOD_WORLDTYPE planet must publish the foreign world type it actually runs: "
                        + info.raw(), "flat", info.worldType());
        assertEquals("the planet must publish its OWN generator options: " + info.raw(),
                FLAT_DIAMOND_PRESET, info.generatorOptions());
        assertEquals("the save-global options string must be untouched — this is a per-dimension "
                        + "channel, not a write to the overworld: " + info.raw(),
                "", info.overworldGeneratorOptions());

        // The player-visible half: the authored preset is what the generator actually built.
        String stats = exec("artest worldgen ore-stats " + OPTIONS_DIM + " 0 0 2 minecraft:diamond_block");
        Reply mReply = Reply.of(stats);
        assertTrue("ore-stats must report a count: " + stats, mReply.has(COUNT));
        int count = Integer.parseInt(mReply.text(COUNT));
        assertTrue("the authored flat preset must be the terrain that got generated; a default flat "
                + "world has no diamond in it at all. count=" + count + " stats=" + stats, count > 0);
    }

    /** A reported world-type name must be a real registered name, not an absence dressed as one. */
    private static void assertNamesAWorldType(String key, String value) {
        assertFalse(key + " must name a world type, got the probe's null marker", "null".equals(value));
        assertFalse(key + " must name a world type, got an empty string", value.isEmpty());
    }

    /** A registered non-overworld AR planet to clone, excluding the dims this test creates. */
    private int firstTemplateArDimOrSkip() throws Exception {
        String joined = exec("artest dim list");
        Assume.assumeFalse("No AR dimensions registered — skipping",
                (Reply.of(joined).arrayLength("arDimensions") == 0));
        Reply dims = Reply.of("artest dim list", joined);
        assertTrue("could not parse arDimensions: " + joined, dims.has(AR_DIMS_ARRAY));
        for (int dim : dims.intArray(AR_DIMS_ARRAY)) {
            if (dim != 0 && dim != MOD_WT_DIM && dim != TEMPLATE_DIM && dim != FALLBACK_DIM) return dim;
        }
        Assume.assumeTrue("Only overworld registered — skipping", false);
        return -1;
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }
}
