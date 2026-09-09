package zmaster587.advancedRocketry.test.trace;

import java.util.Locale;

import net.minecraft.util.math.MathHelper;

/**
 * What the Free Flight pilot's own view was doing, accumulated over a window a test opens and closes.
 *
 * <p><b>Why an accumulator and not a record per frame.</b> Every value here is sampled on the RENDER
 * thread, once per frame — around 120 Hz against the log's 256-per-type ring, so a record each would
 * turn its own history over in about two seconds and a reader asking about a twenty-tick manoeuvre
 * would be reading the tail of it. Same argument, same shape, as {@link FrameStepWindow}.</p>
 *
 * <p><b>What IS a record</b> is the window's summary, written once at {@link #close()}, plus the HUD
 * line whenever it CHANGES. The HUD is text a scenario waits to see; recording every frame of it
 * would bury the change that matters under a hundred identical lines, and recording only the latest
 * in a field — which is what this class used to do — cannot say WHEN it appeared.</p>
 *
 * <p><b>Why the comparison is made here at all.</b> The camera-vs-craft divergence is a perception
 * contract about the CLIENT's own view. A bot reading the camera and the craft in two separate calls
 * could straddle a tracker-quantisation bleed tick and report a divergence neither side ever had, so
 * the pair is compared on the frame, from one pair of numbers.</p>
 *
 * <p><b>Two boundaries, and they are different.</b> {@link #open()} is the READER's — it starts the
 * window this scenario will ask about. A frame reporting {@code inFlight == false} is PRODUCTION's,
 * and it clears the extrema for the same reason it always did: one flight's worst frame must not
 * become the next flight's reading. A reader that forgets to open gets whatever the previous
 * scenario left, which is why close() records the frame count and a zero there is a reading of
 * its own.</p>
 *
 * <p>Client render thread only. Test source set: absent from a released jar.</p>
 */
public final class FlightCameraState {

    private FlightCameraState() {}

    /** The Free Flight HUD text of the last drawn frame, joined with {@code " | "}. Kept only to
     *  suppress an unchanged line: the value a reader gets comes from the {@code ff_hud} record. */
    private static String lastHud = "";

    /** Worst camera-vs-craft divergence (degrees) on any rendered frame of the current window. */
    private static double maxCameraLockErrorDeg;
    /** The same divergence for the most recent frame — at rest, what the pilot sees right now. */
    private static double lastCameraLockErrorDeg;
    /** The camera roll of the last drawn frame, degrees: the bank the pilot sees. */
    private static double ffClientCamRoll;
    /** The most negative world Z the craft's nose reached. A nose held by a ±85° clamp can never
     *  point backwards, so a value well below zero is what pins a real pitch LOOP. */
    private static double ffClientMinForwardZ = 1.0;
    /** Frames the window saw, so an empty window is distinguishable from a still one. */
    private static long frames;

    /** Start a window. Invoked from a test through the harness's static-invoke bridge. */
    public static int open() {
        maxCameraLockErrorDeg = 0.0;
        lastCameraLockErrorDeg = 0.0;
        ffClientCamRoll = 0.0;
        ffClientMinForwardZ = 1.0;
        frames = 0;
        return 0;
    }

    /**
     * End the window and record its summary as {@code flight_camera_window}; returns the frames seen.
     *
     * <p>A window that saw no frame reports {@code frames:0} and its extrema untouched — an absence,
     * not a suspiciously perfect lock.</p>
     */
    public static int close() {
        return record();
    }

    private static int record() {
        TestTrace.recordHere("flight_camera_window", String.format(Locale.ROOT,
                "\"frames\":%d,\"maxErrDeg\":%.4f,\"lastErrDeg\":%.4f,\"camRoll\":%.4f"
                        + ",\"minForwardZ\":%.4f",
                frames, maxCameraLockErrorDeg, lastCameraLockErrorDeg, ffClientCamRoll,
                ffClientMinForwardZ));
        return (int) frames;
    }

    /**
     * Record the window's numbers SO FAR without ending it — for a reader polling a value that is
     * still integrating (the bank growing, the nose coming over the top).
     *
     * <p>Same record type as {@link #close()} on purpose: the reader asks for the last one in its own
     * window either way, and a peek that wrote a different type would make "the last reading" depend
     * on which call produced it.</p>
     */
    public static int peek() {
        return record();
    }

    /** One frame of the FF camera: the roll it was set to, and where the nose was pointing. */
    public static void noteFlightCamera(double roll, double noseZ) {
        ffClientCamRoll = roll;
        if (noseZ < ffClientMinForwardZ) {
            ffClientMinForwardZ = noseZ;
        }
    }

    /** One frame of the camera-nose lock. Not in flight CLEARS the extrema — see the class note on
     *  why that boundary belongs to production's frame and not to the reader. */
    public static void noteCameraLock(boolean pinned, boolean inFlight, double cameraYaw,
                                     double cameraPitch, double craftYaw, double craftPitch) {
        if (!inFlight) {
            maxCameraLockErrorDeg = 0.0;
            lastCameraLockErrorDeg = 0.0;
            ffClientMinForwardZ = 1.0; // a fresh loop witness per flight
            return;
        }
        frames++;
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

    /**
     * The HUD line of the frame being drawn, recorded only when it CHANGED.
     *
     * <p>The line is redrawn every frame and is usually identical; what a reader waits for is the
     * moment it started saying something. One record per change keeps the ring meaningful — a
     * twenty-tick wait costs a handful of records instead of a hundred — and gives every change a
     * sequence number, which the field this replaces could not.</p>
     */
    public static void noteHud(String joinedLine) {
        String line = joinedLine == null ? "" : joinedLine;
        if (line.equals(lastHud)) {
            return;
        }
        lastHud = line;
        TestTrace.recordHere("ff_hud", "\"text\":\"" + TestTrace.json(line) + "\"");
    }
}
