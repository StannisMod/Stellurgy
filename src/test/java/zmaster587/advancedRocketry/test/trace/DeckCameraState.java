package zmaster587.advancedRocketry.test.trace;

/**
 * What the deck camera was last handed on this client — the whole of what a test polls about it.
 *
 * <p><b>Where this came from.</b> Production used to keep every field below as a
 * {@code public static volatile} on {@code ShipFrameCamera}, filled by {@code record*} methods that
 * existed for no other purpose — one of them with an EMPTY body, kept in shipping code purely as an
 * injection point. Nothing in production ever read them. They are here now, written by
 * {@link zmaster587.advancedRocketry.test.mixin.MixinRocketEventHandlerCameraFrame} and
 * {@link zmaster587.advancedRocketry.test.mixin.MixinEntityRendererEyeProbe} from the render paths
 * that compute them, so the client that ships to a player carries none of it.</p>
 *
 * <p><b>Why a holder and not a record.</b> The camera is set up once per rendered frame and most of
 * these change on most frames: a ship rolling under an engaged camera moves its up vector every
 * frame with no edge to record. A per-frame record would turn its own ring over in seconds, and the
 * readers do not want a history — they want "where is the camera NOW", which is what a scenario
 * polls while it rolls a craft over and waits for it to pass vertical. The one genuine EDGE,
 * {@code active} changing, is still a record ({@code deck_camera_changed}).</p>
 *
 * <p><b>Why the CLIENT's value and not the server's.</b> The server's {@code ship-info} carries the
 * authoritative attitude quaternion, and three scenarios already derive an up-Y from it. This is
 * deliberately the other one: what the client itself believes, which is what the pilot under test is
 * looking at. Swapping one for the other would not be a migration, it would be a different
 * question — and one of the assertions that reads this says so in its own message.</p>
 *
 * <p><b>Shared across scenarios.</b> These are latest-values on one client JVM, so a reader gets
 * whatever that client last drew, for whatever ship the camera last engaged for. That was equally
 * true of the production fields they replace; what is new is that it is said here, and that the leak
 * is now confined to the test source set.</p>
 *
 * <p>Client render thread only. Test source set: absent from a released jar.</p>
 */
public final class DeckCameraState {

    private DeckCameraState() {}

    // ---- camera engagement + attitude -------------------------------------------------------

    /** Whether the ship-frame camera was engaged on the last frame that DECIDED the question.
     *  Left standing by a frame that decided nothing (no view entity, a rocket pilot, the
     *  hold-last-roll return) — production's own rule, kept. */
    public static volatile boolean active = false;

    /**
     * The world Y of the ship's local up as of the last camera setup: +1 upright, 0 on its side,
     * -1 inverted; 1.0 (identity) until one has been seen.
     */
    public static volatile double shipUpY = 1.0;

    /** The camera attitude actually pushed to the renderer last frame (degrees). Yaw is the ship's
     *  own heading, not the +180 the vanilla camera convention wants — the same number production
     *  used to store. */
    public static volatile double yaw = 0.0;
    public static volatile double pitch = 0.0;
    public static volatile double roll = 0.0;

    /** The world-frame eye position the camera was placed at last frame. */
    public static volatile double eyeX = 0.0;
    public static volatile double eyeY = 0.0;
    public static volatile double eyeZ = 0.0;

    // ---- crosshair ---------------------------------------------------------------------------

    /** The block position the client's crosshair raytrace resolved this frame, as "x,y,z" (ship
     *  blocks come back in SUBSPACE coordinates), or "" when it hit no block. Lets a client e2e
     *  assert WHAT the crosshair actually picks — the block outlined under the crosshair must be
     *  the block interacted with, at any ship attitude — with no live objectMouseOver access. */
    public static volatile String mouseOverBlock = "";

    /** Where the crosshair RAY actually originates ({@code getPositionEyes}) this frame, compared
     *  by the crosshair-picking e2e against {@link #eyeX}/{@link #eyeY}/{@link #eyeZ} (what the
     *  RENDERER was handed): the two must be one point, or the crosshair picks a block the camera
     *  is not looking at. */
    public static volatile double rayEyeX, rayEyeY, rayEyeZ;

