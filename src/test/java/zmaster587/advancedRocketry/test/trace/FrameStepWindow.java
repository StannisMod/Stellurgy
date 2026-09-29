package zmaster587.advancedRocketry.test.trace;

import java.util.List;
import java.util.Locale;

/**
 * Per-frame smoothness statistics of the aboard camera, accumulated over a window a test opens and
 * closes.
 *
 * <p><b>What it measures.</b> A dead prev-&gt;pos interpolation shows as consecutive frames sharing
 * one interpolated camera position: at 120 FPS against 20 TPS a healthy run repeats almost none, and
 * ~5/6 of them means the camera is stepping at tick rate. The step statistics say the same thing as
 * a distribution — a smooth path has max ~ mean, a tick-stepped one has zero steps within a tick and
 * spikes at its boundaries, so max &gt;&gt; mean. The ABSOLUTE and RELATIVE halves split "the body
 * jitters in the world" from "the body jitters against the deck it rides"; the relative reference is
 * {@code DeckLook.refWorldAt}, itself frame-lerped.</p>
 *
 * <p><b>Why an accumulator and not a record per frame.</b> This seam fires on every rendered frame.
 * The event log's ring is bounded per type, so a per-frame record would turn its own ring over in
 * seconds and a reader asking about a twenty-tick jump would be reading the tail of it — an argument
 * about a RATE, which no bound large enough to be affordable ever answers. What IS a record is the
 * window's SUMMARY, written once at {@link #close(int)}.</p>
 *
 * <p><b>Whose it is.</b> An instance per window, created by the scenario that asks and registered
 * with the client's {@link SideTrace}; the render seam feeds every open one. A scenario that did not
 * open a window has no handle to close, so it cannot be handed another scenario's frames.</p>
 *
 * <p>Client thread only — the render hook is the only writer. Test source set: absent from a
 * released jar.</p>
 */
public final class FrameStepWindow implements TraceWindow {

    private long frames;
    private long samePos;
    private double absMax;
    private double absSum;
    private long absCount;
    private double relMax;
    private double relSum;
    private long relCount;
    private double lastX = Double.NaN, lastY = Double.NaN, lastZ = Double.NaN;
    private double lastRelX = Double.NaN, lastRelY = Double.NaN, lastRelZ = Double.NaN;

    private FrameStepWindow() {}

    /** Start a window; answers its handle. Invoked from a test through the static-invoke bridge. */
    public static int open() {
        return SideTrace.client().open(new FrameStepWindow());
    }

    /**
     * End the window and record its summary as {@code frame_step_window}; returns the frames it saw.
     *
     * <p>The ratios are computed here rather than by the reader because they are the reading: a mean
     * without its max says nothing, and a max without its count can be one frame of noise. A window
     * that sampled nothing reports {@code -1} means and ratios, which is an absence rather than a
     * suspiciously smooth zero.</p>
     */
    public static int close(int handle) {
        FrameStepWindow w = SideTrace.client().close(handle, FrameStepWindow.class);
        double absMean = w.absCount > 0 ? w.absSum / w.absCount : -1.0;
        double relMean = w.relCount > 0 ? w.relSum / w.relCount : -1.0;
        TestTrace.recordHere("frame_step_window", String.format(Locale.ROOT,
                "\"frames\":%d,\"samePos\":%d,\"samePosPct\":%d"
                        + ",\"absMax\":%.5f,\"absMean\":%.5f,\"absCount\":%d,\"absRatio\":%.2f"
                        + ",\"relMax\":%.5f,\"relMean\":%.5f,\"relCount\":%d,\"relRatio\":%.2f",
                w.frames, w.samePos, w.frames > 0 ? (100L * w.samePos / w.frames) : -1L,
                w.absMax, absMean, w.absCount, absMean > 0 ? w.absMax / absMean : -1.0,
                w.relMax, relMean, w.relCount, relMean > 0 ? w.relMax / relMean : -1.0));
        return (int) w.frames;
    }

    /** One aboard frame, from the render seam, to every open window. {@code deckRef} is the deck
     *  reference this frame, or null when no capture episode holds one — its absence breaks the
     *  relative chain rather than silently continuing it from the last episode. */
    public static void sample(double x, double y, double z, double[] deckRef) {
        List<FrameStepWindow> open = SideTrace.client().windows(FrameStepWindow.class);
        for (int i = 0; i < open.size(); i++) {
            open.get(i).add(x, y, z, deckRef);
        }
    }

    private void add(double x, double y, double z, double[] deckRef) {
        frames++;
        if (x == lastX && y == lastY && z == lastZ) {
            samePos++;
        }
        if (!Double.isNaN(lastX)) {
            double step = Math.sqrt((x - lastX) * (x - lastX) + (y - lastY) * (y - lastY)
                    + (z - lastZ) * (z - lastZ));
            if (step > absMax) {
                absMax = step;
            }
            absSum += step;
            absCount++;
        }
        lastX = x;
        lastY = y;
        lastZ = z;
        if (deckRef == null) {
            lastRelX = Double.NaN;
            return;
        }
        double rx = x - deckRef[0], ry = y - deckRef[1], rz = z - deckRef[2];
        if (!Double.isNaN(lastRelX)) {
            double step = Math.sqrt((rx - lastRelX) * (rx - lastRelX)
                    + (ry - lastRelY) * (ry - lastRelY) + (rz - lastRelZ) * (rz - lastRelZ));
            if (step > relMax) {
                relMax = step;
            }
            relSum += step;
            relCount++;
        }
        lastRelX = rx;
        lastRelY = ry;
        lastRelZ = rz;
    }
}
