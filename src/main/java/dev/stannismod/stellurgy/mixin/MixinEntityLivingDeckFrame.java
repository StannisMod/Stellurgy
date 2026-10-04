package dev.stannismod.stellurgy.mixin;

import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.stannismod.stellurgy.integration.vs.DeckFrameTick;

/**
 * Runs the living update of the player a client plays in his deck's frame, when a deck holds him.
 *
 * <p>His client's world tick runs his whole entity update and then sends his position; only the
 * living update inside it - where he walks, jumps and falls - is moved into the deck's frame, so the
 * send, and everything else after this call, reads him in the world.</p>
 */
@Mixin(EntityLivingBase.class)
public abstract class MixinEntityLivingDeckFrame {

    @Redirect(method = "onUpdate",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/EntityLivingBase;onLivingUpdate()V"))
    private void stellurgy$livingUpdateInDeckFrame(EntityLivingBase self) {
        if (!DeckFrameTick.updateLocalPlayerLiving(self)) {
            self.onLivingUpdate();
        }
    }
}
