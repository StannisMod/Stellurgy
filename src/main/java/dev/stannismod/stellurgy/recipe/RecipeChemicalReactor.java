package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.libvulpes.recipe.RecipeMachineFactory;
import dev.stannismod.stellurgy.tile.multiblock.machine.TileChemicalReactor;

public class RecipeChemicalReactor extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TileChemicalReactor.class;
    }
}
