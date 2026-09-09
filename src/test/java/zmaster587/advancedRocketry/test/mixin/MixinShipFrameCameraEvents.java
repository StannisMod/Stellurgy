package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.client.ShipFrameCamera;
import zmaster587.advancedRocketry.test.trace.RemoteModelWindow;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * Every model-rotation decision the client takes about a body, folded into the open window.
 *
 * <h2>What is left here, and what left</h2>
 *
 * <p>This class used to carry two more injections, on {@code recordCamera} and
 * {@code recordFrameInterp} - methods that existed in production for no reason but to be injected
 * into, one of them with an empty body, filling fourteen statics no production path read. Those are
 * gone from production entirely, and their observation moved to the render paths that compute the
 * values: {@link MixinRocketEventHandlerCameraFrame} and {@link MixinEntityRendererEyeProbe}.</p>
 *
 * <p>{@code modelRotationFor} is not of that kind and stays. It is real production logic - it takes
 * a body and returns the rotation its model is drawn with, and production calls it for that answer.
 * Watching it observes a decision that is genuinely made there.</p>
 *
 * <p>Client render thread. Test source set.</p>
 */
@Mixin(ShipFrameCamera.class)
public abstract class MixinShipFrameCameraEvents {

    /**
     * Every model-rotation decision, folded into the open remote-model window.
     *
     * <p>RETURN and not HEAD: the decision IS the return value, and the body it was made about is
     * the first parameter, so both halves are here without a local capture. Production used to
     * accumulate the same three counts, the same maximum and the same bounded trace into five
     * statics of its own; {@link RemoteModelWindow} says why they are an accumulator rather than a
     * record each, and why one of its fields stays readable while the window is open.</p>
     *
     * <p>Whether the body is the LOCAL player is decided here, where {@code Minecraft} is at hand —
     * the distinction the whole scenario rests on, since a client always draws itself and a run that
     * counted that would report the gate as firing on a subject that was never rendered.</p>
     */
    @Inject(method = "modelRotationFor", at = @At("RETURN"))
    private static void arTest$modelRotation(EntityLivingBase entity, float partialTicks,
                                             CallbackInfoReturnable<double[]> cir) {
        TestTrace.instrumentHere("remote_model_events");
        RemoteModelWindow.sample(entity,
                entity != null && entity == Minecraft.getMinecraft().player, cir.getReturnValue());
    }
}
