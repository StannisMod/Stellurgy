package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.Test;

/**
 * Crystallizer end-to-end recipe contract.
 */
public class CrystallizerRecipeEndToEndTest extends AbstractSharedServerTest {

    private static final String FIXTURE_KEY = "crystallizer";
    private static final String TILE_SHORT  = "TileCrystallizer";

    /** Pins INV-MBM-13 (a centrifuge and a crystallizer run a full recipe end to end). */
    @Pins("INV-MBM-13")
    @RedWitness(member = "TileCrystallizer#onRunningPoweredTick", at = "if (isGravityWithinBounds())",
            replacedBy = "if (!isGravityWithinBounds())",
            fails = "expected output libvulpes:productgem not in the output hatch for crystallizer", on = "2026-10-19")
    @Test
    public void crystallizerRunsFirstRegisteredRecipe() throws Exception {
        MachineRecipeEndToEndKit.runFirstRecipeEndToEnd(client(),
                FIXTURE_KEY, TILE_SHORT, 500, 70, 400);
    }
}
