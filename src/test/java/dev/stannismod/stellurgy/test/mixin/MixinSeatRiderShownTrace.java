package dev.stannismod.stellurgy.test.mixin;

import dev.stannismod.stellurgy.test.trace.SeatRiderTrace;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where the CLIENT shows a seated rider against where it shows his seat: {@code seat_rider_shown}
 * ({@link SeatRiderTrace}), at the HEAD of every client tick.
 *
 * <p>The head of a tick is the state every frame since the last tick has been drawn toward: a rider is
 * rendered between his previous and current position, and the craft between its previous and current
 * pose, so the two current ends are what the pilot sees the craft and himself arrive at. Read by
 * {@code VSRiderKeepsHisMountAtCruiseTest}. Test source set: absent from a released jar.</p>
 */
@Mixin(Minecraft.class)
public abstract class MixinSeatRiderShownTrace {

    @Inject(method = "runTick", at = @At("HEAD"))
    private void stellurgyTest$riderShown(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.world == null || mc.isGamePaused()) {
            return;
        }
        SeatRiderTrace.recordRiders(mc.world, "seat_rider_shown", "seat_rider_shown_events");
    }
}
