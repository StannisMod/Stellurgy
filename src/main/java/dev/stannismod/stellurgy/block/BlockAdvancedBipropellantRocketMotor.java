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

public class BlockAdvancedBipropellantRocketMotor extends BlockBipropellantRocketMotor {

    public BlockAdvancedBipropellantRocketMotor(Material mat) {
        super(mat);
    }

    /**
     * Effectively final, process lifetime: built once at class initialisation; an {@link EngineDesign} is immutable. A staged-combustion cycle at a higher chamber
     * pressure (RS-25, 206 bar): five times the basic motor's rated thrust, the pair's full exhaust
     * velocity (the row IS the RS-25's), and the RS-25's bell, 1 859 of 2 279 kN at one atmosphere.
     */
    private static final EngineDesign DESIGN = EngineDesign.chemical(2_452_500, 1.0, 1859.0 / 2279.0);

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
