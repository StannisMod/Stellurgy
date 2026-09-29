package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.client.render.planet.BoundarySky;
import zmaster587.advancedRocketry.test.trace.RenderFrameWindow;

/**
 * Counts the sky renderer's frames into every open {@link RenderFrameWindow} — the denominator for
 * every other sky reading, without the renderer keeping a count.
 *
 * <p>HEAD and not TAIL: the count means "the renderer RAN", including a frame that returned early
 * into the hyperspace corridor. What a frame DREW is {@code MixinBoundarySkyEvents}' record.</p>
 *
 * <p>Client. Test source set.</p>
 */
@Mixin(BoundarySky.class)
public abstract class MixinBoundarySkyDiag {

    @Inject(method = "render", at = @At("HEAD"))
    private void arTest$skyFrameBegun(float partialTicks, WorldClient world, Minecraft mc,
                                      CallbackInfo ci) {
        RenderFrameWindow.skyFrame();
    }
}
