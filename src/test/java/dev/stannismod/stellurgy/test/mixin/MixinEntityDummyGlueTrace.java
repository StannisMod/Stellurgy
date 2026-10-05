package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.entity.EntityDummy;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import dev.stannismod.stellurgy.test.trace.EntityTrace;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * One record per CLIENT update of a seat entity: {@code dummy_glue}, with where the seat's block sits
 * in the world through the client's pose of the craft, and where the seat entity was before and after
 * its update glued it there. Read by {@code VSFlightSmoothnessAcrossJumpE2ETest} beside its per-tick
 * pilot channel.
 *
 * <p>A seated pilot's client position is his seat entity's, and the seat entity's is the seat block
 * through the craft's pose — so when the pilot's tick covers no ground and the next covers two ticks'
 * worth while the server flies the craft evenly, one of three things happened in the tick that stood
 * still: the pose did not advance ({@code seat} stands), the glue did not land ({@code seat} null, or
 * {@code after} differs from it), or the seat entity was not updated at all (its tick count skips
 * against the world's). This record tells the three apart. Test source set: absent from a released
 * jar.</p>
 */
@Mixin(value = EntityDummy.class, remap = false)
public abstract class MixinEntityDummyGlueTrace {

    @Inject(method = "onUpdate", at = @At("HEAD"))
    private void stellurgyTest$glueHead(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.world == null || !self.world.isRemote) {
            return;
        }
        EntityTrace.DeckFrameUpdate start =
                EntityTrace.memory(self, EntityTrace.DeckFrameUpdate.class, EntityTrace.DeckFrameUpdate::new);
        start.startX = self.posX;
        start.startY = self.posY;
        start.startZ = self.posZ;
    }

    @Inject(method = "onUpdate", at = @At("RETURN"))
    private void stellurgyTest$glueReturn(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.world == null || !self.world.isRemote) {
            return;
        }
        TestTrace.instrument(self, "dummy_glue_events");
        EntityTrace.DeckFrameUpdate start =
                EntityTrace.memory(self, EntityTrace.DeckFrameUpdate.class, EntityTrace.DeckFrameUpdate::new);
        BlockPos seatPos = ((EntityDummy) (Object) this).getSeatPos();
        double[] seat = seatPos == null ? null : VSIntegration.getSeatWorldPosition(self.world, seatPos);
        Entity rider = self.getPassengers().isEmpty() ? null : self.getPassengers().get(0);
        TestTrace.record(self, "dummy_glue", "\"e\":" + self.getEntityId()
                + ",\"dummyTick\":" + self.ticksExisted
                + ",\"riderTick\":" + (rider == null ? -1 : rider.ticksExisted)
                + ",\"seatPos\":" + (seatPos != null)
                + ",\"seat\":" + (seat == null ? "null"
                        : "\"" + seat[0] + "," + seat[1] + "," + seat[2] + "\"")
                + ",\"before\":\"" + start.startX + "," + start.startY + "," + start.startZ + "\""
                + ",\"after\":\"" + self.posX + "," + self.posY + "," + self.posZ + "\""
                + ",\"nanos\":" + System.nanoTime());
    }
}
