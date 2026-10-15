package dev.stannismod.stellurgy.block;

import dev.stannismod.stellurgy.api.IIntake;
import dev.stannismod.stellurgy.client.TooltipInjector;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

public class BlockIntake extends Block implements IIntake {

    public BlockIntake(Material material) {
        super(material);
    }
    
    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        int insertAt = TooltipInjector.computeInsertIndex(tooltip, flag.isAdvanced());
        TooltipInjector.renderShiftAlt(stack, tooltip, "tooltip.stellurgy.intake", insertAt);
    }

    @Override
    public int getIntakeAmt(IBlockState state) {
        return 1;
    }
}
