package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.tile.multiblock.machine.TileCuttingMachine;
import dev.stannismod.stellurgy.libvulpes.recipe.RecipeMachineFactory;

public class RecipeCuttingMachine extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TileCuttingMachine.class;
    }
}
