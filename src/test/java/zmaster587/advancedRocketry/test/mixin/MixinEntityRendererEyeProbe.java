package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.client.DeckLook;
import zmaster587.advancedRocketry.test.trace.DeckCameraState;
import zmaster587.advancedRocketry.test.trace.FrameStepWindow;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * Where the renderer put the eye this frame, and how the camera path moved between frames.
 *
 * <h2>What this replaces</h2>
 *
 * <p>Production's eye-offset redirect used to end by filling three statics with the eye position and
 * calling {@code ShipFrameCamera.recordFrameInterp} — a method with an EMPTY body kept in shipping
 * code solely as an injection point — and it computed the interpolated camera position for no other
 * reason. All of that is gone; the redirect now does the one thing it exists for.</p>
 *
 * <h2>Two attempts that failed, and what each cost — measured 2026-09-09</h2>
 *
 * <p><b>Asking for the ship's up here.</b> The first version injected at this same RETURN and called
 * {@code ShipFrameCamera.shipUpFor}. That is not a copy of production — it is production's own
 * function — but it is a SECOND call to it on a frame where production's redirect has already made
 * the first, and for a seated pilot that runs {@code forShipPilot → getTileEntity + isManagedByShip}
 * plus a slerp. At identical suite load the six-suite gate went 41/42 with that probe and 42/42
 * without it, and the scenario it broke was the seated pilot's rotation brake — three subsystems
 * from anything this touches. <b>An instrument may observe the game; it may not change its
 * timing.</b></p>
 *
 * <p><b>Redirecting production's own translate.</b> The second version redirected the
 * {@code GlStateManager.translate} inside {@code advancedrocketry$shipEyeOffset}, which would have
 * handed over the offset with no extra work at all. It never wove: that method is ADDED to
 * {@code EntityRenderer} by a production mixin, and a test mixin does not get to inject into it.
 * The scenarios read {@code (0,0,0)} for the eye, which is how it was caught.</p>
 *
 * <h2>What this version does</h2>
 *
 * <p>The ship's up is taken from {@link DeckCameraState#consumeFrameShipUp()} — the vector
 * production handed the camera-setup event a few instructions earlier IN THIS SAME METHOD. Vanilla
 * posts {@code EntityViewRenderEvent.CameraSetup} inside {@code orientCamera} and pushes the
 * eye-height translate afterwards, so the ordering is a fact of the vanilla source, not an
 * assumption. Consuming it also means a frame that engaged no ship-frame camera records no eye,
 * which is production's own condition rather than a re-derivation of it.</p>
 *
 * <p>Nothing here re-derives the ship's up, the eye height, or the decision that an offset applies.
 * The only arithmetic is the entity's own {@code prevPos}/{@code pos} lerp — vanilla's definition of
 * where a body is drawn — and adding an eye height along a vector that was read, which is the
 * definition of the eye point. If production's offset breaks, this breaks with it.</p>
 *
 * <p>Client render thread. Test source set.</p>
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRendererEyeProbe {

    @Inject(method = "orientCamera", at = @At("RETURN"))
    private void arTest$aboardEye(float partialTicks, CallbackInfo ci) {
        double[] up = DeckCameraState.consumeFrameShipUp();
        if (up == null) {
            return;
        }
        TestTrace.instrumentHere("aboard_frame_interp");
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if (view == null) {
            return;
        }
        double px = view.prevPosX + (view.posX - view.prevPosX) * partialTicks;
        double py = view.prevPosY + (view.posY - view.prevPosY) * partialTicks;
        double pz = view.prevPosZ + (view.posZ - view.prevPosZ) * partialTicks;
        // The deck reference comes from production's public DeckLook.refWorldAt, so the relative
        // half of the smoothness measure is against the same frame-lerped point production used.
        FrameStepWindow.sample(px, py, pz, DeckLook.refWorldAt(partialTicks));
        float eyeHeight = view.getEyeHeight();
        DeckCameraState.noteEye(px + eyeHeight * up[0], py + eyeHeight * up[1],
                pz + eyeHeight * up[2]);
    }
}
