package zmaster587.advancedRocketry.test.mixin;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.multiplayer.WorldClient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.client.render.planet.BoundarySky;
import zmaster587.advancedRocketry.network.PacketSystemBodiesSync;
import zmaster587.advancedRocketry.command.test.RenderDiag;

/**
 * Counts what the sky renderer actually DREW, without the renderer keeping a count.
 *
 * <h2>Two of these are reconstructions, not relocations</h2>
 *
 * <p>`boundariesDrawnLastFrame` and `labelsDrawnLastFrame` used to be locals that production tallied
 * across its own draw loops and then published into statics. Nothing here relocates that tally: the
 * mixin watches the SAME production calls the tally was counting — each {@code drawBoundary} /
 * {@code drawBody} return — and adds them up on the test side, resetting at the frame's head. The
 * number is identical and production keeps no counter at all.</p>
 *
 * <p>A per-frame injection on a render path is the one place this could cost something in a test.
 * It is two boolean increments per drawn body, and the class it lives in does not exist outside a
 * harness-launched client.</p>
 */
@Mixin(BoundarySky.class)
public abstract class MixinBoundarySkyDiag {

    @Inject(method = "render", at = @At("HEAD"))
    private void arTest$skyFrameBegun(float partialTicks, WorldClient world, Minecraft mc,
                                      CallbackInfo ci) {
        RenderDiag.skyFrameBegun();
    }

    /**
     * TAIL and not RETURN-of-each-loop: what a frame drew is only final once the frame is, and a
     * reader that samples mid-frame would see a count still climbing.
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void arTest$skyFrameEnded(float partialTicks, WorldClient world, Minecraft mc,
                                      CallbackInfo ci) {
        RenderDiag.skyFrameEnded();
    }

    @Inject(method = "drawBackdrop", at = @At("RETURN"))
    private void arTest$nebulaeDrawn(List<PacketSystemBodiesSync.RenderNebula> clouds,
                                     CallbackInfoReturnable<Integer> cir) {
        RenderDiag.nebulaeDrawn(cir.getReturnValue());
    }

    @Inject(method = "drawBoundary", at = @At("RETURN"))
    private void arTest$boundaryDrawn(BufferBuilder buffer,
                                      PacketSystemBodiesSync.RenderBody body,
                                      CallbackInfoReturnable<Boolean> cir) {
        RenderDiag.boundaryDrawn(cir.getReturnValue());
    }

    @Inject(method = "drawBody", at = @At("RETURN"))
    private void arTest$bodyDrawn(BufferBuilder buffer,
                                  PacketSystemBodiesSync.RenderBody body, boolean labels,
                                  CallbackInfoReturnable<Boolean> cir) {
        RenderDiag.bodyDrawn(cir.getReturnValue());
    }
}
