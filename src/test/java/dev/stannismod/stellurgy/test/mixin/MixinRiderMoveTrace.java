package dev.stannismod.stellurgy.test.mixin;

import dev.stannismod.stellurgy.entity.EntityDummy;
import dev.stannismod.stellurgy.test.trace.TestTrace;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * One record per CLIENT {@code Entity.move} of a body riding a pilot-seat {@link EntityDummy}:
 * {@code rider_move}, with the mover type and the displacement asked for. Printed by
 * {@code VSFlightSmoothnessAcrossJumpTest} beside its seat records.
 *
 * <p>A seated rider's own move is where the physics mod decides whether he touches his craft, and
 * that decision is what keeps its end-of-tick drag of him armed. The displacement he asks for is the
 * input to that decision. Test source set: absent from a released jar.</p>
 */
@Mixin(Entity.class)
public abstract class MixinRiderMoveTrace {

    @Inject(method = "move", at = @At("HEAD"))
    private void stellurgyTest$riderMove(MoverType type, double x, double y, double z, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.world == null || !self.world.isRemote || !(self.getRidingEntity() instanceof EntityDummy)) {
            return;
        }
        TestTrace.instrument(self, "rider_move_events");
        TestTrace.record(self, "rider_move", "\"e\":" + self.getEntityId()
                + ",\"mover\":\"" + type + "\""
                + ",\"dx\":" + x + ",\"dy\":" + y + ",\"dz\":" + z
                + ",\"onGround\":" + self.onGround
                + ",\"posY\":" + self.posY);
    }
}
