package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.tile.multiblock.machine.TilePrecisionLaserEtcher;
import dev.stannismod.stellurgy.libvulpes.recipe.RecipeMachineFactory;

public class RecipePrecisionLaserEtcher extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TilePrecisionLaserEtcher.class;
    }
}
