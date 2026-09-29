package dev.stannismod.stellurgy.affs.block;

import net.minecraft.item.Item;

import javax.annotation.Nullable;

public interface IHasItemBlock {

    @Nullable
    Item createItemBlock();
}
