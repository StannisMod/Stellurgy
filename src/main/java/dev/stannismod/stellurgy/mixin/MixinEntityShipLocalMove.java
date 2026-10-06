package dev.stannismod.stellurgy.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.integration.vs.ShipLocalMove;
import dev.stannismod.stellurgy.integration.vs.ShipLocalMoveControl;

/**
 * Claims {@link Entity#move} before the physics mod does, so an entity aboard a ship can have its
 * collision resolved in the ship's own frame (where the deck is axis-aligned) instead of the world
 * frame (where the entity's box is upright and the deck is not).
 *
 * <p>The physics mod injects at the same point, cancellably. Mixin emits an
 * {@code if (ci.isCancelled()) return;} guard after <em>each</em> callback at an injection point, so
 * whichever callback runs first and cancels prevents the rest — including the vanilla method body.
 * Callback order follows mixin application order, and a LOWER priority is applied first, so this
 * mixin must sit below the physics mod's own ({@code MixinEntityIntrinsic}, priority 1). It sat at
 * 1500 until 2026-10-05, which ran it SECOND: measured on a held player's movement packet, the
 * physics mod's callback was the outer frame, pushed his step out of the hull's polygons along the
 * deck normal (0.209 blocks on a 45-degree deck, for a player standing still), re-entered
 * {@code move} with that step, and on the way out wrote the step into his velocity and named the
 * hull as the ship he last touched.</p>
 *
 * <p>Inert unless armed through {@link ShipLocalMoveControl}, and then only for one entity, only on
 * the server. It references no physics-mod type, so it is safe to weave with or without that mod.</p>
 */
@Mixin(value = Entity.class, priority = 0)
public abstract class MixinEntityShipLocalMove {

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void stellurgy$shipLocalMove(MoverType type, double x, double y, double z,
                                                CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        // A body a deck holds, moved from outside its own update: replayed against the deck's blocks
        // in the deck's frame (for now a player's own step, which the server replays from his packet).
        if (dev.stannismod.stellurgy.integration.vs.DeckFrameTick.moveHeld(self, type, x, y, z)) {
            ci.cancel();
            return;
        }
        // A player on a ship's outer hull, his own step: replayed against the hull with the sweep
        // his client collided it with.
        if (dev.stannismod.stellurgy.integration.vs.ShipFrameTravel.replayHullStandStep(self, type, x, y, z)) {
            ci.cancel();
            return;
        }
        // Server-side only: the client keeps predicting with vanilla rules, so a mistake here cannot
        // strand a player, only desync him for a tick.
        if (self.world == null || self.world.isRemote) {
            return;
        }
        ShipLocalMoveControl control = dev.stannismod.stellurgy.Stellurgy.serverState().shipLocalMove;
        if (!control.shouldTakeOver(self)) {
            return;
        }
        control.markFired();
        switch (control.getMode()) {
            case OBSERVE:
                return; // fire only; prove the injection exists
            case CANCEL:
                ci.cancel(); // suppress everything behind us, including the physics mod
                return;
            case SHIP_FRAME:
                // Resolve in the ship's frame. If that cannot be done (aboard no loaded ship, or the
                // transform is unavailable this tick), fall through to vanilla rather than freeze.
                if (ShipLocalMove.resolve(control, self, x, y, z)) {
                    ci.cancel();
                }
                return;
            default:
                return;
        }
    }
}
