package dev.stannismod.stellurgy.tile.weapon;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentTranslation;
import dev.stannismod.stellurgy.libvulpes.items.ItemLinker;

/**
 * How a player names a target with a linker, for the two things a linker can be bound to — a gun and
 * a weapon console. Click the gun or the console to bind the linker to it; then right-click while
 * looking at a block or a creature, at any distance the player can see, and that becomes the order.
 *
 * <p>A block hit becomes a POINT — where the line of sight landed, in world coordinates — and a
 * creature hit becomes that creature, followed as it moves. One replaces the other, because the gun
 * reads a followed creature ahead of a point and an order left behind would outrank the new one.</p>
 */
final class LinkerDesignation {

    private LinkerDesignation() {
    }

    /**
     * Binds the linker to {@code tile}. A linker already bound elsewhere is re-bound, since a second
     * click on a weapon has no other meaning: the target is named by looking, not by clicking.
     */
    static void bind(ItemStack linker, TileEntity tile, EntityPlayer player) {
        ItemLinker.setMasterCoords(linker, tile.getPos());
        ItemLinker.setDimId(linker, tile.getWorld().provider.getDimension());
        if (!tile.getWorld().isRemote) {
            player.sendMessage(new TextComponentTranslation("msg.weaponLinker.bound"));
        }
    }

    /** Tells the player what he just designated. */
    static void confirm(EntityPlayer player, RayTraceResult aimedAt) {
        if (aimedAt.typeOfHit == RayTraceResult.Type.ENTITY) {
            player.sendMessage(new TextComponentTranslation("msg.weaponLinker.designatedEntity",
                    aimedAt.entityHit.getDisplayName()));
        } else {
            player.sendMessage(new TextComponentTranslation("msg.weaponLinker.designatedPoint",
                    (int) Math.floor(aimedAt.hitVec.x), (int) Math.floor(aimedAt.hitVec.y),
                    (int) Math.floor(aimedAt.hitVec.z)));
        }
    }
}
