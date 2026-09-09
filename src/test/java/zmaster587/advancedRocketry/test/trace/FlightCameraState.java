package zmaster587.advancedRocketry.test.trace;

import net.minecraft.util.math.MathHelper;

/**
 * What the Free Flight pilot's own view was doing, as of the last rendered frame.
 *
 * <p><b>Why a holder and not records.</b> Every value here is sampled on the RENDER thread, once per
 * frame — around 120 Hz against the log's 256-per-type ring, so a record each would turn its own
 * history over in two seconds. And the readers do not want a history: they poll "what does the pilot
 * see now", or they read a per-FLIGHT extremum at the end of a flight. See
 * {@code observe-through-events-and-mixins}, the per-frame-sampler rule.</p>
 *
 * <p><b>Why the values are still POLLED at all.</b> Two of them are perception contracts and their
 * subject is the CLIENT's own view: the bank the pilot sees, and whether his nose could point
 * backwards. The server's attitude is a different question, and a bot that read the camera and the
 * craft in two separate reflective calls could straddle a tracker-quantisation bleed tick and report
 * a divergence neither side ever had — which is why the comparison is made HERE, on the frame, from
 * one pair of numbers.</p>
 *
 * <p><b>The flight window is production's, not the reader's.</b> {@link #noteCameraLock} is called on
 * every rendered frame whether the camera is pinned or not, and a frame that reports
 * {@code inFlight == false} CLEARS the extrema. That is what keeps one flight's worst frame out of
 * the next flight's reading, and it is the same rule production followed when these were its own
 * fields.</p>
 *
 * <p>Client render thread only. Test source set: absent from a released jar.</p>
 */
public final class FlightCameraState {

    private FlightCameraState() {}

    /** The Free Flight HUD text of the last drawn frame, joined with {@code " | "}; empty when no
     *  FF HUD has been drawn. Composed on the test side from the same snapshot the HUD renders. */
    public static volatile String lastFreeFlightHud = "";

    /** Worst camera-vs-craft divergence (degrees) on any rendered frame of the CURRENT flight.
     *  Bounded small while the lock holds — intra-tick mouse deflection only. */
    public static volatile double maxCameraLockErrorDeg = 0.0;

    /** The same divergence for the most recent frame — at rest, what the pilot sees right now. */
    public static volatile double lastCameraLockErrorDeg = 0.0;

    /** The camera roll of the last drawn frame, degrees: the bank the pilot sees. */
    public static volatile double ffClientCamRoll = 0.0;

    /** The most negative world Z the craft's nose reached this flight. A nose held by a ±85° clamp
     *  can never point backwards, so a value well below zero is what pins a real pitch LOOP. */
    public static volatile double ffClientMinForwardZ = 1.0;

    /** One frame of the FF camera: the roll it was set to, and where the nose was pointing. */
    public static void noteFlightCamera(double roll, double noseZ) {
        ffClientCamRoll = roll;
        if (noseZ < ffClientMinForwardZ) {
            ffClientMinForwardZ = noseZ;
        }
    }

    /** One frame of the camera-nose lock. Not in flight CLEARS the flight's extrema — see the class
     *  note on why that boundary belongs to production's frame and not to the reader. */
    public static void noteCameraLock(boolean pinned, boolean inFlight, double cameraYaw,
                                     double cameraPitch, double craftYaw, double craftPitch) {
        if (!inFlight) {
            maxCameraLockErrorDeg = 0.0;
            lastCameraLockErrorDeg = 0.0;
            ffClientMinForwardZ = 1.0; // a fresh loop witness per flight
            return;
        }
        if (!pinned) {
            return; // an unpinned camera is free by design; its divergence measures nothing
        }
        double err = Math.max(Math.abs(MathHelper.wrapDegrees(cameraYaw - craftYaw)),
                Math.abs(cameraPitch - craftPitch));
        lastCameraLockErrorDeg = err;
        if (err > maxCameraLockErrorDeg) {
            maxCameraLockErrorDeg = err;
        }
    }

    /** The HUD line of the frame being drawn. */
    public static void noteHud(String joinedLine) {
        lastFreeFlightHud = joinedLine == null ? "" : joinedLine;
    }
}
