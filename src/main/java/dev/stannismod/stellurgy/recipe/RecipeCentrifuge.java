package dev.stannismod.stellurgy.recipe;

import dev.stannismod.stellurgy.libvulpes.recipe.RecipeMachineFactory;
import dev.stannismod.stellurgy.tile.multiblock.machine.TileCentrifuge;

public class RecipeCentrifuge extends RecipeMachineFactory {

    @Override
    public Class getMachine() {
        return TileCentrifuge.class;
    }
}
