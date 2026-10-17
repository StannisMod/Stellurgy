package dev.stannismod.stellurgy.test.mixin;

import dev.stannismod.stellurgy.entity.EntityDummy;
import dev.stannismod.stellurgy.test.trace.TestTrace;
import dev.stannismod.stellurgy.util.GravityHandler;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * One record per CLIENT gravity application to a body riding a pilot-seat {@link EntityDummy}:
 * {@code rider_gravity}, with the body's vertical motion as the mod's gravity hook receives it.
 * Printed by {@code VSFlightSmoothnessAcrossJumpTest} beside its seat records.
 *
 * <p>A seated rider's own move carries whatever vertical motion he holds when his living update
 * travels ({@code rider_move}'s {@code dy}), and that motion decides whether he touches his craft. The
 * difference between the two records is what the gravity hook and the rest of his update added; this
 * one says what he already held. Test source set: absent from a released jar.</p>
 */
@Mixin(value = GravityHandler.class, remap = false)
public abstract class MixinRiderGravityTrace {

    @Inject(method = "applyGravity", at = @At("HEAD"))
    private static void stellurgyTest$received(Entity entity, CallbackInfo ci) {
        if (entity.world == null || !entity.world.isRemote || !(entity.getRidingEntity() instanceof EntityDummy)) {
            return;
        }
        TestTrace.instrument(entity, "rider_gravity_events");
        TestTrace.record(entity, "rider_gravity", "\"e\":" + entity.getEntityId()
                + ",\"motionYIn\":" + entity.motionY
                + ",\"motionXIn\":" + entity.motionX
                + ",\"motionZIn\":" + entity.motionZ);
    }
}
