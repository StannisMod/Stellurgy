package dev.stannismod.stellurgy.mixin;

import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.server.SPacketPlayerPosLook;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.integration.vs.DeckHeld;

/**
 * Counts every teleport the server sends a player that puts him somewhere new, on the player.
 *
 * <p>{@code setPlayerLocation} is the one method every server-side teleport of a player ends in — a
 * command, a dimension transfer, a dismount, a movement correction — and the only one that sends the
 * position-look his client must apply. An intent to put him on a deck compares the count it began at
 * with the count now, so a placement made after it began is seen whoever made it.</p>
 *
 * <p>Not counted: a teleport to where the server already has him. Vanilla sends two of those — the
 * re-send of a teleport his client has not confirmed yet ({@code processPlayer}, every 20 ticks while
 * {@code targetPos} stands), and the "moved too quickly" correction that follows a long teleport when
 * his confirm and his next move are handled in one tick, judged against the position the tick began
 * with ({@code firstGoodX}) — and both target the position the last teleport already gave him.</p>
 */
@Mixin(NetHandlerPlayServer.class)
public abstract class MixinNetHandlerServerTeleportCount {

    @Shadow
    public EntityPlayerMP player;

    @Inject(method = "setPlayerLocation(DDDFFLjava/util/Set;)V", at = @At("HEAD"))
    private void stellurgy$countTeleport(double x, double y, double z, float yaw, float pitch,
                                         Set<SPacketPlayerPosLook.EnumFlags> relativeSet,
                                         CallbackInfo ci) {
        if (player == null) {
            return;
        }
        double toX = x + (relativeSet.contains(SPacketPlayerPosLook.EnumFlags.X) ? player.posX : 0.0);
        double toY = y + (relativeSet.contains(SPacketPlayerPosLook.EnumFlags.Y) ? player.posY : 0.0);
        double toZ = z + (relativeSet.contains(SPacketPlayerPosLook.EnumFlags.Z) ? player.posZ : 0.0);
        if (toX != player.posX || toY != player.posY || toZ != player.posZ) {
            ((DeckHeld) player).stellurgy$countTeleport();
        }
    }
}
