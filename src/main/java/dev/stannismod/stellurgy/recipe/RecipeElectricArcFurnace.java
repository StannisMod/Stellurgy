package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.libvulpes.recipe.RecipeMachineFactory;
import dev.stannismod.stellurgy.tile.multiblock.machine.TileElectricArcFurnace;

public class RecipeElectricArcFurnace extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TileElectricArcFurnace.class;
    }
}
