package dev.stannismod.stellurgy.block;

import dev.stannismod.stellurgy.api.IRocketNuclearCore;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.client.TooltipInjector;
import dev.stannismod.stellurgy.item.ItemNuclearFuelElement;
import dev.stannismod.stellurgy.tile.TileNuclearCore;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.InventoryHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A reactor core for nuclear-thermal nozzles, rated in thermal power and temperature, fuelled by one
 * fuel element (C10 STAT-35). Right-click with an element to load it; right-click with an empty hand
 * to take out what it holds — spent fuel included, which only leaves this way or through a hopper.
 */
public class BlockNuclearCore extends Block implements IRocketNuclearCore {

    /** W: a NERVA-class reactor (NRX / XE, about 1 100 MW thermal). */
    public static final double RATED_THERMAL_POWER = 1.1e9;
    /** K: the core temperature the exhaust-velocity anchor was taken at (NuclearThermal). */
    public static final double CORE_TEMPERATURE = 2_700.0;

    public BlockNuclearCore(Material mat) {
        super(mat);
    }

    @Nullable
    private static TileNuclearCore core(IBlockAccess world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileNuclearCore ? (TileNuclearCore) te : null;
    }

    @Override
    public double getThermalPower(IBlockAccess world, BlockPos pos) {
        TileNuclearCore core = core(world, pos);
        return core != null && core.isFuelled()
                ? RATED_THERMAL_POWER * StellurgyConfiguration.getCurrentConfig().nuclearCorePowerRatio : 0;
    }

    @Override
    public double getCoreTemperature(IBlockAccess world, BlockPos pos) {
        return CORE_TEMPERATURE;
    }

    @Override
    public double produce(IBlockAccess world, BlockPos pos, double joules) {
        TileNuclearCore core = core(world, pos);
        return core == null ? Math.max(0, joules) : core.produce(joules);
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileNuclearCore();
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand,
                                    EnumFacing facing, float hitX, float hitY, float hitZ) {
        TileNuclearCore core = core(world, pos);
        if (core == null) {
            return false;
        }
        if (world.isRemote) {
            return true;
        }
        ItemStack held = player.getHeldItem(hand);
        if (held.getItem() instanceof ItemNuclearFuelElement) {
            player.setHeldItem(hand, core.fuel().insertItem(0, held, false));
        } else if (held.isEmpty()) {
            player.setHeldItem(hand, core.fuel().extractItem(0, 1, false));
        }
        return true;
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        TileNuclearCore core = core(world, pos);
        if (core != null && !core.fuel().getStackInSlot(0).isEmpty()) {
            InventoryHelper.spawnItemStack(world, pos.getX(), pos.getY(), pos.getZ(), core.fuel().getStackInSlot(0));
        }
        super.breakBlock(world, pos, state);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        int insertAt = TooltipInjector.computeInsertIndex(tooltip, flag.isAdvanced());
        TooltipInjector.renderShiftAlt(stack, tooltip, "tooltip.stellurgy.nuclearcore", insertAt);
    }
}
