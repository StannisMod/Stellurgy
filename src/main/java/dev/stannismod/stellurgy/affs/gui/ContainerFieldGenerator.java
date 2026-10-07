package dev.stannismod.stellurgy.affs.gui;

import dev.stannismod.stellurgy.affs.te.TileEntityFieldGenerator;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

public class ContainerFieldGenerator extends Container {

    private final TileEntityFieldGenerator tile;

    public ContainerFieldGenerator(EntityPlayer player, TileEntityFieldGenerator tile) {
        this.tile = tile;
    }

    /**
     * The generator this screen shows, when it is the one at {@code pos} and {@code player} may still
     * use it ({@link #canInteractWith}); otherwise {@code null}.
     */
    @Nullable
    public TileEntityFieldGenerator generatorAt(BlockPos pos, EntityPlayer player) {
        return tile != null && tile.getPos().equals(pos) && canInteractWith(player) ? tile : null;
    }

    @Override
    public boolean canInteractWith(EntityPlayer playerIn) {
        return tile != null
                && !tile.isInvalid()
                && tile.getWorld() != null
                && tile.getWorld().getTileEntity(tile.getPos()) == tile
                && playerIn.getDistanceSq(
                    tile.getPos().getX() + 0.5D,
                    tile.getPos().getY() + 0.5D,
                    tile.getPos().getZ() + 0.5D
                ) <= 64.0D;
    }
}
