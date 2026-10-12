package dev.stannismod.stellurgy.util;

import dev.stannismod.stellurgy.tile.TileBrokenPart;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import javax.annotation.Nullable;

public interface IBrokenPartBlock {

    ItemStack getDropItem(final IBlockState state, final World world, final @Nullable TileBrokenPart te);
}
