package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.tile.multiblock.machine.TileElectricArcFurnace;
import zmaster587.libVulpes.recipe.RecipeMachineFactory;

public class RecipeElectricArcFurnace extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TileElectricArcFurnace.class;
    }
}
