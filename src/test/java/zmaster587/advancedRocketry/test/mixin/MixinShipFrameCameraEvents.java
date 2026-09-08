package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.client.DeckLook;
import zmaster587.advancedRocketry.client.ShipFrameCamera;
import zmaster587.advancedRocketry.test.trace.DeckCameraState;
import zmaster587.advancedRocketry.test.trace.FrameStepWindow;
import zmaster587.advancedRocketry.test.trace.RemoteModelWindow;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The deck camera ENGAGING — and, where production ever says so, disengaging — as a client event.
 *
 * <h2>What the event is</h2>
 *
 * <p>{@code deck_camera_changed} records that the renderer was handed a ship-frame camera whose
 * {@code active} flag differs from the one the last frame left behind. Production's own telemetry
 * point is {@code ShipFrameCamera.recordCamera(active, yaw, pitch, roll, shipUp, eyeX, eyeY, eyeZ)},
 * a public static called once per rendered frame by the two camera-setup branches of
 * {@code RocketEventHandler} (the seated pilot and the walking crew member) and by the eye-offset
 * mixin; every test used to poll the {@code shipCamActive} static it fills. A per-frame record would
 * turn the ring over in seconds, so this is an EDGE: one record when the flag changes, carrying the
 * new {@code active}, the {@code roll} (degrees) and the world-Y of the ship's up ({@code upY}: +1
 * upright, 0 on its side, -1 inverted) that came with it, and the caller's trail so the seated and the
 * walking branch are distinguishable.</p>
 *
 * <h2>Which seam, on which side</h2>
 *
 * <p>HEAD of {@code recordCamera}, before the store. The class is {@code @SideOnly(CLIENT)} and the
 * call happens on the client render thread, so the record lands in the CLIENT log through
 * {@link TestTrace#recordHere}.</p>
 *
 * <p>The "last value" the edge is measured against is production's own {@code shipCamActive} field
 * read at HEAD — not a private copy on this mixin. That choice is what makes the re-engagement
 * visible: the two release branches in {@code RocketEventHandler.onFreeFlightCameraSetup} (a body no
 * longer resolving aboard; a ship whose attitude cannot be resolved) write {@code shipCamActive =
 * false} DIRECTLY and never call {@code recordCamera}, and every production caller of
 * {@code recordCamera} passes {@code true}. A private last-value would have stayed {@code true}
 * across such a release and reported no change when the camera next engaged; the field has already
 * dropped, so the next {@code recordCamera(true, ...)} is a false→true edge here.</p>
 *
 * <h2>What it is SILENT about</h2>
 *
 * <ul>
 *   <li>The true→false edge itself. The releases write the field without passing through this seam,
 *       so the camera DISENGAGING is never recorded at the moment it happens; it shows only
 *       indirectly, as the false→true record of the next engagement — or directly, only if some
 *       caller ever passes {@code active = false} to {@code recordCamera} (none in production does
 *       today). A test that needs the release edge must read {@code shipCamActive} or wait for the
 *       next engagement; it cannot await it here.</li>
 *   <li>Any change in roll, up or eye while {@code active} stays the same — a ship rolling under an
 *       engaged camera produces no record, which is most of a roll. The up vector is held for those
 *       readers in {@link zmaster587.advancedRocketry.test.trace.DeckCameraState} (written above,
 *       BEFORE this edge test); roll and eye remain production statics.</li>
 *   <li>The very first frame: the field's initial value is {@code false}, so the first engagement of
 *       a session IS recorded; a client that starts with the field already {@code true} (there is no
 *       such path) would not be.</li>
 * </ul>
 */
@Mixin(ShipFrameCamera.class)
public abstract class MixinShipFrameCameraEvents {

    private static final String INSTRUMENT = "deck_camera_events";

    @Inject(method = "recordCamera", at = @At("HEAD"))
    private static void arTest$cameraChanged(boolean active, double yaw, double pitch, double roll,
                                             double[] shipUp, double eyeX, double eyeY, double eyeZ,
                                             CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        // The ship's up, held for the scenarios that POLL it while they roll a craft over. Taken
        // before the early return below, because that return is about the ACTIVE flag not changing
        // and the up vector changes on frames where the flag does not — which is most of a roll.
        DeckCameraState.noteCamera(shipUp);
        // HEAD, before the store: the field still holds what the previous frame — or a direct
        // release write — left in it, which is exactly the value this call is about to replace.
        if (ShipFrameCamera.shipCamActive == active) {
            return;
        }
        double upY = DeckCameraState.shipUpY;
        TestTrace.recordHere("deck_camera_changed", "\"active\":" + active
                + ",\"roll\":" + TestTrace.fmt(roll)
                + ",\"upY\":" + TestTrace.fmt(upY)
                + ",\"caller\":\"" + TestTrace.json(TestTrace.callerTrail()) + "\"");
    }

    /**
     * Every ABOARD frame's interpolated camera position, folded into the open smoothness window.
     *
     * <p>Production ran this arithmetic itself, on every rendered frame, into eight statics whose
     * only readers printed them. It is a seam now and the accumulation is {@link FrameStepWindow} —
     * see that class for why a render counter gets an accumulator and a closing record rather than a
     * record per frame.</p>
     *
     * <p>The deck reference is read HERE, from production's public {@code DeckLook.refWorldAt}, so
     * the relative half is measured against the same frame-lerped point production compared against
     * — not against a value sampled a frame later from somewhere else.</p>
     */
    @Inject(method = "recordFrameInterp", at = @At("HEAD"))
    private static void arTest$frameInterp(double x, double y, double z, float partialTicks,
                                           CallbackInfo ci) {
        TestTrace.instrumentHere("aboard_frame_interp");
        FrameStepWindow.sample(x, y, z, DeckLook.refWorldAt(partialTicks));
    }

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
