package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.tile.multiblock.machine.TileCentrifuge;
import dev.stannismod.stellurgy.libvulpes.recipe.RecipeMachineFactory;

public class RecipeCentrifuge extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TileCentrifuge.class;
    }
}
