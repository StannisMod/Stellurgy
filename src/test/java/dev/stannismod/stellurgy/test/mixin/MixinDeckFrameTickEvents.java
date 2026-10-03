package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.integration.vs.DeckFrameTick;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The deck frame's two episode edges, in the same vocabulary and on the same instrument as the
 * travel resolver's: {@code deck_entered} and {@code deck_released} on {@code deck_capture_events}.
 *
 * <p>"The deck took this body" and "the deck let it go" are what a scenario asks, whichever mechanism
 * holds it; a reader that had to know which one would be pinning an implementation. So the deck
 * frame answers in the same records, marked {@code "by":"deckFrame"} for a reader that does want to
 * know.</p>
 *
 * <p>An edge is a change of HOLDER: taking over a body the travel resolver already held on the same
 * craft changes nothing a scenario can see and records nothing, exactly as the resolver's own
 * re-install onto its current anchor records nothing. A different craft, or none, is an edge and
 * carries {@code from}.</p>
 */
@Mixin(value = DeckFrameTick.class, remap = false)
public abstract class MixinDeckFrameTickEvents {

    private static final String INSTRUMENT = "deck_capture_events";
    private static final String INSTRUMENT_MODE = "deck_mode_events";

    @Inject(method = "noteEntered", at = @At("HEAD"))
    private static void stellurgyTest$entered(Entity entity, String shipId, String from, CallbackInfo ci) {
        TestTrace.instrument(entity, INSTRUMENT);
        TestTrace.instrument(entity, INSTRUMENT_MODE);
        if (entity == null || entity.world == null) {
            return;
        }
        // The deck frame holds a body in one mode only - on the deck - so every opening commits that
        // mode, the same craft included: taking over a body the resolver held on this craft's OUTER
        // hull is a change of mode a scenario can see.
        TestTrace.record(entity, "deck_mode_committed", "\"e\":" + entity.getEntityId()
                + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\",\"ship\":\""
                + TestTrace.json(shipId) + "\",\"mode\":\"aboard\",\"by\":\"deckFrame\"");
        if (shipId != null && shipId.equals(from)) {
            return;
        }
        TestTrace.record(entity, "deck_entered", "\"e\":" + entity.getEntityId()
                + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\",\"ship\":\""
                + TestTrace.json(shipId)
                + "\",\"from\":" + (from == null ? "null" : "\"" + TestTrace.json(from) + "\"")
                + ",\"worldY\":" + TestTrace.fmt(entity.posY) + ",\"by\":\"deckFrame\"");
    }

    @Inject(method = "noteReleased", at = @At("HEAD"))
    private static void stellurgyTest$released(Entity entity, String reason, CallbackInfo ci) {
        TestTrace.instrument(entity, INSTRUMENT);
        if (entity == null || entity.world == null) {
            return;
        }
        TestTrace.record(entity, "deck_released", "\"e\":" + entity.getEntityId()
                + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\",\"reason\":\""
                + TestTrace.json(reason) + "\",\"mode\":\"deckFrame\",\"y\":" + TestTrace.fmt(entity.posY)
                + ",\"by\":\"deckFrame\"");
    }
}
