package dev.stannismod.stellurgy.tile.hatch;

import dev.stannismod.stellurgy.libvulpes.tile.multiblock.hatch.TileInventoryHatch;

public class TileInvHatch extends TileInventoryHatch {

    public TileInvHatch(int invSize) {
        super(invSize);
    }

    @Override
    public String getModularInventoryName() {
        return "container.invhatch";
    }
}
