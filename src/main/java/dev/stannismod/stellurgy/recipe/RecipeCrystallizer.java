package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.libvulpes.recipe.RecipeMachineFactory;
import dev.stannismod.stellurgy.tile.multiblock.machine.TileCrystallizer;

public class RecipeCrystallizer extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TileCrystallizer.class;
    }
}
