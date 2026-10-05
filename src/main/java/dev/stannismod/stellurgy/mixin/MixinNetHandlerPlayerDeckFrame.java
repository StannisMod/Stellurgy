package dev.stannismod.stellurgy.mixin;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.CPacketPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.integration.vs.DeckFrameTick;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;

/**
 * Runs the living update of a server player a deck holds in that deck's frame.
 *
 * <p>The world's entity tick does not run a player's living update; his network handler does, once
 * a tick, and puts him back where his client last had him right after. This is that call.</p>
 */
@Mixin(NetHandlerPlayServer.class)
public abstract class MixinNetHandlerPlayerDeckFrame {

    @Shadow
    public EntityPlayerMP player;

    @Redirect(method = "update",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/EntityPlayerMP;onUpdateEntity()V"))
    private void stellurgy$updatePlayerInDeckFrame(EntityPlayerMP player) {
        if (!DeckFrameTick.updatePlayer(player)) {
            player.onUpdateEntity();
        }
    }

    @Redirect(method = "update", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/EntityPlayerMP;setPositionAndRotation(DDDFF)V"))
    private void stellurgy$restoreAfterUpdate(EntityPlayerMP player, double x, double y, double z,
                                              float yaw, float pitch) {
        DeckFrameTick.restoreAfterUpdate(player, x, y, z, yaw, pitch);
    }

    /**
     * His packet handled: the deck takes the claim onto itself through the pose it was handled under
     * ({@link DeckFrameTick#claimArrived}), and the physics mod's association with the craft goes -
     * a packet that declared his point on a deck leaves it naming that craft as the one he last
     * touched, and before his next update the world tick runs the mod's drag, which would carry him by
     * the craft's motion on top of the deck's own carry.
     */
    @Inject(method = "processPlayer", at = @At("TAIL"))
    private void stellurgy$claimOntoTheDeck(CPacketPlayer packet, CallbackInfo ci) {
        if (player != null && DeckFrameTick.holds(player)) {
            DeckFrameTick.claimArrived(player);
            VSIntegration.suppressShipDrag(player);
        }
    }
}
