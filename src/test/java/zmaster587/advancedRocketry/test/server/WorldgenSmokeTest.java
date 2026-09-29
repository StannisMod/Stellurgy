package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * worldgen + ore generation.
 *
 * <ol>
 *   <li>Earth chunk (0,0) must have a non-air top block.</li>
 *   <li>9-chunk window must contain >50 bedrock (vanilla).</li>
 *   <li>9-chunk window must contain >0 iron ore (AR oregen tripwire).</li>
 * </ol>
 */
public class WorldgenSmokeTest extends AbstractHeadlessServerTest {

    /** The smallest bedrock count a vanilla chunk column may plausibly hold — the test's own bar
     *  on "worldgen ran at all", far under what a real column carries. */
    private static final long MIN_BEDROCK = 50L;

    // Generated terrain IS this test's subject, and it is what the harness hands out by default.
    // Do not give this class requiresFlatTerrain(): a flat world has no decoration pass, so its
    // iron count is zero and the AR oregen tripwire below would measure the preset, not the
    // generator.

    private static final String COUNT = "count";
    private static final String CHUNKS = "chunksScanned";

    @Test
    public void earthChunkAndOreCountsLookSane() throws Exception {
        String sample = String.join("\n", client().execute("artest worldgen sample 0 0 0"));
        assertTrue("worldgen sample failed: " + sample, !Reply.of(sample).has("error"));
        assertTrue("worldgen reports air on top — generator likely crashed: " + sample,
                !"minecraft:air".equals(Reply.of(sample).text("topBlock")));

        String bedrock = String.join("\n", client().execute(
                "artest worldgen ore-stats 0 0 0 1 minecraft:bedrock"));
        assertTrue("ore-stats bedrock failed: " + bedrock, !Reply.of(bedrock).has("error"));
        assertEquals("expected 9 chunks scanned", 9L, parseLong(CHUNKS, bedrock));
        long bedrockCount = parseLong(COUNT, bedrock);
        assertTrue("vanilla bedrock count too low: " + bedrockCount + " in " + bedrock,
                bedrockCount >= MIN_BEDROCK);

        String iron = String.join("\n", client().execute(
                "artest worldgen ore-stats 0 0 0 1 minecraft:iron_ore"));
        long ironCount = parseLong(COUNT, iron);
        assertTrue("iron ore count=0 in 9-chunk window — oregen broken? " + iron,
                ironCount > 0L);
    }

    private static long parseLong(String field, String s) {
        return (long) Reply.of(s).number(field);
    }
}
