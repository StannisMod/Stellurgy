package zmaster587.advancedRocketry.test.trace;

import java.util.List;
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
 * <p><b>What IS a record</b> is the window's summary, written at {@link #close(int)} or
 * {@link #peek(int)}, plus the HUD line whenever it CHANGES. The HUD is text a scenario waits to see;
 * recording every frame of it would bury the change that matters under a hundred identical lines.
 * The line last drawn is the client's, not a window's — it is what the pilot is looking at whether
 * or not a test is — so it is kept in the client's {@link SideTrace} as {@link Hud}.</p>
 *
 * <p><b>Why the comparison is made here at all.</b> The camera-vs-craft divergence is a perception
 * contract about the CLIENT's own view. A bot reading the camera and the craft in two separate calls
 * could straddle a tracker-quantisation bleed tick and report a divergence neither side ever had, so
 * the pair is compared on the frame, from one pair of numbers.</p>
 *
 * <p><b>Two boundaries, and they are different.</b> {@link #open()} is the READER's — it creates the
 * window this scenario will ask about, by handle. A frame reporting {@code inFlight == false} is
 * PRODUCTION's, and it clears every open window's extrema for the same reason it always did: one
 * flight's worst frame must not become the next flight's reading.</p>
 *
 * <p>Client render thread only. Test source set: absent from a released jar.</p>
 */
public final class FlightCameraState implements TraceWindow {

    /** Worst camera-vs-craft divergence (degrees) on any rendered frame of this window. */
    private double maxCameraLockErrorDeg;
    /** The same divergence for the most recent frame — at rest, what the pilot sees right now. */
    private double lastCameraLockErrorDeg;
    /** The camera roll of the last drawn frame, degrees: the bank the pilot sees. */
    private double ffClientCamRoll;
    /** The most negative world Z the craft's nose reached. A nose held by a ±85° clamp can never
     *  point backwards, so a value well below zero is what pins a real pitch LOOP. */
    private double ffClientMinForwardZ = 1.0;
    /** Frames the window saw, so an empty window is distinguishable from a still one. */
    private long frames;
    /**
     * In-flight frames on which the camera was PINNED — the only frames the divergence extrema are
     * taken over. Cleared with them at a not-in-flight frame, so it always counts the frames the
     * extrema describe. Without it a window that measured no pinned frame reports a divergence of
     * {@code 0.0}, which reads as a perfect lock: the extrema start at zero and a camera nobody pinned
     * never raises them.
     */
    private long pinnedFrames;

    private FlightCameraState() {}

    /** The HUD line of the last drawn frame, joined with {@code " | "} — the client's, kept only to
     *  suppress an unchanged line: the value a reader gets comes from the {@code ff_hud} record. */
    public static final class Hud {
        String last = "";
        /** The client world's clock when the HUD was last drawn; see {@link #noteHud}. */
        long lastDrawnAt = Long.MIN_VALUE;
    }

    private static List<FlightCameraState> windows() {
        return SideTrace.client().windows(FlightCameraState.class);
    }

    /** Start a window; answers its handle. Invoked from a test through the static-invoke bridge. */
    public static int open() {
        return SideTrace.client().open(new FlightCameraState());
    }

    /**
     * End the window and record its summary as {@code flight_camera_window}; returns the frames seen.
     *
     * <p>A window that saw no frame reports {@code frames:0} and its extrema untouched — an absence,
     * not a suspiciously perfect lock.</p>
     */
    public static int close(int handle) {
        return SideTrace.client().close(handle, FlightCameraState.class).record();
    }

    /**
     * Record the window's numbers SO FAR without ending it — for a reader polling a value that is
     * still integrating (the bank growing, the nose coming over the top).
     *
     * <p>Same record type as {@link #close(int)} on purpose: the reader asks for the last one in its
     * own window either way, and a peek that wrote a different type would make "the last reading"
     * depend on which call produced it.</p>
     */
    public static int peek(int handle) {
        return SideTrace.client().window(handle, FlightCameraState.class).record();
    }

    private int record() {
        TestTrace.recordHere("flight_camera_window", String.format(Locale.ROOT,
                "\"frames\":%d,\"pinnedFrames\":%d,\"maxErrDeg\":%.4f,\"lastErrDeg\":%.4f"
                        + ",\"camRoll\":%.4f,\"minForwardZ\":%.4f",
                frames, pinnedFrames, maxCameraLockErrorDeg, lastCameraLockErrorDeg,
                ffClientCamRoll, ffClientMinForwardZ));
        return (int) frames;
    }

    /** One frame of the FF camera: the roll it was set to, and where the nose was pointing. */
    public static void noteFlightCamera(double roll, double noseZ) {
        List<FlightCameraState> open = windows();
        for (int i = 0; i < open.size(); i++) {
            FlightCameraState w = open.get(i);
            w.ffClientCamRoll = roll;
            if (noseZ < w.ffClientMinForwardZ) {
                w.ffClientMinForwardZ = noseZ;
            }
        }
    }

    /** One frame of the camera-nose lock. Not in flight CLEARS the extrema — see the class note on
     *  why that boundary belongs to production's frame and not to the reader. */
    public static void noteCameraLock(boolean pinned, boolean inFlight, double cameraYaw,
                                     double cameraPitch, double craftYaw, double craftPitch) {
        List<FlightCameraState> open = windows();
        for (int i = 0; i < open.size(); i++) {
            open.get(i).cameraLock(pinned, inFlight, cameraYaw, cameraPitch, craftYaw, craftPitch);
        }
    }

    private void cameraLock(boolean pinned, boolean inFlight, double cameraYaw, double cameraPitch,
                            double craftYaw, double craftPitch) {
        if (!inFlight) {
            maxCameraLockErrorDeg = 0.0;
            lastCameraLockErrorDeg = 0.0;
            pinnedFrames = 0;
            ffClientMinForwardZ = 1.0; // a fresh loop witness per flight
            return;
        }
        frames++;
        if (!pinned) {
            return; // an unpinned camera is free by design; its divergence measures nothing
        }
        pinnedFrames++;
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
     * sequence number.</p>
     *
     * <p><b>A HUD that APPEARS is a change too</b>, even with the line it last showed. The memory
     * lives as long as the client, and nothing is drawn while no rocket is flown — so without this,
     * a scenario whose first HUD line matched the previous scenario's last one recorded nothing,
     * and a wait for "the HUD shows X" passed or expired on test ORDER. A gap of more than one tick
     * of the client world's clock since the last draw is read as the HUD having been away.</p>
     */
    public static void noteHud(String joinedLine) {
        String line = joinedLine == null ? "" : joinedLine;
        Hud hud = SideTrace.client().memory(Hud.class, Hud::new);
        net.minecraft.client.multiplayer.WorldClient world =
                net.minecraft.client.Minecraft.getMinecraft().world;
        long now = world == null ? Long.MIN_VALUE : world.getTotalWorldTime();
        boolean reappeared = now == Long.MIN_VALUE || hud.lastDrawnAt == Long.MIN_VALUE
                || now > hud.lastDrawnAt + 1;
        hud.lastDrawnAt = now;
        if (line.equals(hud.last) && !reappeared) {
            return;
        }
        hud.last = line;
        TestTrace.recordHere("ff_hud", "\"text\":\"" + TestTrace.json(line) + "\"");
    }
}
