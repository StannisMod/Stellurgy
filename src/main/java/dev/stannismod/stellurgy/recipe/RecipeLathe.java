package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.tile.multiblock.machine.TileLathe;
import zmaster587.libVulpes.recipe.RecipeMachineFactory;

public class RecipeLathe extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TileLathe.class;
    }
}
