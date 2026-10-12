package dev.stannismod.stellurgy.item;

import dev.stannismod.stellurgy.client.TooltipInjector;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public class ItemThermite extends Item {

    @Override
    public int getItemBurnTime(@Nonnull ItemStack itemStack) {
        return 6000;
    }
    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        int insertAt = TooltipInjector.computeInsertIndex(tooltip, flag.isAdvanced());
        TooltipInjector.renderShiftAlt(stack, tooltip, "tooltip.stellurgy.thermite", insertAt);
    }
    
}
