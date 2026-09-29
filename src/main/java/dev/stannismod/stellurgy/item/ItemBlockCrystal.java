package dev.stannismod.stellurgy.item;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import dev.stannismod.stellurgy.libvulpes.block.INamedMetaBlock;
import dev.stannismod.stellurgy.libvulpes.items.ItemBlockMeta;

import javax.annotation.Nonnull;

public class ItemBlockCrystal extends ItemBlockMeta {

    public ItemBlockCrystal(Block p_i45326_1_) {
        super(p_i45326_1_);
    }

    @Override
    public String getUnlocalizedName(@Nonnull ItemStack stack) {
        return ((INamedMetaBlock) Block.getBlockFromItem(stack.getItem())).getUnlocalizedName(stack.getItemDamage());
    }
}
