package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


import dev.stannismod.stellurgy.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.exec;

/**
 * terraformer powered cycle on overworld with
 * {@code allowTerraformNonStellurgy=true} config flip.
 *
 * <p>Pins the <b>{@code allowTerraformNonStellurgy} branch</b> of
 * {@code TileAtmosphereTerraformer.processComplete()}'s gate:</p>
 *
 * <pre>{@code
 *     (WorldProviderPlanet && isNativeDimension) || allowTerraformNonStellurgy
 * }</pre>
 *
 * <p>Players running modpacks with {@code allowTerraformingNonStellurgyWorlds=true}
 * expect the terraformer to work on the overworld and any other non-Stellurgy
 * dim. Phase 1a pinned the native-planet branch (the default config);
 * this phase pins the explicitly-enabled override.</p>
 *
 * <p><b>State restoration</b>: each test snapshots {@code allowTerraformNonStellurgy}
 * and the overworld's current atmosphere density in {@code @Before}, then
 * restores both in {@code @After} — the shared harness is one JVM across
 * all methods of this class, so leaked config or density would corrupt
 * subsequent methods.</p>
 */
public class TerraformerPoweredCycleOnOverworldTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int CY = FixtureSite.OPEN_AIR_Y;
    private static final int CZ = 4000;
    private static final int CX_POSITIVE = 4000;
    private static final int CX_NEGATIVE = 4200;

    private static final String CONFIG_VALUE = "value";
    private static final String CURRENT_ATMOS = "currentAtmosphere";
    private static final String POWER_POS = "powerPos";
    /** All four 'L' hatches of the terraformer structure, as {@code [[x,y,z], …]}. */
    private static final String LIQUID_INPUT_POSITIONS = "liquidInputPositions";

    private boolean originalAllowNonStellurgy;
    private int originalDensity;

    @Before
    public void snapshotConfigAndDensity() throws Exception {
        originalAllowNonStellurgy = readBoolConfig("allowTerraformNonStellurgy");
        originalDensity = readDensity();
    }

    @After
    public void restoreConfigAndDensity() throws Exception {
        // Restore in BOTH cases — even if the config was never flipped this
        // method completes the round-trip cleanly.
        exec("stellurgytest config set allowTerraformNonStellurgy " + originalAllowNonStellurgy);
        exec("stellurgytest terraforming set-density " + DIM + " " + originalDensity);
    }

    /** With the config flipped, an overworld-placed terraformer with fuel +
     *  power must mutate dim 0's atmosphere density. */
    @Test
    public void overworldTerraformerWithNonStellurgyConfigFlipStepsDensity() throws Exception {
        String flip = exec("stellurgytest config set allowTerraformNonStellurgy true");
        assertTrue("config flip failed: " + flip,
                Reply.of(flip).ok() && Reply.of(flip).bool("newValue"));

        String fixture = buildAndCompleteFixture(CX_POSITIVE);
        injectPower(fixture, 30_000_000);
        enableMachine(CX_POSITIVE);

        int densityBefore = readDensity();
        runRefillCycle(fixture, CX_POSITIVE, 60, 400);
        int densityAfter = readDensity();

        assertNotEquals("non-Stellurgy-config-flipped terraformer did not move density"
                        + " on overworld (before=" + densityBefore
                        + " after=" + densityAfter + ")",
                densityBefore, densityAfter);
    }

    /** Counter-test: with the default config ({@code allowTerraformNonStellurgy=false}),
     *  the same fuel+power+tick combination on overworld must NOT move
     *  density — the dim-check gate is the sole reason. */
    @Test
    public void overworldTerraformerWithoutConfigFlipDoesNotStep() throws Exception {
        // Explicit set to false (idempotent with the default) so a stale
        // value from a sibling test or harness boot can't masquerade as
        // a passing default-branch test.
        String set = exec("stellurgytest config set allowTerraformNonStellurgy false");
        assertTrue("config set-false failed: " + set,
                Reply.of(set).ok());

        String fixture = buildAndCompleteFixture(CX_NEGATIVE);
        injectPower(fixture, 30_000_000);
        enableMachine(CX_NEGATIVE);

        int densityBefore = readDensity();
        runRefillCycle(fixture, CX_NEGATIVE, 60, 400);
        int densityAfter = readDensity();

        assertEquals("default-config terraformer moved overworld density anyway"
                        + " (before=" + densityBefore + " after=" + densityAfter + ")"
                        + " — gate branch ((WorldProviderPlanet && isNative) ||"
                        + " allowTerraformNonStellurgy) leaked through",
                densityBefore, densityAfter);
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private String buildAndCompleteFixture(int cx) throws Exception {
        String fixture = exec("stellurgytest fixture multiblock terraformer "
                + DIM + " " + cx + " " + CY + " " + CZ);
        assertTrue("terraformer fixture build failed: " + fixture,
                Reply.of(fixture).ok() && (Reply.of(fixture).integer("unresolved") == 0));
        String tryComplete = exec("stellurgytest machine try-complete "
                + DIM + " " + cx + " " + CY + " " + CZ);
        assertTrue("terraformer structure failed to complete: " + tryComplete,
                Reply.of(tryComplete).bool("isComplete"));
        return fixture;
    }

    private void injectPower(String fixture, int amount) throws Exception {
        int[] m = Reply.of(fixture).blockPos(POWER_POS);
        assertTrue("no powerPos in fixture response: " + fixture, m != null);
        int px = m[0];
        int py = m[1];
        int pz = m[2];
        String resp = exec("stellurgytest energy inject "
                + DIM + " " + px + " " + py + " " + pz + " " + amount);
        assertTrue("energy inject failed: " + resp, Reply.of(resp).ok());
    }

    private void enableMachine(int cx) throws Exception {
        String resp = exec("stellurgytest machine set-enabled "
                + DIM + " " + cx + " " + CY + " " + CZ + " true");
        assertTrue("machine set-enabled failed: " + resp, Reply.of(resp).bool("enabled"));
    }

    private void runRefillCycle(String fixture, int cx, int iterations, int ticksPerIter)
            throws Exception {
        for (int i = 0; i < iterations; i++) {
            injectFluidAt(fixture, 0, "nitrogen", 16000);
            injectFluidAt(fixture, 1, "nitrogen", 16000);
            injectFluidAt(fixture, 2, "oxygen", 16000);
            injectFluidAt(fixture, 3, "oxygen", 16000);
            String tick = exec("stellurgytest tile force-tick "
                    + DIM + " " + cx + " " + CY + " " + CZ + " " + ticksPerIter);
            assertTrue("force-tick errored on iter " + i + ": " + tick,
                    Reply.of(tick).ok());
        }
    }

    private void injectFluidAt(String fixture, int hatchIndex, String fluidName, int amount)
            throws Exception {
        int[][] hatches = Reply.of("stellurgytest fixture machine", fixture)
                .blockPosArray(LIQUID_INPUT_POSITIONS);
        assertTrue("liquidInputPositions has fewer than " + (hatchIndex + 1)
                + " hatches: " + fixture, hatchIndex < hatches.length);
        int lx = hatches[hatchIndex][0];
        int ly = hatches[hatchIndex][1];
        int lz = hatches[hatchIndex][2];
        String resp = exec("stellurgytest fluid inject "
                + DIM + " " + lx + " " + ly + " " + lz + " " + fluidName + " " + amount);
        assertTrue(fluidName + " inject failed at hatch " + hatchIndex + ": " + resp,
                Reply.of(resp).ok());
    }

    private int readDensity() throws Exception {
        String info = exec("stellurgytest terraforming info " + DIM);
        Reply mReply = Reply.of(info);
        assertTrue("no currentAtmosphere in terraforming info: " + info, mReply.has(CURRENT_ATMOS));
        return Integer.parseInt(mReply.text(CURRENT_ATMOS));
    }

    private boolean readBoolConfig(String key) throws Exception {
        String resp = exec("stellurgytest config get " + key);
        Reply mReply = Reply.of(resp);
        assertTrue("config get " + key + " did not yield value: " + resp, mReply.has(CONFIG_VALUE));
        return Boolean.parseBoolean(mReply.text(CONFIG_VALUE));
    }
}
