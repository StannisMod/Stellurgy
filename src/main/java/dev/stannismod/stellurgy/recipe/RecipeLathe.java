package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.libvulpes.recipe.RecipeMachineFactory;
import dev.stannismod.stellurgy.tile.multiblock.machine.TileLathe;

public class RecipeLathe extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TileLathe.class;
    }
}
