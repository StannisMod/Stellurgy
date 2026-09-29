package dev.stannismod.stellurgy.tile.atmosphere;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.block.BlockSeal;

public class TileSeal extends TileEntity implements ITickable {

    boolean ticked = false;

    @Override
    public void onChunkUnload() {
        ((BlockSeal) StellurgyBlocks.blockPipeSealer).removeSeal(getWorld(), getPos());
        ticked = false;
    }

    @Override
    public void update() {
        if (!world.isRemote && !ticked && !isInvalid()) {
            for (EnumFacing dir : EnumFacing.VALUES) {
                ((BlockSeal) StellurgyBlocks.blockPipeSealer).fireCheckAllDirections(getWorld(), pos.offset(dir), dir);
            }
            ticked = true;
        }
    }
}
