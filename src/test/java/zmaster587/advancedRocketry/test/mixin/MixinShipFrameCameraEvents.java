package zmaster587.advancedRocketry.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.client.ShipFrameCamera;
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
 *       engaged camera produces no record. The per-frame values remain on the statics.</li>
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
        // HEAD, before the store: the field still holds what the previous frame — or a direct
        // release write — left in it, which is exactly the value this call is about to replace.
        if (ShipFrameCamera.shipCamActive == active) {
            return;
        }
        // Mirror the store: production keeps the old up-Y when it is handed no up vector.
        double upY = shipUp != null ? shipUp[1] : ShipFrameCamera.shipUpY;
        TestTrace.recordHere("deck_camera_changed", "\"active\":" + active
                + ",\"roll\":" + TestTrace.fmt(roll)
                + ",\"upY\":" + TestTrace.fmt(upY)
                + ",\"caller\":\"" + TestTrace.json(TestTrace.callerTrail()) + "\"");
    }
}
