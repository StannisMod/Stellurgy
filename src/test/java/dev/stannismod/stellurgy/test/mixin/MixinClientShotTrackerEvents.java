package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.util.math.Vec3d;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.api.projectile.ShotEndReason;
import dev.stannismod.stellurgy.client.ClientShotTracker;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The client being handed a round to draw, and being told it ended, as events:
 * {@code client_shot_drawn} (HEAD of {@code ClientShotTracker.spawn}) and {@code client_shot_ended}
 * (HEAD of {@code ClientShotTracker.end}), each carrying the round's server id; the end carries the
 * reason and the point the flash is drawn at. Those two methods are the only doors from the spawn
 * and end packets into what the renderer draws, so a round this client was told about is a record
 * here and one it was not told about is not. Client log. Read by {@code ShotReachesClientTest}.
 *
 * <p>SILENT about a round the client drops on its own because it outlived its declared lifetime —
 * that is the tracker's tick, not a packet.</p>
 */
@Mixin(ClientShotTracker.class)
public abstract class MixinClientShotTrackerEvents {

    private static final String INSTRUMENT = "client_shot_events";

    @Inject(method = "spawn", at = @At("HEAD"), require = 1)
    private void stellurgyTest$drawn(long id, Vec3d origin, Vec3d velocity, float radius,
                                     int lifetimeTicks, double gravityPerTickSquared,
                                     CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordHere("client_shot_drawn", "\"shot\":" + id
                + ",\"x\":" + origin.x + ",\"y\":" + origin.y + ",\"z\":" + origin.z);
    }

    @Inject(method = "end", at = @At("HEAD"), require = 1)
    private void stellurgyTest$ended(long id, Vec3d point, ShotEndReason reason, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordHere("client_shot_ended", "\"shot\":" + id
                + ",\"reason\":\"" + reason + "\""
                + ",\"x\":" + point.x + ",\"y\":" + point.y + ",\"z\":" + point.z);
    }
}
