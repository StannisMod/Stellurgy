package dev.stannismod.stellurgy.block;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.IRocketNuclearCore;
import dev.stannismod.stellurgy.client.TooltipInjector;

import javax.annotation.Nullable;
import java.util.List;

public class BlockNuclearCore extends Block implements IRocketNuclearCore {

    public BlockNuclearCore(Material mat) {
        super(mat);
    }

    /** Largest thrust this core can feed, newtons. */
    @Override
    public int getMaxThrust(World world, BlockPos pos) {
        return (int) (49_050_000L * StellurgyConfiguration.getCurrentConfig().nuclearCoreThrustRatio);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        int insertAt = TooltipInjector.computeInsertIndex(tooltip, flag.isAdvanced());
        TooltipInjector.renderShiftAlt(stack, tooltip, "tooltip.stellurgy.nuclearcore", insertAt);
    }
}
