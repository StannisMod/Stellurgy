package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.tile.multiblock.machine.TileRollingMachine;
import dev.stannismod.stellurgy.libvulpes.recipe.RecipeMachineFactory;

public class RecipeRollingMachine extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TileRollingMachine.class;
    }
}
