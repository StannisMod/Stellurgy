package dev.stannismod.stellurgy.probe;

import dev.stannismod.stellurgy.api.StellurgyItems;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Random;

/**
 * The first probe's wreck: a transmitter while it stands. Broken — by a player or by anything else — it
 * stops transmitting and gives up its flight recorder, never itself.
 */
public class BlockProbeWreck extends Block implements ITileEntityProvider {

    public BlockProbeWreck() {
        super(Material.IRON);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileProbeWreck();
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        if (!world.isRemote) {
            TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof TileProbeWreck) {
                spawnAsEntity(world, pos,
                        ((ItemFlightRecorder) StellurgyItems.itemFlightRecorder).holding(((TileProbeWreck) tile).record()));
            }
            ProbeFallDriver.transmitterStopped(world, pos);
        }
        super.breakBlock(world, pos, state);
    }

    @Override
    public Item getItemDropped(IBlockState state, Random rand, int fortune) {
        return Items.AIR;
    }
}
