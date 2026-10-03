package dev.stannismod.stellurgy.mixin;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.stannismod.stellurgy.integration.vs.DeckFrameTick;

/**
 * Runs the living update of a server player a deck holds in that deck's frame.
 *
 * <p>The world's entity tick does not run a player's living update; his network handler does, once
 * a tick, and puts him back where his client last had him right after. This is that call.</p>
 */
@Mixin(NetHandlerPlayServer.class)
public abstract class MixinNetHandlerPlayerDeckFrame {

    @Redirect(method = "update",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/EntityPlayerMP;onUpdateEntity()V"))
    private void stellurgy$updatePlayerInDeckFrame(EntityPlayerMP player) {
        if (!DeckFrameTick.updatePlayer(player)) {
            player.onUpdateEntity();
        }
    }
}
