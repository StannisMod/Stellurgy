package dev.stannismod.stellurgy.block;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import dev.stannismod.stellurgy.api.IFuelTank;

public class BlockNuclearFuelTank extends BlockFuelTank implements IFuelTank {

    public BlockNuclearFuelTank(Material mat) {
        super(mat);
    }

    @Override
    public int getMaxFill(World world, BlockPos pos, IBlockState state) {
        return 1000;
    }
}
