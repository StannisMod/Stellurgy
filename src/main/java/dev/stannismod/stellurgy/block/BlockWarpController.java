package dev.stannismod.stellurgy.block;

import dev.stannismod.stellurgy.api.stations.ISpaceObject;
import dev.stannismod.stellurgy.client.TooltipInjector;
import dev.stannismod.stellurgy.libvulpes.block.BlockTile;
import dev.stannismod.stellurgy.stations.SpaceObjectManager;
import dev.stannismod.stellurgy.stations.SpaceStationObject;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public class BlockWarpController extends BlockTile {

    public BlockWarpController(Class<? extends TileEntity> tileClass, int guiId) {
        super(tileClass, guiId);
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state,
                                EntityLivingBase placer, @Nonnull ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);

        ISpaceObject spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(pos);

        if (spaceObject instanceof SpaceStationObject) {
            ((SpaceStationObject) spaceObject).setForwardDirection(getFront(state).getOpposite());
        }
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        int insertAt = TooltipInjector.computeInsertIndex(tooltip, flag.isAdvanced());
        TooltipInjector.renderShiftAlt(stack, tooltip, "tooltip.stellurgy.warpcontroller", insertAt);
    }    

}
