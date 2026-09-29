package dev.stannismod.stellurgy.util;

import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import dev.stannismod.stellurgy.tile.TileBrokenPart;

import javax.annotation.Nullable;

public interface IBrokenPartBlock {

    ItemStack getDropItem(final IBlockState state, final World world, final @Nullable TileBrokenPart te);
}
