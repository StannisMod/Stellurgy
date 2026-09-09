package zmaster587.advancedRocketry.test.mixin;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.client.FreeFlightHudState;
import zmaster587.advancedRocketry.client.KeyBindings;
import zmaster587.advancedRocketry.entity.EntityRocket;
import zmaster587.advancedRocketry.event.RocketEventHandler;
import zmaster587.advancedRocketry.test.trace.FlightCameraState;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * What the Free Flight pilot's own view is doing, per rendered frame.
 *
 * <h2>What this replaces</h2>
 *
 * <p>Five statics on {@code RocketEventHandler} — the HUD text of the last drawn frame, the running
 * and last-frame camera-vs-craft divergence, the camera roll, and the most negative nose Z of the
 * flight — and then, for a while, two EMPTY methods that had been left behind to be injected into.
 * {@link FlightCameraState} holds the values; the two seams are gone, and nothing in
 * {@code src/main} exists for this file's benefit any more.</p>
 *
 * <h2>Where each reading now comes from</h2>
 *
 * <ul>
 *   <li><b>The HUD line</b> — {@code renderFreeFlightHud}, which is real production code that draws
 *       it. Composed here from the same {@code FreeFlightHudState} the method was handed, through
 *       the same public {@code KeyBindings.freeFlightHudLines}, so it is the text on the screen.</li>
 *   <li><b>The camera roll and the nose Z</b> — a redirect of {@code eulerFromQuat} in the ROCKET
 *       branch of {@code onFreeFlightCameraSetup} (ordinal 0; ordinal 1 is the seated ship pilot and
 *       ordinal 2 the walking crew member). That call CARRIES the attitude quaternion as its
 *       argument and RETURNS the euler triple, so one hook holds both halves the old seam was handed
 *       — with no second call to anything, which is the constraint that matters on a per-frame
 *       path.</li>
 *   <li><b>The camera-vs-craft pair</b> — the head of {@code onScreenRender}, under the same three
 *       gates production applies before it would have called the seam: the HOTBAR element, a live
 *       player and world, and a rider that is a rocket. All three are READS, not decisions, so
 *       nothing in production needs to exist here for a test to inject into.</li>
 * </ul>
 *
 * <p>The pair is still taken as ONE reading on ONE frame — two reflective reads over the socket
 * could straddle a tracker-quantisation bleed tick and report a divergence neither side ever had.
 * That was the seam's stated reason for existing, and an injection at the same point keeps it.</p>
 *
 * <p>The flight's WINDOW is production's too: this fires on every rendered frame whether the camera
 * is pinned or not, and a not-in-flight frame clears the extrema, so a previous flight's worst frame
 * is never read as this one's.</p>
 *
 * <p>Client render thread only. Test source set: absent from a released jar.</p>
 */
@Mixin(RocketEventHandler.class)
public abstract class MixinRocketEventHandlerViewState {

    private static final String INSTRUMENT = "ff_view_state";

    @Inject(method = "renderFreeFlightHud", at = @At("HEAD"), remap = false)
    private static void arTest$hudLine(RenderGameOverlayEvent.Post event, Minecraft mc,
                                       FreeFlightHudState state, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        List<String> lines = KeyBindings.freeFlightHudLines(state);
        FlightCameraState.noteHud(lines == null ? "" : String.join(" | ", lines));
    }

    /**
     * The tier-1 rocket's camera this frame: the roll the pilot sees, and where the nose points.
     *
     * <p>The perception contracts behind them: mouse-horizontal must BANK the craft, and a pitch
     * input must be able to loop past vertical rather than stop at a clamp — which the most negative
     * nose Z over a flight is what pins.</p>
     */
    @Redirect(method = "onFreeFlightCameraSetup",
            at = @At(value = "INVOKE",
                    target = "Lzmaster587/advancedRocketry/api/FreeFlightPhysics;"
                            + "eulerFromQuat(Lzmaster587/advancedRocketry/api/FreeFlightPhysics$Quat;)[F",
                    ordinal = 0),
            remap = false)
    private float[] arTest$rocketCamera(FreeFlightPhysics.Quat cq) {
        float[] e = FreeFlightPhysics.eulerFromQuat(cq);
        TestTrace.instrumentHere(INSTRUMENT);
        if (e != null && cq != null) {
            FlightCameraState.noteFlightCamera(e[2], cq.rotate(0.0, 0.0, 1.0)[2]);
        }
        return e;
    }

    /** Where the pilot's camera and his craft are pointing, as one reading on one frame. */
    @Inject(method = "onScreenRender", at = @At("HEAD"), remap = false)
    private void arTest$cameraLock(RenderGameOverlayEvent.Post event, CallbackInfo ci) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.HOTBAR) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) {
            return;
        }
        Entity ride = mc.player.getRidingEntity();
        if (!(ride instanceof EntityRocket)) {
            return;
        }
        EntityRocket rocket = (EntityRocket) ride;
        TestTrace.instrumentHere(INSTRUMENT);
        FlightCameraState.noteCameraLock(
                rocket.isFreeFlight() && KeyBindings.isCameraPinnedThisFlight(),
                rocket.isInFlight(), mc.player.rotationYaw, mc.player.rotationPitch,
                rocket.rotationYaw, rocket.rotationPitch);
    }
}
