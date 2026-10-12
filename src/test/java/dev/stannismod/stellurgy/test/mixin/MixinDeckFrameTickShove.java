package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.integration.vs.DeckFrameTick;
import dev.stannismod.stellurgy.test.trace.ShoveArming;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The armed shove ({@link dev.stannismod.stellurgy.test.trace.ShoveArming}), taken where the DECK
 * FRAME commits a held body's position — the code that owns a client player's position on a deck.
 *
 * <p>The driver has to live in the code that owns the position at the moment it is armed: a write
 * from anywhere else is not the client declaring a position, and the client's own movement undoes
 * it before anything is sent.</p>
 *
 * <p>Taken at the end of the held update, after the deck frame has put him back in the world: the
 * step is a POSITION only. His client sends it a moment later, in the same entity update; the
 * deck frame's end-of-tick re-image then puts him back at the deck point it stored before the
 * shove, so the step exists for one packet. No velocity: here nothing between the shove and the
 * send can undo it, and a velocity would be carried into the deck's frame as motion of his own the
 * next tick.</p>
 *
 * <p>Records {@code ship_frame_travel_shove}, marked {@code "by":"deckFrame"}. Test source set:
 * absent from a released jar.</p>
 */
@Mixin(value = DeckFrameTick.class, remap = false)
public abstract class MixinDeckFrameTickShove {

    @Inject(method = "noteHeldTick", at = @At("HEAD"))
    private static void stellurgyTest$shoveAfterHeldUpdate(Entity entity, String shipId, double localX,
                                                          double localY, double localZ, double carryX,
                                                          double carryY, double carryZ, boolean grounded,
                                                          CallbackInfo ci) {
        TestTrace.instrument(entity, "ship_frame_travel_shove");
        if (entity == null || entity.world == null || !entity.world.isRemote
                || !(entity instanceof EntityPlayer)) {
            return;
        }
        final int blocks = ShoveArming.take();
        if (blocks == 0) {
            return;
        }
        entity.setPosition(entity.posX, entity.posY + blocks, entity.posZ);
        TestTrace.record(entity, "ship_frame_travel_shove",
                "\"e\":" + entity.getEntityId() + ",\"blocks\":" + blocks + ",\"toY\":" + TestTrace.fmt(entity.posY)
                        + ",\"ship\":\"" + TestTrace.json(shipId) + "\",\"by\":\"deckFrame\"");
    }
}
