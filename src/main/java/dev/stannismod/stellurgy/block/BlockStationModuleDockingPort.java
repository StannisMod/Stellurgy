package dev.stannismod.stellurgy.block;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import dev.stannismod.stellurgy.client.TooltipInjector;
import dev.stannismod.stellurgy.tile.station.TileDockingPort;
import dev.stannismod.stellurgy.tile.station.TileLandingPad;
import dev.stannismod.stellurgy.libvulpes.block.BlockFullyRotatable;
import dev.stannismod.stellurgy.libvulpes.inventory.GuiHandler;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.ParametersAreNullableByDefault;
import dev.stannismod.stellurgy.Stellurgy;

public class BlockStationModuleDockingPort extends BlockFullyRotatable {

    public BlockStationModuleDockingPort(Material par2Material) {
        super(par2Material);
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    @ParametersAreNullableByDefault
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileDockingPort();
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos,
                                    IBlockState state, EntityPlayer playerIn, EnumHand hand, EnumFacing side, float hitX, float hitY,
                                    float hitZ) {
        if (!worldIn.isRemote)
            playerIn.openGui(Stellurgy.instance, GuiHandler.guiId.MODULAR.ordinal(), worldIn, pos.getX(), pos.getY(), pos.getZ());
        return true;
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state,
                                EntityLivingBase placer, @Nonnull ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        TileEntity tile = world.getTileEntity(pos);
        if (tile instanceof TileDockingPort) {
            ((TileDockingPort) tile).registerTileWithStation(world, pos);
        }
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        int insertAt = TooltipInjector.computeInsertIndex(tooltip, flag.isAdvanced());
        TooltipInjector.renderShiftAlt(stack, tooltip, "tooltip.stellurgy.dockingport", insertAt);
    }    

    @Override
    @ParametersAreNonnullByDefault
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        TileEntity tile = world.getTileEntity(pos);
        if (tile instanceof TileLandingPad) {
            ((TileLandingPad) tile).unregisterTileWithStation(world, pos);
        }
        super.breakBlock(world, pos, state);
    }

}
