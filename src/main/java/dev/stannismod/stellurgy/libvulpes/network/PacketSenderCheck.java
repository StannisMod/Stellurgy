package dev.stannismod.stellurgy.libvulpes.network;

import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.inventory.ContainerModular;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IModularInventory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldServer;

/**
 * What the server asks of the player who sent a packet before it lets the packet touch anything.
 *
 * <p>A client is not trusted to address the world. Every packet of this channel that a client sends
 * names its target itself — a dimension and a block, a dimension and an entity id, a held item — and
 * the client writes those bytes, not our encoder. So the target is the sender's CLAIM, and the
 * handler checks it against what the server knows about that sender: the target must be in the world
 * he is in, and he must be where the interaction the packet stands for could have happened. A packet
 * that fails is dropped and {@link #refuse logged} — it never reaches the addressee.</p>
 *
 * <p>These checks are about the SENDER and are the same for every addressee. What a particular
 * machine or entity additionally requires of its sender — that he is seated, that only the server may
 * send an id — is the addressee's own rule, and it decides that in its own handler.</p>
 */
public final class PacketSenderCheck {

    /**
     * How near a player must be to a block, squared, to use it as a container: measured from the
     * player's feet to the block's centre, exactly as vanilla's own containers measure it
     * ({@code TileEntityFurnace#isUsableByPlayer}, {@code TileEntityLockableLoot#isUsableByPlayer}).
     */
    private static final double CONTAINER_REACH_SQ = 64.0D;

    /**
     * What the server adds to a player's reach before it believes he used an item on a block — the
     * tolerance vanilla gives a click against movement it has not seen yet
     * ({@code NetHandlerPlayServer#processTryUseItemOnBlock}: the reach attribute plus 3).
     */
    private static final double ITEM_USE_REACH_MARGIN = 3.0D;

    private PacketSenderCheck() {
    }

    /** Whether {@code dimension} is the world {@code sender} is in. */
    public static boolean inSendersWorld(EntityPlayerMP sender, int dimension) {
        return sender.world.provider.getDimension() == dimension;
    }

    /**
     * Whether {@code sender} stands within container reach of the block at {@code pos} of his own
     * world.
     *
     * <p>A block aboard a ship is measured where the ship has it in the world, not at its shipyard
     * address — a pilot in his seat is a block from the seat, while the seat's {@code BlockPos} lies
     * millions of blocks off. That is {@code Entity#getDistanceSq}'s own answer here: Valkyrien Skies
     * replaces it ({@code org.valkyrienskies.mixin.entity.MixinEntity#getDistanceSq}) with the nearer
     * of the plain distance and the distance to where the loaded ship managing that point has it.</p>
     */
    public static boolean withinContainerReach(EntityPlayerMP sender, BlockPos pos) {
        return sender.getDistanceSq(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= CONTAINER_REACH_SQ;
    }

    /**
     * Whether {@code sender} could have clicked something inside {@code box}, a box in his own world:
     * his eyes are within his reach plus {@link #ITEM_USE_REACH_MARGIN} of it.
     */
    public static boolean withinItemUseReach(EntityPlayer sender, AxisAlignedBB box) {
        double reach = sender.getEntityAttribute(EntityPlayer.REACH_DISTANCE).getAttributeValue()
                + ITEM_USE_REACH_MARGIN;
        Vec3d eyes = sender.getPositionEyes(1.0F);
        double dx = Math.max(Math.max(box.minX - eyes.x, 0.0D), eyes.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - eyes.y, 0.0D), eyes.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - eyes.z, 0.0D), eyes.z - box.maxZ);
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    /**
     * Whether the server shows {@code entity} to {@code sender} — he rides it, or its tracker is sending
     * it to him. A client can only have learned an entity id from that tracker, so a packet about an
     * entity he is not being sent names something he cannot see.
     */
    public static boolean seesEntity(EntityPlayerMP sender, Entity entity) {
        if (entity.isRidingOrBeingRiddenBy(sender)) {
            return true;
        }
        return sender.world instanceof WorldServer
                && ((WorldServer) sender.world).getEntityTracker().getTrackingPlayers(entity).contains(sender);
    }

    /**
     * Whether {@code sender} has the screen of {@code machine} open: the container the server built
     * for him when he opened that machine's GUI, and still holds as his open one. A packet that only a
     * machine's screen sends claims that he is using that screen, and this is the server's own record
     * of it.
     */
    public static boolean hasScreenOpen(EntityPlayer sender, IModularInventory machine) {
        return sender != null && sender.openContainer instanceof ContainerModular
                && ((ContainerModular) sender.openContainer).isScreenOf(machine);
    }

    /**
     * Drop a client's packet, saying so. Every refusal is logged: a dropped packet is either a client
     * that is not playing by the rules or a check that is wrong, and both must be visible.
     *
     * @param sender who sent it
     * @param packet what was sent and to whom, for the log line
     * @param why    which check it failed
     */
    public static void refuse(EntityPlayer sender, String packet, String why) {
        LibVulpes.logger.warn("Dropped a {} from {}: {}", packet, sender == null ? "no player" : sender.getName(), why);
    }
}
