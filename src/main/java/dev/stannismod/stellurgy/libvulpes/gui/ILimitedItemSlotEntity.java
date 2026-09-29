package dev.stannismod.stellurgy.libvulpes.gui;

import net.minecraft.item.ItemStack;

import javax.annotation.Nonnull;

public interface ILimitedItemSlotEntity {
	boolean isItemValidForLimitedSlot(int slot, @Nonnull ItemStack itemstack);
}
