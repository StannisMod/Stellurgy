package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.tile.multiblock.machine.TilePrecisionAssembler;
import zmaster587.libVulpes.recipe.RecipeMachineFactory;

public class RecipePrecisionAssembler extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TilePrecisionAssembler.class;
    }
}
