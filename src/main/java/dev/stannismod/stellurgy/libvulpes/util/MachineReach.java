package dev.stannismod.stellurgy.libvulpes.util;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;

/**
 * Whether a player can reach a machine, which is what being allowed to use it means. Using a machine
 * from afar makes no sense, and an open screen is a player using the machine, so the screen closes and
 * a press arriving as a packet is refused on the same answer.
 */
public final class MachineReach {

	/**
	 * Vanilla's container reach, squared: {@code TileEntityFurnace#isUsableByPlayer} and
	 * {@code TileEntityLockableLoot#isUsableByPlayer} both compare against 64.
	 */
	private static final double REACH_SQ = 64.0D;

	private MachineReach() {
	}

	/**
	 * Vanilla's rule for a block: the player is in the tile's world, the tile is still the one there,
	 * and the player is within reach of the block's centre.
	 *
	 * <p>The distance is {@code EntityPlayer#getDistanceSq(double, double, double)} on purpose: on a
	 * Valkyrien Skies ship the tile's position is a shipyard address, and VS's overwrite of that method
	 * measures to where the block really is.</p>
	 */
	public static boolean reaches(EntityPlayer player, TileEntity tile) {
		return player != null && tile.getWorld() != null && player.world == tile.getWorld()
				&& !tile.isInvalid()
				&& player.getDistanceSq(tile.getPos().getX() + 0.5D, tile.getPos().getY() + 0.5D,
				tile.getPos().getZ() + 0.5D) <= REACH_SQ
				// Last: in reach, the chunk is loaded, so this lookup never loads one.
				&& tile.getWorld().getTileEntity(tile.getPos()) == tile;
	}

	/**
	 * The same rule for an entity: the player rides it, or is in its world and within reach of the
	 * nearest point of its bounding box. The box and not the entity's position, because a rocket is
	 * many blocks tall and its position is its base.
	 */
	public static boolean reaches(EntityPlayer player, Entity entity) {
		if (player == null || entity.isDead || player.world != entity.world) {
			return false;
		}
		if (player.isRidingOrBeingRiddenBy(entity)) {
			return true;
		}
		AxisAlignedBB box = entity.getEntityBoundingBox();
		double dx = Math.max(Math.max(box.minX - player.posX, 0.0D), player.posX - box.maxX);
		double dy = Math.max(Math.max(box.minY - player.posY, 0.0D), player.posY - box.maxY);
		double dz = Math.max(Math.max(box.minZ - player.posZ, 0.0D), player.posZ - box.maxZ);
		return dx * dx + dy * dy + dz * dz <= REACH_SQ;
	}
}
