package zmaster587.advancedRocketry.test.trace;

import java.util.List;
import java.util.Locale;

/**
 * What the deck camera was last handed on this client, and the per-scenario counters beside it.
 *
 * <p><b>Where this came from.</b> Production used to keep every value below as a
 * {@code public static volatile} on {@code ShipFrameCamera}, filled by {@code record*} methods that
 * existed for no other purpose — one of them with an EMPTY body, kept in shipping code purely as an
 * injection point. Nothing in production ever read them. They are written now by
 * {@link zmaster587.advancedRocketry.test.mixin.MixinRocketEventHandlerCameraFrame},
 * {@link zmaster587.advancedRocketry.test.mixin.MixinEntityRendererEyeProbe} and
 * {@link zmaster587.advancedRocketry.test.mixin.MixinNetHandlerPosLookCount} from the render paths
 * that compute them, so the client that ships to a player carries none of it.</p>
 *
 * <h2>Two owners, because there are two kinds of value</h2>
 *
 * <p><b>The camera's own state is the CLIENT's</b> — {@link Memory}, one per client, in its
 * {@link SideTrace}. Whether the ship-frame camera is engaged, the attitude and eye it was handed:
 * these describe what this client is drawing, whether or not a test is watching, and the edge
 * recorder needs the previous frame to say "it engaged" at all. The one genuine EDGE is a record
 * ({@code deck_camera_changed}, written by the mixin); the rest change most frames with no edge, and
 * a record each would turn the log's 256-per-type ring over in about two seconds.</p>
 *
 * <p><b>The counters are the SCENARIO's</b> — this class's instances, one per window.
 * {@code cameraHookCalls} and {@code posLookApplies} count frames and packets, and a count means
 * something only over a window someone chose: cumulative for the life of the client, a threshold on
 * either would be satisfied by whatever ran before. A scenario opens its window and reads it by
 * handle; {@link #peek(int)} writes the client's camera state and THIS window's counters as one
 * {@code deck_camera} record, so every reading is attributable to a moment.</p>
 *
 * <p><b>Why the CLIENT's value and not the server's.</b> The server's {@code ship-info} carries the
 * authoritative attitude quaternion, and three scenarios already derive an up-Y from it. This is
 * deliberately the other one: what the client itself believes, which is what the pilot under test is
 * looking at. Swapping one for the other would not be a migration, it would be a different
 * question.</p>
 *
 * <p>Client render thread only. Test source set: absent from a released jar.</p>
 */
public final class DeckCameraState implements TraceWindow {

    /** Frames on which the camera-stage render hook ran, in this window. The control for the model
     *  stage: if this advances while {@code ShipFrameCamera.modelRotationFor} is not being called,
     *  the render stage IS running and the model stage is the thing not reaching us; if neither
     *  advances, nothing is being drawn. */
    private long cameraHookCalls;

    /** Server PosLook packets applied on the client MAIN thread in this window — the classic writer
     *  that collapses the frame's prev-&gt;pos render interpolation for that tick. A steadily
     *  climbing count while walking or jumping a deck names the server echo as the stepping's
     *  writer. */
    private long posLookApplies;

    private DeckCameraState() {}

    /** The client's camera state: one per client, kept in its {@link SideTrace}. */
    public static final class Memory {

        /** Whether the ship-frame camera was engaged on the last frame that DECIDED the question.
         *  Left standing by a frame that decided nothing (no view entity, a rocket pilot, the
         *  hold-last-roll return) — production's own rule, kept. */
        boolean active;

        /** The world Y of the ship's local up as of the last camera setup: +1 upright, 0 on its
         *  side, -1 inverted; 1.0 (identity) until one has been seen. */
        double shipUpY = 1.0;

        /** The camera attitude actually pushed to the renderer last frame (degrees). Yaw is the
         *  ship's own heading, not the +180 the vanilla camera convention wants. */
        double yaw;
        double pitch;
        double roll;

        /** The world-frame eye position the camera was placed at last frame. */
        double eyeX;
        double eyeY;
        double eyeZ;

        /** The block the crosshair last resolved, as "x,y,z", or "" for none — kept only to write
         *  {@code deck_crosshair} when it CHANGES. */
        String mouseOverBlock = "";

        /** Entities in the CLIENT world on the last camera-stage frame; {@code -1} = no client
         *  world. The other control: a draw-stage counter of zero means nothing when the subject
         *  never reached this side. */
        int loadedEntities = -1;

        /**
         * The ship's up as of THIS frame's camera setup, for the eye probe a few instructions later —
         * consumed once and cleared, so a frame that engaged no ship-frame camera records no eye.
         *
         * <p>This is what keeps the eye probe from asking {@code ShipFrameCamera.shipUpFor} itself.
         * That question is production's and production has already asked it this frame; asking it a
         * SECOND time is what an earlier version did, and for a seated pilot it runs
         * {@code forShipPilot → getTileEntity + isManagedByShip} plus a slerp on every rendered
         * frame. Measured 2026-09-09: that doubling alone failed a control-loop scenario three
         * subsystems away (six-suite gate 41/42 with it, 42/42 without). An instrument may observe
         * the game; it may not change its timing.</p>
         *
         * <p>The ordering is not an assumption: vanilla posts {@code EntityViewRenderEvent.CameraSetup}
         * inside {@code EntityRenderer.orientCamera} and pushes the eye-height translate afterwards,
         * so the setter runs before the reader, in the same call, every frame.</p>
         */
        double[] frameShipUp;
    }

