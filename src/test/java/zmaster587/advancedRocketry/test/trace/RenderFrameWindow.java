package zmaster587.advancedRocketry.test.trace;

import java.util.List;
import java.util.Locale;

/**
 * How many frames the client's boundary SKY and hyperspace CORRIDOR renderers ran, over a window a
 * scenario opens.
 *
 * <p>The frame count is the denominator every other sky reading needs: a zero on "how much was
 * drawn" has several causes that look identical from outside — the renderer drew nothing, it never
 * ran, or the render stage is not reached — and only "it ran N frames" separates them. The corridor's
 * count is the transit's only visible signal to a pilot with no controls and no readout.</p>
 *
 * <p>Counted per frame, which no record can carry (the ring is 256 per type); the window's reading is
 * the {@code render_frame_window} record its {@code peek}/{@code close} writes. These were two
 * cumulative {@code public static volatile} longs in the main jar's probe package, read across the
 * socket by reflection, until 2026-09-23.</p>
 *
 * <p>Client render thread only. Test source set: absent from a released jar.</p>
 */
public final class RenderFrameWindow implements TraceWindow {

    private long skyFrames;
    private long tunnelFrames;

    private RenderFrameWindow() {}

    /** Start a window; answers its handle. */
    public static int open() {
        return SideTrace.client().open(new RenderFrameWindow());
    }

    /** Write the counts as they stand, without ending the window. */
    public static int peek(int handle) {
        return SideTrace.client().window(handle, RenderFrameWindow.class).record();
    }

    /** Write the counts and end the window. */
    public static int close(int handle) {
        return SideTrace.client().close(handle, RenderFrameWindow.class).record();
    }

    private int record() {
        TestTrace.recordHere("render_frame_window", String.format(Locale.ROOT,
                "\"skyFrames\":%d,\"tunnelFrames\":%d", skyFrames, tunnelFrames));
        return (int) skyFrames;
    }

    /** One boundary-sky frame began. */
    public static void skyFrame() {
        List<RenderFrameWindow> open = SideTrace.client().windows(RenderFrameWindow.class);
        for (int i = 0; i < open.size(); i++) {
            open.get(i).skyFrames++;
        }
    }

    /** One corridor frame was drawn. */
    public static void tunnelFrame() {
        List<RenderFrameWindow> open = SideTrace.client().windows(RenderFrameWindow.class);
        for (int i = 0; i < open.size(); i++) {
            open.get(i).tunnelFrames++;
        }
    }
}
