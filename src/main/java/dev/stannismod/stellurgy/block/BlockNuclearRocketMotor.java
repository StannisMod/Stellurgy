package dev.stannismod.stellurgy.block;

import dev.stannismod.stellurgy.api.IRocketNuclearCore;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.fuel.FuelRegistry;
import dev.stannismod.stellurgy.api.propulsion.EngineDesign;
import dev.stannismod.stellurgy.client.TooltipInjector;
import dev.stannismod.stellurgy.tile.TileBrokenPart;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public class BlockNuclearRocketMotor extends BlockRocketMotor {

    public BlockNuclearRocketMotor(Material mat) {
        super(mat);
    }

    /**
     * Effectively final, process lifetime: built once at class initialisation; an {@link EngineDesign} is immutable. 1 716 750 N rated through the nozzle; what it
     * really gives is held to its core stack's power (C10 STAT-30, STAT-36). Jet efficiency from
     * NERVA XE-Prime: 1 140 MW thermal for 246.7 kN at I_sp 710 s, so {@code F·v_e / 2P} = 0.753. No
     * nuclear engine was ever fired at sea level, so its bell is taken from an engine built to run
     * from the pad to vacuum, the RS-25 (1 859 of 2 279 kN at one atmosphere).
     */
    private static final EngineDesign DESIGN = EngineDesign.nuclearThermal(1_716_750, 0.753, 1859.0 / 2279.0);

    @Override
    public EngineDesign getEngineDesign(World world, BlockPos pos) {
        return DESIGN;
    }

    @Override
    protected FuelRegistry.FuelType propellantRole() {
        return FuelRegistry.FuelType.NUCLEAR_WORKING_FLUID;
    }

    @Override
    public void addActuators(World world, BlockPos pos, IBlockState state,
                             List<dev.stannismod.stellurgy.ship.control.Actuator> out) {
        ChemicalMotorActuators.addNuclear(world, pos, state, getEngineDesign(world, pos), out);
    }
    @Override
    public IBlockState getActualState(@Nonnull IBlockState state, IBlockAccess world, BlockPos pos) {
        // Prefer nuclear core adjacency over tanks
        if (world.getBlockState(pos.up()).getBlock() instanceof IRocketNuclearCore)
            return state.withProperty(FACING, EnumFacing.DOWN);
        if (world.getBlockState(pos.down()).getBlock() instanceof IRocketNuclearCore)
            return state.withProperty(FACING, EnumFacing.UP);
        if (world.getBlockState(pos.east()).getBlock() instanceof IRocketNuclearCore)
            return state.withProperty(FACING, EnumFacing.EAST);
        if (world.getBlockState(pos.west()).getBlock() instanceof IRocketNuclearCore)
            return state.withProperty(FACING, EnumFacing.WEST);
        if (world.getBlockState(pos.south()).getBlock() instanceof IRocketNuclearCore)
            return state.withProperty(FACING, EnumFacing.SOUTH);
        if (world.getBlockState(pos.north()).getBlock() instanceof IRocketNuclearCore)
            return state.withProperty(FACING, EnumFacing.NORTH);
        return state;
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(final World worldIn, final IBlockState state) {
        return new TileBrokenPart(10, 4 * (float) StellurgyConfiguration.getCurrentConfig().increaseWearIntensityProb);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        int insertAt = TooltipInjector.computeInsertIndex(tooltip, flag.isAdvanced());
        TooltipInjector.renderShiftAlt(stack, tooltip, "tooltip.stellurgy.nuclearmotor", insertAt);
    }
}
