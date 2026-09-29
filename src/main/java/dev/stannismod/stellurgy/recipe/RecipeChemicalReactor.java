package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.tile.multiblock.machine.TileChemicalReactor;
import zmaster587.libVulpes.recipe.RecipeMachineFactory;

public class RecipeChemicalReactor extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TileChemicalReactor.class;
    }
}