    private static Memory memory() {
        return SideTrace.client().memory(Memory.class, Memory::new);
    }

    private static List<DeckCameraState> windows() {
        return SideTrace.client().windows(DeckCameraState.class);
    }

    // ---- writers, from the render seams --------------------------------------------------------

    /** Note a camera setup's ship up. A null {@code shipUp} leaves the last value standing —
     *  production's own rule, kept: a frame that hands no up vector is not a statement that the
     *  ship became upright. */
    public static void noteCamera(double[] shipUp) {
        if (shipUp != null) {
            memory().shipUpY = shipUp[1];
        }
    }

    /** The ship-frame camera engaged this frame, with the attitude the renderer was handed. */
    public static void noteEngaged(double yawDeg, double pitchDeg, double rollDeg, double[] shipUp) {
        noteCamera(shipUp);
        Memory m = memory();
        m.frameShipUp = shipUp;
        m.yaw = yawDeg;
        m.pitch = pitchDeg;
        m.roll = rollDeg;
        m.active = true;
    }

    /** @see Memory#frameShipUp */
    public static double[] consumeFrameShipUp() {
        Memory m = memory();
        double[] up = m.frameShipUp;
        m.frameShipUp = null;
        return up;
    }

    /** The ship-frame camera was released this frame: the body is not resolved aboard, or its
     *  ship's attitude could not be resolved. */
    public static void noteDisengaged() {
        memory().active = false;
    }

    /** Where the renderer actually put the eye this frame. */
    public static void noteEye(double x, double y, double z) {
        Memory m = memory();
        m.eyeX = x;
        m.eyeY = y;
        m.eyeZ = z;
    }

    /**
     * What the crosshair resolved, and where its ray started — recorded when the BLOCK changes.
     *
     * <p>This fires every frame and the answer is usually the same block; what a reader waits for
     * is the moment it became a different one. One record per change keeps the ring meaningful and
     * gives each change a sequence number. The ray origin rides along because the two are read
     * together: where the crosshair landed is only interpretable beside where its ray started.</p>
     */
    public static void noteCrosshair(String block, double originX, double originY, double originZ) {
        String resolved = block == null ? "" : block;
        Memory m = memory();
        if (resolved.equals(m.mouseOverBlock)) {
            return;
        }
        m.mouseOverBlock = resolved;
        TestTrace.recordHere("deck_crosshair", String.format(Locale.ROOT,
                "\"block\":\"%s\",\"rayEyeX\":%.5f,\"rayEyeY\":%.5f,\"rayEyeZ\":%.5f",
                TestTrace.json(resolved), originX, originY, originZ));
    }

    /** One camera-stage frame ran, with the client world's entity population on that SAME frame. */
    public static void noteRenderStage(int entities) {
        memory().loadedEntities = entities;
        List<DeckCameraState> open = windows();
        for (int i = 0; i < open.size(); i++) {
            open.get(i).cameraHookCalls++;
        }
    }

    /** A server PosLook was applied on the client main thread. */
    public static void notePosLookApplied() {
        List<DeckCameraState> open = windows();
        for (int i = 0; i < open.size(); i++) {
            open.get(i).posLookApplies++;
        }
    }

    // ---- read accessors for the RECORDING mixins, which need the previous state to say whether
    // this frame CHANGED anything ("the camera engaged", "it was released").

    /** Whether the ship-frame camera was engaged as of the last frame. */
    public static boolean isActive() {
        return memory().active;
    }

    /** The camera roll of the last drawn frame, degrees. */
    public static double roll() {
        return memory().roll;
    }

    /** The ship's up-Y as of the last camera setup that carried one. */
    public static double shipUpY() {
        return memory().shipUpY;
    }

    // ---- the window a scenario owns ------------------------------------------------------------

    /** Start a window: its counters start at zero. Answers its handle. */
    public static int open() {
        return SideTrace.client().open(new DeckCameraState());
    }

    /** Write the camera as it stands and this window's counters, without ending the window. */
    public static int peek(int handle) {
        return SideTrace.client().window(handle, DeckCameraState.class).record();
    }

    /** Write them and end the window. */
    public static int close(int handle) {
        return SideTrace.client().close(handle, DeckCameraState.class).record();
    }

    private int record() {
        Memory m = memory();
        TestTrace.recordHere("deck_camera", String.format(Locale.ROOT,
                "\"active\":%b,\"shipUpY\":%.5f,\"yaw\":%.4f,\"pitch\":%.4f,\"roll\":%.4f"
                        + ",\"eyeX\":%.5f,\"eyeY\":%.5f,\"eyeZ\":%.5f"
                        + ",\"cameraHookCalls\":%d,\"posLookApplies\":%d,\"loadedEntities\":%d",
                m.active, m.shipUpY, m.yaw, m.pitch, m.roll, m.eyeX, m.eyeY, m.eyeZ,
                cameraHookCalls, posLookApplies, m.loadedEntities));
        return (int) cameraHookCalls;
    }
}
