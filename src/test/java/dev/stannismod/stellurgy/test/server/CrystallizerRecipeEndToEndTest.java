package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

/**
 * Crystallizer end-to-end recipe contract.
 */
public class CrystallizerRecipeEndToEndTest extends AbstractSharedServerTest {

    private static final String FIXTURE_KEY = "crystallizer";
    private static final String TILE_SHORT  = "TileCrystallizer";

    /** Pins INV-MBM-13 (a centrifuge and a crystallizer run a full recipe end to end). */
    @Test
    public void crystallizerRunsFirstRegisteredRecipe() throws Exception {
        MachineRecipeEndToEndKit.runFirstRecipeEndToEnd(client(),
                FIXTURE_KEY, TILE_SHORT, 500, 70, 400);
    }
}
