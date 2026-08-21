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
            MotionTrace.clientFrame(eye.x, eye.y, eye.z, p);
        }
    }
}
