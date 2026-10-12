package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.libvulpes.recipe.RecipeMachineFactory;
import dev.stannismod.stellurgy.tile.multiblock.machine.TilePrecisionLaserEtcher;

public class RecipePrecisionLaserEtcher extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TilePrecisionLaserEtcher.class;
    }
}
