package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

/**
 * Chemical Reactor end-to-end recipe contract.
 */
public class ChemicalReactorRecipeEndToEndTest extends AbstractSharedServerTest {

    private static final String FIXTURE_KEY = "chemical-reactor";
    private static final String TILE_SHORT  = "TileChemicalReactor";

    @Test
    public void chemicalReactorRunsFirstRegisteredRecipe() throws Exception {
        MachineRecipeEndToEndKit.runFirstRecipeEndToEnd(client(),
                FIXTURE_KEY, TILE_SHORT, 500, 70, 400);
    }
}
