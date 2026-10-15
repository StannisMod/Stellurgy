package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.libvulpes.recipe.RecipeMachineFactory;
import dev.stannismod.stellurgy.tile.multiblock.machine.TilePrecisionAssembler;

public class RecipePrecisionAssembler extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TilePrecisionAssembler.class;
    }
}
