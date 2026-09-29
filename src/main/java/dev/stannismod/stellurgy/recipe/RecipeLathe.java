package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.tile.multiblock.machine.TileLathe;
import dev.stannismod.stellurgy.libvulpes.recipe.RecipeMachineFactory;

public class RecipeLathe extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TileLathe.class;
    }
}
