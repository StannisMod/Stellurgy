package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;
import zmaster587.advancedRocketry.test.trace.DeckReseatState;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * How far each re-seat moves a body, measured at the move itself.
 *
 * <p>Production used to hold this as {@code ShipFrameTravel.lastReseatStep} and, purely to fill it,
 * computed three deltas nothing else in the method used. Both are gone.</p>
 *
 * <h2>Why a redirect and not a local capture</h2>
 *
 * <p>A capture was the obvious tool and it cannot be used here: the local beside the seat point is a
 * {@code ShipFrameState}, a PRIVATE nested class of the target, and a capture handler has to name
 * every local's type exactly. Widening that class so a test could name it would be the same defect
 * this whole migration is removing, one level down.</p>
 *
 * <p>A redirect needs none of it. The call it replaces carries both halves of the measurement — the
 * seat point as its arguments, the body's position before the move on the receiver, which is
 * readable only until the call happens. It then performs the original call, so production behaves
 * exactly as written.</p>
 *
 * <p>Runs wherever the pose pass runs — client and server both. Test source set.</p>
 */
@Mixin(ShipFrameTravel.class)
public abstract class MixinShipFrameReseatStep {

    @Redirect(method = "followShipPoses",
            // remap left ON: setPosition is a vanilla method and its SRG name is what is actually
            // in the bytecode. The enclosing method is ours, so remapping its name is a no-op.
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/entity/Entity;setPosition(DDD)V"))
    private static void arTest$reseatStep(Entity entity, double x, double y, double z) {
        TestTrace.instrumentHere("deck_reseat_step");
        DeckReseatState.noteReseat(new double[]{x, y, z}, entity.posX, entity.posY, entity.posZ);
        entity.setPosition(x, y, z);
    }
}
