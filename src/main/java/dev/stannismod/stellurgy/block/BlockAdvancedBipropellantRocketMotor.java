package dev.stannismod.stellurgy.block;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.tile.TileBrokenPart;

import javax.annotation.Nullable;

public class BlockAdvancedBipropellantRocketMotor extends BlockBipropellantRocketMotor {

    public BlockAdvancedBipropellantRocketMotor(Material mat) {
        super(mat);
    }

    @Override
    public int getThrust(World world, BlockPos pos) {
        return 50;
    }

    @Override
    public int getFuelConsumptionRate(World world, int x, int y, int z) {
        return 3;
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(final World worldIn, final IBlockState state) {
        return new TileBrokenPart(10, (float) StellurgyConfiguration.getCurrentConfig().increaseWearIntensityProb);
    }
}
