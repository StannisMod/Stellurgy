package dev.stannismod.stellurgy.test.mixin;

import dev.stannismod.stellurgy.test.trace.SeatRiderTrace;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where the SERVER places a seated rider against where it holds his seat: {@code seat_rider_placed}
 * ({@link SeatRiderTrace}), at the HEAD of every server tick, for every loaded world.
 *
 * <p>The head of a tick is the state the previous one left — after the world ticks, the physics
 * substrate's pose advance and everything that re-seats a body after it — and the one the server's
 * tracker sends, interaction reach measures from and the next tick starts from. Read by
 * {@code VSRiderKeepsHisMountAtCruiseTest}. Test source set: absent from a released jar.</p>
 */
@Mixin(MinecraftServer.class)
public abstract class MixinSeatRiderPlacedTrace {

    @Inject(method = "tick", at = @At("HEAD"))
    private void stellurgyTest$riderPlaced(CallbackInfo ci) {
        for (WorldServer world : ((MinecraftServer) (Object) this).worlds) {
            if (world != null) {
                SeatRiderTrace.recordRiders(world, "seat_rider_placed", "seat_rider_placed_events");
            }
        }
    }
}
