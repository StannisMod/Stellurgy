package zmaster587.advancedRocketry.test.mixin;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.client.FreeFlightHudState;
import zmaster587.advancedRocketry.client.KeyBindings;
import zmaster587.advancedRocketry.event.RocketEventHandler;
import zmaster587.advancedRocketry.test.trace.FlightCameraState;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * What the Free Flight pilot's own view is doing, per rendered frame.
 *
 * <h2>What this replaces</h2>
 *
 * <p>Five statics on {@code RocketEventHandler}: the HUD text of the last drawn frame, the running
 * and last-frame camera-vs-craft divergence, the camera roll, and the most negative nose Z of the
 * flight. All five were written on the render thread and read by client e2e over the socket, and the
 * class that carried them ships to players. {@link FlightCameraState} holds them now, and says why a
 * ~120 Hz sampler gets a holder rather than a record each.</p>
 *
 * <h2>Three seams, and what each is for</h2>
 *
 * <ul>
 *   <li>{@code renderFreeFlightHud} — the HUD line. Composed HERE from the same
 *       {@code FreeFlightHudState} snapshot production draws from, through the same public
 *       {@code KeyBindings.freeFlightHudLines}, so it is the text on the screen and not a
 *       reconstruction of it.</li>
 *   <li>{@code noteCameraLock} — the camera and the craft as ONE pair of numbers on one frame. Two
 *       reflective reads could straddle a tracker-quantisation bleed tick and report a divergence
 *       neither side ever had; that is why the comparison happens on the frame.</li>
 *   <li>{@code noteFlightCamera} — the roll the camera was set to, and where the nose pointed. The
 *       perception contracts: mouse-horizontal must BANK, and a pitch input must be able to loop
 *       past vertical instead of stopping at a ±85° clamp.</li>
 * </ul>
 *
 * <p>The flight's WINDOW comes from production: {@code noteCameraLock} is called on every frame,
 * pinned or not, and a not-in-flight frame clears the extrema. Without that a previous flight's worst
 * frame would be read as this one's.</p>
 *
 * <p>{@code renderFreeFlightHud} is private and static, which a mixin may target by name; the other
 * two are seams that exist for this. Client render thread only. Test source set: absent from a
 * released jar.</p>
 */
@Mixin(RocketEventHandler.class)
public abstract class MixinRocketEventHandlerViewState {

    private static final String INSTRUMENT = "ff_view_state";

    @Inject(method = "renderFreeFlightHud", at = @At("HEAD"))
    private static void arTest$hudLine(RenderGameOverlayEvent.Post event, Minecraft mc,
                                       FreeFlightHudState state, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        List<String> lines = KeyBindings.freeFlightHudLines(state);
        FlightCameraState.noteHud(lines == null ? "" : String.join(" | ", lines));
    }

    @Inject(method = "noteCameraLock", at = @At("HEAD"))
    private static void arTest$cameraLock(boolean pinned, boolean inFlight, float cameraYaw,
                                          float cameraPitch, float craftYaw, float craftPitch,
                                          CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        FlightCameraState.noteCameraLock(pinned, inFlight, cameraYaw, cameraPitch,
                craftYaw, craftPitch);
    }

    @Inject(method = "noteFlightCamera", at = @At("HEAD"))
    private static void arTest$flightCamera(float roll, double noseZ, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        FlightCameraState.noteFlightCamera(roll, noseZ);
    }
}
