package dev.stannismod.stellurgy.block;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.propulsion.EngineDesign;
import dev.stannismod.stellurgy.tile.TileBrokenPart;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;

public class BlockAdvancedRocketMotor extends BlockRocketMotor {

    public BlockAdvancedRocketMotor(Material mat) {
        super(mat);
    }

    /**
     * Effectively final, process lifetime: built once at class initialisation; an {@link EngineDesign} is immutable. A higher chamber pressure behind the same
     * throat: five times the basic motor's rated thrust from one block. A catalytic monopropellant's
     * exhaust velocity does not rise with chamber pressure, so the gain is thrust density alone; the
     * bell is the basic motor's (Merlin 1D, 845 of 914 kN at one atmosphere).
     */
    private static final EngineDesign DESIGN = EngineDesign.chemical(2_452_500, 1.0, 845.0 / 914.0);

    @Override
    public EngineDesign getEngineDesign(World world, BlockPos pos) {
        return DESIGN;
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(final World worldIn, final IBlockState state) {
        return new TileBrokenPart(10, (float) StellurgyConfiguration.getCurrentConfig().increaseWearIntensityProb);
    }
}
