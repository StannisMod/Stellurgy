package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.player.EntityPlayerMP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * What the server's movement bound actually SAW — the step it was asked about and the verdict it
 * returned.
 *
 * <h2>The question</h2>
 *
 * <p>A refusal that did not happen has three innocent readings and the verdict alone separates none
 * of them: the bound was never asked (the body is not captured, so it has no opinion), it was asked
 * about a step that was already ordinary (something upstream had rewritten the position before it
 * looked), or it was asked about a wild step and let it through. Measured once: a client committed
 * a forty-block step, its own recorder proved it, and the server refused nothing — which of the
 * three that was cannot be read off "refused: 0".</p>
 *
 * <p>Records only steps worth a line — every accepted tick of ordinary walking would drown it — and
 * announces itself on entry either way, so silence means the bound never ran rather than that it saw
 * nothing.</p>
 *
 * <p>Test-only: test source set, queued by the harness coremod, absent from a released jar.</p>
 */
@Mixin(targets = "zmaster587.advancedRocketry.integration.vs.DeckMovementBound", remap = false)
public abstract class MixinDeckMovementBoundInputs {

    /** Step worth reporting, in blocks — above ordinary walking, below the wild ones. */
    private static final double REPORT_ABOVE_BLOCKS = 1.0;

    @Inject(method = "accepts", at = @At("RETURN"), remap = false)
    private static void arTest$recordStep(EntityPlayerMP player,
                                          double fromX, double fromY, double fromZ,
                                          double toX, double toY, double toZ,
                                          CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentHere("deck_movement_bound");
        final double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        final double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (moved <= REPORT_ABOVE_BLOCKS) {
            return;
        }
        TestTrace.recordHere("deck_movement_bound",
                "\"moved\":" + TestTrace.fmt(moved)
                        + ",\"fromY\":" + TestTrace.fmt(fromY)
                        + ",\"toY\":" + TestTrace.fmt(toY)
                        + ",\"accepted\":" + cir.getReturnValue());
    }
}
