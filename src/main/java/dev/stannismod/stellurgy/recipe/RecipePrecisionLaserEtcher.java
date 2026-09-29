package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.tile.multiblock.machine.TilePrecisionLaserEtcher;
import zmaster587.libVulpes.recipe.RecipeMachineFactory;

public class RecipePrecisionLaserEtcher extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TilePrecisionLaserEtcher.class;
    }
}