    // ---- render-stage controls ---------------------------------------------------------------

    /** Frames on which the camera-stage render hook ran. The control for the model stage: if this
     *  advances while {@code ShipFrameCamera.modelRotationFor} is not being called, the render
     *  stage IS running and the model stage is the thing not reaching us; if neither advances,
     *  nothing is being drawn. */
    public static volatile long cameraHookCalls = 0L;

    /** Entities in the CLIENT world, sampled on the same frame as {@link #cameraHookCalls}. The
     *  other control: a draw-stage counter of zero means nothing when the subject never reached
     *  this side. {@code -1} = no client world. */
    public static volatile int loadedEntities = -1;

    /** Server PosLook packets actually applied on the client MAIN thread — the classic writer that
     *  collapses the frame's prev-&gt;pos render interpolation for that tick. A steadily climbing
     *  count while walking or jumping a deck names the server echo as the stepping's writer. */
    public static volatile long posLookApplies = 0L;

    // ---- writers -----------------------------------------------------------------------------

    /** Note a camera setup's ship up. A null {@code shipUp} leaves the last value standing —
     *  production's own rule, kept: a frame that hands no up vector is not a statement that the
     *  ship became upright. */
    public static void noteCamera(double[] shipUp) {
        if (shipUp != null) {
            shipUpY = shipUp[1];
        }
    }

    /**
     * The ship's up as of THIS frame's camera setup, for the eye probe a few instructions later —
     * consumed once and cleared, so a frame that engaged no ship-frame camera records no eye.
     *
     * <p>This is what keeps the eye probe from asking {@code ShipFrameCamera.shipUpFor} itself.
     * That question is production's and production has already asked it this frame; asking it a
     * SECOND time is what an earlier version did, and for a seated pilot it runs
     * {@code forShipPilot → getTileEntity + isManagedByShip} plus a slerp on every rendered frame.
     * Measured 2026-09-09: that doubling alone failed a control-loop scenario three subsystems away
     * (six-suite gate 41/42 with it, 42/42 without). An instrument may observe the game; it may not
     * change its timing.</p>
     *
     * <p>The ordering is not an assumption: vanilla posts {@code EntityViewRenderEvent.CameraSetup}
     * inside {@code EntityRenderer.orientCamera} and pushes the eye-height translate afterwards, so
     * the setter below runs before the reader, in the same call, every frame.</p>
     */
    private static volatile double[] frameShipUp = null;

    /** The ship-frame camera engaged this frame, with the attitude the renderer was handed. */
    public static void noteEngaged(double yawDeg, double pitchDeg, double rollDeg, double[] shipUp) {
        noteCamera(shipUp);
        frameShipUp = shipUp;
        yaw = yawDeg;
        pitch = pitchDeg;
        roll = rollDeg;
        active = true;
    }

    /** @see #frameShipUp */
    public static double[] consumeFrameShipUp() {
        double[] up = frameShipUp;
        frameShipUp = null;
        return up;
    }

    /** The ship-frame camera was released this frame: the body is not resolved aboard, or its
     *  ship's attitude could not be resolved. */
    public static void noteDisengaged() {
        active = false;
    }

    /** Where the renderer actually put the eye this frame. */
    public static void noteEye(double x, double y, double z) {
        eyeX = x;
        eyeY = y;
        eyeZ = z;
    }

    /** What the crosshair resolved, and where its ray started. */
    public static void noteCrosshair(String block, double originX, double originY, double originZ) {
        mouseOverBlock = block;
        rayEyeX = originX;
        rayEyeY = originY;
        rayEyeZ = originZ;
    }

    /** One camera-stage frame ran, with the client world's entity population on that SAME frame. */
    public static void noteRenderStage(int entities) {
        cameraHookCalls++;
        loadedEntities = entities;
    }

    /** A server PosLook was applied on the client main thread. */
    public static void notePosLookApplied() {
        posLookApplies++;
    }
}
