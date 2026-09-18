package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * terraforming smoke.
 *
 * Drives {@link
 * zmaster587.advancedRocketry.dimension.DimensionProperties#setAtmosphereDensity(int)}
 * and verifies that {@code currentAtmosphere} changes while {@code originalAtmosphere}
 * is preserved (terraforming reversibility invariant).
 */
public class TerraformingSmokeTest extends AbstractHeadlessServerTest {

    private static final String ORIG = "originalAtmosphere";
    private static final String CURRENT = "currentAtmosphere";

    @Test
    public void mutationKeepsOriginalDensityIntact() throws Exception {
        String before = String.join("\n", client().execute("artest terraforming info 0"));
        assertTrue("baseline terraforming info errored: " + before,
                !Reply.of(before).has("error"));

        Reply baseline = Reply.of("artest terraforming info", before);
        assertTrue("could not extract original/current from: " + before,
                baseline.has(ORIG) && baseline.has(CURRENT));
        int original = baseline.integer(ORIG);
        int currentBefore = baseline.integer(CURRENT);

        int target = currentBefore == 25 ? 75 : 25;
        try {
            String set = String.join("\n",
                    client().execute("artest terraforming set-density 0 " + target));
            assertTrue("set-density did not stick: " + set,
                    Reply.of(set).ok() && String.valueOf(target).equals(Reply.of(set).text("newDensity")));

            String after = String.join("\n", client().execute("artest terraforming info 0"));
            Reply mutated = Reply.of("artest terraforming info", after);
            assertTrue("could not extract from post-mutation: " + after,
                    mutated.has(ORIG) && mutated.has(CURRENT));

            assertEquals("currentAtmosphere did not move to " + target + ": " + after,
                    target, mutated.integer(CURRENT));
            assertEquals("originalAtmosphere unexpectedly mutated: " + after,
                    original, mutated.integer(ORIG));
            assertTrue("proxylists not reported: " + after,
                    after.contains("\"proxyInitialized\""));
        } finally {
            client().execute("artest terraforming set-density 0 " + currentBefore);
        }
    }
}
