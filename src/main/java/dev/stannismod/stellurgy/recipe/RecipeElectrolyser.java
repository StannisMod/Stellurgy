package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.tile.multiblock.machine.TileElectrolyser;
import zmaster587.libVulpes.recipe.RecipeMachineFactory;

public class RecipeElectrolyser extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TileElectrolyser.class;
    }
}
