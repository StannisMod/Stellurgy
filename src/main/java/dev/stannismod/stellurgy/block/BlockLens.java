package dev.stannismod.stellurgy.block;

import dev.stannismod.stellurgy.client.TooltipInjector;
import net.minecraft.block.BlockGlass;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Yes, this class may seem useless, but setSoundType can't be run in the registry, only by a subclass of Block.
 */
public class BlockLens extends BlockGlass {
    public BlockLens() {
        super(Material.GLASS, true);
        setSoundType(SoundType.GLASS);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        int insertAt = TooltipInjector.computeInsertIndex(tooltip, flag.isAdvanced());
        TooltipInjector.renderShiftAlt(stack, tooltip, "tooltip.stellurgy.lens", insertAt);
    }
}
