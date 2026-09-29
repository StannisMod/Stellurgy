package dev.stannismod.stellurgy.tile.multiblock;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.libvulpes.api.LibVulpesBlocks;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiPowerConsumer;
import dev.stannismod.stellurgy.libvulpes.util.HashedBlockPosition;

public class TileBeacon extends TileMultiPowerConsumer {

    private static final Object[][][] structure = new Object[][][]
            {
                    {
                            {Blocks.AIR, Blocks.AIR, Blocks.AIR},
                            {Blocks.AIR, Blocks.REDSTONE_BLOCK, Blocks.AIR},
                            {Blocks.AIR, Blocks.AIR, Blocks.AIR}
                    },
                    {
                            {Blocks.AIR, Blocks.AIR, Blocks.AIR},
                            {Blocks.AIR, LibVulpesBlocks.blockStructureBlock, Blocks.AIR},
                            {Blocks.AIR, Blocks.AIR, Blocks.AIR}
                    },
                    {
                            {Blocks.AIR, Blocks.AIR, Blocks.AIR},
                            {Blocks.AIR, LibVulpesBlocks.blockStructureBlock, Blocks.AIR},
                            {Blocks.AIR, Blocks.AIR, Blocks.AIR}
                    },
                    {
                            {Blocks.AIR, Blocks.AIR, Blocks.AIR},
                            {Blocks.AIR, LibVulpesBlocks.blockStructureBlock, Blocks.AIR},
                            {Blocks.AIR, Blocks.AIR, Blocks.AIR}
                    },
                    {
                            {null, 'c', null},
                            {LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock},
                            {null, LibVulpesBlocks.blockStructureBlock, null}
                    }
            };

    @Override
    public Object[][][] getStructure() {
        return structure;
    }

    @Override
    public String getModularInventoryName() {
        return StellurgyBlocks.blockBeacon.getLocalizedName();
    }

    @Override
    public String getMachineName() {
        return getModularInventoryName();
    }

    @Override
    public void setMachineEnabled(boolean enabled) {
        super.setMachineEnabled(enabled);

        if (DimensionManager.getInstance().isDimensionCreated(world.provider.getDimension())) {
            DimensionProperties props = DimensionManager.getInstance().getDimensionProperties(world.provider.getDimension());
            if (enabled) {
                props.addBeaconLocation(world, new HashedBlockPosition(this.getPos()));
            } else
                props.removeBeaconLocation(world, new HashedBlockPosition(getPos()));
        }
    }

    @Override
    public boolean shouldHideBlock(World world, BlockPos pos, IBlockState tile) {
        return true;
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {

        return new AxisAlignedBB(pos.add(-5, -0, -5), pos.add(5, 5, 5));
    }
}
