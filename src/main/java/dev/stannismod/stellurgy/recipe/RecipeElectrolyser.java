package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.tile.multiblock.machine.TileElectrolyser;
import dev.stannismod.stellurgy.libvulpes.recipe.RecipeMachineFactory;

public class RecipeElectrolyser extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TileElectrolyser.class;
    }
}
