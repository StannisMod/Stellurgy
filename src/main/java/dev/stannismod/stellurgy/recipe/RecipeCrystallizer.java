package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.tile.multiblock.machine.TileCrystallizer;
import zmaster587.libVulpes.recipe.RecipeMachineFactory;

public class RecipeCrystallizer extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TileCrystallizer.class;
    }
}
