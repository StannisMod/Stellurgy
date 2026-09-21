package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.EntityViewRenderEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.command.test.MotionTrace;
import zmaster587.advancedRocketry.event.RocketEventHandler;

/**
 * One flight-recorder sample per RENDERED FRAME, carrying the pilot's eye point.
 *
 * <p>The eye point is the pose the pilot actually sees, which is a filter chasing the ship rather
 * than the ship itself — the difference between the two is the whole subject of a smoothness test.
 * Read from the render-view entity, so nothing production computed is needed.</p>
 */
@Mixin(RocketEventHandler.class)
public abstract class MixinRocketEventHandlerMotionSample {

    @Inject(method = "onFreeFlightCameraSetup", at = @At("HEAD"))
    private void arTest$clientFrameSample(EntityViewRenderEvent.CameraSetup event, CallbackInfo ci) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc == null ? null : mc.getRenderViewEntity();
        if (view != null) {
            float p = (float) event.getRenderPartialTicks();
            Vec3d eye = view.getPositionEyes(p);
            // The tick this frame was rendered INSIDE, not a tick of its own: several frames share
            // one, and how many is the frame rate. So this channel's per-tick block is a FRAME
            // COUNT per tick, never a gap count — a tick with no frame is the render loop's
            // business and the only channel that can see it is this one, which is also the one
            // whose rate the box sets. Nothing asserts on it; it is here so the number is beside
            // the others when a reader is chasing something real.
            MotionTrace.clientFrame(view.world == null ? -1L : view.world.getTotalWorldTime(),
                    eye.x, eye.y, eye.z, p);
        }
    }
}
