package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.tile.multiblock.machine.TileRollingMachine;
import zmaster587.libVulpes.recipe.RecipeMachineFactory;

public class RecipeRollingMachine extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TileRollingMachine.class;
    }
}
