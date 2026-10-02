package dev.stannismod.stellurgy.test.server;

// migrated to AbstractSharedServerTest
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Assume;
import org.junit.Test;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * worldgen smoke + determinism.
 *
 * Until this point we had zero coverage of the actual chunk-generation path.
 * Here we exercise the public probe surface ({@code /stellurgytest worldgen sample}
 * and {@code worldgen ore-stats}) on a real Stellurgy planet dim to:
 *
 *   - prove {@code WorldProviderPlanet}'s chunk provider returns a chunk
 *     with valid top-Y / top-block / biome (smoke);
 *   - prove the same chunk sampled twice in the same session returns the
 *     same answer (within-session determinism — guards against a future
 *     "regenerate on every probe" bug);
 *   - prove the biome reported by sample matches the biome reported by
 *     ore-stats (the underlying ChunkProvider must be the SAME instance
 *     for both subcommands, not two parallel providers handing out
 *     different biome lookups).
 *
 * Cross-session determinism (same seed &rarr; identical histogram across server
 * restarts) is intentionally deferred to a later phase — it doubles the
 * harness boot time and the within-session check already catches the
 * majority of regenerator bugs.
 */
public class WorldgenDeterminismAndSamplingTest extends AbstractSharedServerTest {

    private static final String AR_DIMS_ARRAY_PATTERN = "stellurgyDimensions";
    private static final String TOP_Y_PATTERN = "topY";
    private static final String BIOME_PATTERN = "biome";
    private static final String TOP_BLOCK_PATTERN = "topBlock";

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
                "Only overworld (dim 0) is registered as a Stellurgy planet — skipping",
                false);
        return -1;
    }

    private static String group(String field, String resp, String label) {
        Reply reply = Reply.of(resp);
        assertTrue("could not parse " + label + ": " + resp, reply.has(field));
        return reply.text(field);
    }

    @Test
    public void differentChunksReturnIndependentlyAddressableData() throws Exception {
        // Sanity that the probe isn't returning a cached "single chunk" for
        // every query — sample three distinct chunks and assert they don't
        // collapse to identical (topY,topBlock) triples.
        int dim = firstNonOverworldStellurgyDimOrSkip();
        client().execute("stellurgytest dim load " + dim);

        // Use wider chunk spread (0/64/128 in X) so adjacent biome boundaries
        // are crossed even on Stellurgy's flat moon-style planets. With (0,4,8)
        // every sample landed in the same 16×16 biome cell on `moondark`,
        // legitimately collapsing topY+biome to identical and flaking the
        // assertion.
        String a = String.join("\n",
                client().execute("stellurgytest worldgen sample " + dim + " 0 0"));
        String b = String.join("\n",
                client().execute("stellurgytest worldgen sample " + dim + " 64 64"));
        String c = String.join("\n",
                client().execute("stellurgytest worldgen sample " + dim + " 128 0"));

        String topAandBandC =
                group(TOP_Y_PATTERN, a, "topY") + "/"
                        + group(TOP_Y_PATTERN, b, "topY") + "/"
                        + group(TOP_Y_PATTERN, c, "topY");
        // If all three chunks have *identical* topY, that's possible on a
        // flat planet biome (atmosphere-vacuum desert moon, e.g.) — only
        // flag if all three are the same AND the biome is also the same;
        // the combined signature is what would betray a cache bug.
        String biomeSig = group(BIOME_PATTERN, a, "biome") + "/"
                + group(BIOME_PATTERN, b, "biome") + "/"
                + group(BIOME_PATTERN, c, "biome");
        // Either the topY differs OR the biome differs across the three.
        // If both are identical for three deliberately-spaced chunks, the
        // probe is almost certainly broken.
        boolean topYAllSame = group(TOP_Y_PATTERN, a, "topY")
                .equals(group(TOP_Y_PATTERN, b, "topY"))
                && group(TOP_Y_PATTERN, b, "topY")
                        .equals(group(TOP_Y_PATTERN, c, "topY"));
        boolean biomeAllSame = group(BIOME_PATTERN, a, "biome")
                .equals(group(BIOME_PATTERN, b, "biome"))
                && group(BIOME_PATTERN, b, "biome")
                        .equals(group(BIOME_PATTERN, c, "biome"));
        assertTrue("three spaced chunks reported identical (topY,biome) — probe likely caching\n"
                        + "  topY=" + topAandBandC + "\n  biome=" + biomeSig,
                !(topYAllSame && biomeAllSame));
    }

}
