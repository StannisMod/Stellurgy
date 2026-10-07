package dev.stannismod.stellurgy.block;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import dev.stannismod.stellurgy.api.IShipActuatorBlock;
import dev.stannismod.stellurgy.ship.control.Actuator;
import dev.stannismod.stellurgy.ship.control.ReactionWheel;
import dev.stannismod.stellurgy.tile.TileReactionWheel;

/**
 * A reaction wheel block: turns the ship it is built into about any axis, pushes it nowhere, and
 * cannot hold a turn forever. What the device IS — its torques and its capacity — is
 * {@link ReactionWheel}'s; this block is where one stands, and the tile that keeps its spin.
 */
public class BlockReactionWheel extends Block implements IShipActuatorBlock {

    public BlockReactionWheel(Material material) {
        super(material);
    }

    @Override
    public void addActuators(World world, BlockPos pos, IBlockState state, List<Actuator> out) {
        out.addAll(ReactionWheel.at(pos.getX(), pos.getY(), pos.getZ()));
    }

    /** Nothing wears a wheel out yet; it works while it is aboard. */
    @Override
    public boolean isWorking(World world, BlockPos pos, IBlockState state) {
        return true;
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileReactionWheel();
    }
}
