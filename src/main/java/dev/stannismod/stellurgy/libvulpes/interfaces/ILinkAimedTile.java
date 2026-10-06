package dev.stannismod.stellurgy.libvulpes.interfaces;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.RayTraceResult;

import javax.annotation.Nonnull;

/**
 * A linkable tile whose link is finished by LOOKING rather than by clicking a second machine: once a
 * linker is bound to it, the player right-clicks while looking at something, and the tile is handed
 * whatever his line of sight landed on first — however far away, which is the point of it, since a
 * click on a block reaches only as far as an arm does.
 */
public interface ILinkAimedTile extends ILinkableTile {

	/**
	 * Called on the server only.
	 *
	 * @param aimedAt an {@code ENTITY} or {@code BLOCK} hit, never a miss. Its {@code hitVec} is in
	 *                WORLD coordinates even when the block hit belongs to a physics ship, whose
	 *                {@code getBlockPos()} is the ship's own.
	 * @return whether the tile took it
	 */
	boolean onLinkAimed(@Nonnull ItemStack linker, @Nonnull RayTraceResult aimedAt, EntityPlayer player);
}
