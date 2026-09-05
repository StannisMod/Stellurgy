package zmaster587.advancedRocketry.test.mixin;

import java.util.UUID;

import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.space.DescentController;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The descent gate's answer as an event: {@code descent_requested} with {@code granted}. The
 * controller returns a bare boolean from several refusals, so the record carries only the verdict;
 * the reason a refusal had is read off the records around it (the ledger state, the crossing begun
 * or not).
 */
@Mixin(DescentController.class)
public abstract class MixinDescentControllerEvents {

    @Inject(method = "requestDescent", at = @At("RETURN"))
    private void arTest$requested(int slotDim, BlockPos afcPos, UUID shipId, int targetPlanetDim,
                                  CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentHere("descent_events");
        TestTrace.recordServer("descent_requested", "\"ship\":\"" + shipId + "\",\"slotDim\":" + slotDim
                + ",\"targetDim\":" + targetPlanetDim + ",\"granted\":" + cir.getReturnValueZ());
    }
}
