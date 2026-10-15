package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.libvulpes.recipe.RecipeMachineFactory;
import dev.stannismod.stellurgy.tile.multiblock.machine.TileElectrolyser;

public class RecipeElectrolyser extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TileElectrolyser.class;
    }
}
