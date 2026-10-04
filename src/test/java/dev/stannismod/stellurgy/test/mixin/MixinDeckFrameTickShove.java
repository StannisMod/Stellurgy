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
 * The armed shove of {@link MixinShipFrameTravelShove}, taken where the DECK FRAME commits a held
 * body's position — the other code that can own a client player's position.
 *
 * <p>The driver has to live in whichever code owns the position at the moment it is armed, for the
 * reason the travel resolver's half gives: a write from anywhere else is not the client declaring a
 * position. A player standing or walking on a deck is held by the deck frame, whose update the
 * resolver's {@code travel} does not run — so an arming the resolver alone could take waited until
 * the body left the deck. Measured 2026-10-04: the scenario kept walking with the shove armed, the
 * deck let him go at its edge, the resolver took him in hull mode and the shove fired there, where
 * no deck holds him and the server's bound rightly has no opinion.</p>
 *
 * <p>Taken at the end of the held update, after the deck frame has put him back in the world: the
 * step is a POSITION only. His client sends it a moment later, in the same entity update; the
 * deck frame's end-of-tick re-image then puts him back at the deck point it stored before the
 * shove, so the step exists for one packet — the shape the resolver's half produces. No velocity:
 * here nothing between the shove and the send can undo it, and a velocity would be carried into the
 * deck's frame as motion of his own the next tick.</p>
 *
 * <p>Records on the same instrument as the resolver's half, marked {@code "by":"deckFrame"}, so the
 * link a scenario waits on is the same whichever holder took the step. Test source set: absent from a
 * released jar.</p>
 */
@Mixin(value = DeckFrameTick.class, remap = false)
public abstract class MixinDeckFrameTickShove {

    @Inject(method = "noteHeldTick", at = @At("HEAD"))
    private static void stellurgyTest$shoveAfterHeldUpdate(Entity entity, String shipId, double localX,
                                                          double localY, double localZ, double carryX,
                                                          double carryY, double carryZ, boolean grounded,
                                                          CallbackInfo ci) {
        TestTrace.instrumentHere("ship_frame_travel_shove");
        if (entity == null || entity.world == null || !entity.world.isRemote
                || !(entity instanceof EntityPlayer)) {
            return;
        }
        final int blocks = ShoveArming.take();
        if (blocks == 0) {
            return;
        }
        entity.setPosition(entity.posX, entity.posY + blocks, entity.posZ);
        TestTrace.recordHere("ship_frame_travel_shove",
                "\"blocks\":" + blocks + ",\"toY\":" + TestTrace.fmt(entity.posY)
                        + ",\"ship\":\"" + TestTrace.json(shipId) + "\",\"by\":\"deckFrame\"");
    }
}
