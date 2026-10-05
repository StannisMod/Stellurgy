package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

/**
 * Lathe end-to-end recipe contract.
 */
public class LatheRecipeEndToEndTest extends AbstractSharedServerTest {

    private static final String FIXTURE_KEY = "lathe";
    private static final String TILE_SHORT  = "TileLathe";

    @Test
    public void latheRunsFirstRegisteredRecipe() throws Exception {
        MachineRecipeEndToEndKit.runFirstRecipeEndToEnd(client(),
                FIXTURE_KEY, TILE_SHORT, 500, 70, 400);
    }
}
