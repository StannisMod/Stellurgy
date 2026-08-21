package zmaster587.advancedRocketry.command.test;

/**
 * What the client's own sky and corridor renderers DREW on their last frame.
 *
 * <h2>Why a drawn count and a frame count, always together</h2>
 *
 * <p>A zero on "how many boundaries were drawn" has several causes that look identical from outside:
 * the renderer drew nothing, the renderer never ran, or the render stage itself is not reached. Only
 * {@link #skyFramesDrawn} beside the per-frame counts separates them, which is why they live in one
 * place and are documented as one reading.</p>
 *
 * <h2>Where these are written from</h2>
 *
 * <p>Test-only mixins, on the client. Two of them are not relocated assignments but RECONSTRUCTIONS:
 * `boundariesDrawnLastFrame` and `labelsDrawnLastFrame` used to be locals that production counted up
 * and then published; the mixin counts the same production calls' own {@code true} returns instead,
 * resetting at the frame's head. Same number, and production no longer keeps a counter for a reader
 * that only exists in a test.</p>
 *
 * <p>Read by a client e2e through the harness's reflective static read, so the field NAMES are part
 * of the contract with those tests — they are the same names the fields had on the renderers.</p>
 */
public final class RenderDiag {

    /** Frames the boundary-sky renderer has drawn. The denominator for everything else here. */
    public static volatile long skyFramesDrawn;
    /** Nebula billboards drawn on the last sky frame. */
    public static volatile int nebulaeDrawnLastFrame;
    /** Descent-boundary rings drawn on the last sky frame. */
    public static volatile int boundariesDrawnLastFrame;
    /** Body labels drawn on the last sky frame. */
    public static volatile int labelsDrawnLastFrame;

    /** Frames the hyperspace corridor has drawn — the transit's only visible signal to a pilot. */
    public static volatile long tunnelFramesDrawn;

    /** In-flight tallies for the frame being drawn right now; published at the frame's end. */
    private static int boundariesThisFrame;
    private static int labelsThisFrame;

    private RenderDiag() { }

    /** From the test mixin at the head of a sky frame. */
    public static void skyFrameBegun() {
        skyFramesDrawn++;
        boundariesThisFrame = 0;
        labelsThisFrame = 0;
    }

    /** From the test mixin at the return of the backdrop draw, with what it drew. */
    public static void nebulaeDrawn(int count) {
        nebulaeDrawnLastFrame = count;
    }

    /** From the test mixin at each boundary draw, with that draw's own verdict. */
    public static void boundaryDrawn(boolean drawn) {
        if (drawn) {
            boundariesThisFrame++;
        }
    }

    /** From the test mixin at each body draw, with that draw's own verdict. */
    public static void bodyDrawn(boolean drawn) {
        if (drawn) {
            labelsThisFrame++;
        }
    }

    /** From the test mixin at the end of a sky frame: publish what this frame drew. */
    public static void skyFrameEnded() {
        boundariesDrawnLastFrame = boundariesThisFrame;
        labelsDrawnLastFrame = labelsThisFrame;
    }

    /** From the test mixin at the end of a corridor frame. */
    public static void tunnelFrameDrawn() {
        tunnelFramesDrawn++;
    }

    /** Forget everything. For a harness that reuses one client across scenarios. */
    public static void reset() {
        skyFramesDrawn = 0L;
        nebulaeDrawnLastFrame = 0;
        boundariesDrawnLastFrame = 0;
        labelsDrawnLastFrame = 0;
        tunnelFramesDrawn = 0L;
        boundariesThisFrame = 0;
        labelsThisFrame = 0;
    }
}
