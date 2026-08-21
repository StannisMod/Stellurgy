package zmaster587.advancedRocketry.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.client.render.planet.HyperspaceTunnel;
import zmaster587.advancedRocketry.command.test.RenderDiag;

/**
 * Counts the corridor's drawn frames.
 *
 * <p>TAIL and not HEAD: the count means "a frame was DRAWN", and the renderer can return early. The
 * production counter this replaces sat at the very end of the method for the same reason.</p>
 *
 * <p>The corridor is the only thing that tells a pilot with no controls and no readout that he is
 * moving, so "did it draw at all" is a real question for a transit test — and one production should
 * not be keeping a counter to answer.</p>
 */
@Mixin(HyperspaceTunnel.class)
public abstract class MixinHyperspaceTunnelDiag {

    @Inject(method = "render", at = @At("TAIL"))
    private static void arTest$tunnelFrameDrawn(float partialTicks, net.minecraft.world.World world,
                                                CallbackInfo ci) {
        RenderDiag.tunnelFrameDrawn();
    }
}
