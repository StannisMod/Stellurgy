package zmaster587.advancedRocketry.test.trace;

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
 * The event log keeps 256 records per type, so a per-frame record would turn its own ring over in
 * about two seconds and a reader asking about a twenty-tick jump would be reading the tail of it. A
 * render counter is not an event chain. What IS a record is the window's SUMMARY, written once at
 * {@link #close()} — one record, in the reader's own window, carrying every number the eight
 * production statics used to publish.</p>
 *
 * <p><b>What it is, said plainly.</b> Mutable static state, per client JVM, shared by every scenario
 * in a shared-harness class — exactly what the production fields it replaces were. The difference is
 * that it is no longer in shipped code, and that a window must be OPENED: a reader that forgets
 * {@link #open()} gets whatever the previous scenario left, which is why close() records the frame
 * count beside the statistics and a zero there is a reading of its own.</p>
 *
 * <p>Client thread only — the render hook is the only writer. Test source set: absent from a
 * released jar.</p>
 */
public final class FrameStepWindow {

    private FrameStepWindow() {}

    private static long frames;
    private static long samePos;
    private static double absMax;
    private static double absSum;
    private static long absCount;
    private static double relMax;
    private static double relSum;
    private static long relCount;
    private static double lastX = Double.NaN, lastY = Double.NaN, lastZ = Double.NaN;
    private static double lastRelX = Double.NaN, lastRelY = Double.NaN, lastRelZ = Double.NaN;

    /** Start a window. Invoked from a test through the harness's static-invoke bridge. */
    public static int open() {
        frames = 0;
        samePos = 0;
        absMax = 0.0;
        absSum = 0.0;
        absCount = 0;
        relMax = 0.0;
        relSum = 0.0;
        relCount = 0;
        lastX = Double.NaN;
        lastRelX = Double.NaN;
        return 0;
    }

    /**
     * End the window and record its summary as {@code frame_step_window}; returns the frames it saw.
     *
     * <p>The ratios are computed here rather than by the reader because they are the reading: a mean
     * without its max says nothing, and a max without its count can be one frame of noise. A window
     * that sampled nothing reports {@code -1} means and ratios, which is an absence rather than a
     * suspiciously smooth zero.</p>
     */
    public static int close() {
        double absMean = absCount > 0 ? absSum / absCount : -1.0;
        double relMean = relCount > 0 ? relSum / relCount : -1.0;
        TestTrace.recordHere("frame_step_window", String.format(Locale.ROOT,
                "\"frames\":%d,\"samePos\":%d,\"samePosPct\":%d"
                        + ",\"absMax\":%.5f,\"absMean\":%.5f,\"absCount\":%d,\"absRatio\":%.2f"
                        + ",\"relMax\":%.5f,\"relMean\":%.5f,\"relCount\":%d,\"relRatio\":%.2f",
                frames, samePos, frames > 0 ? (100L * samePos / frames) : -1L,
                absMax, absMean, absCount, absMean > 0 ? absMax / absMean : -1.0,
                relMax, relMean, relCount, relMean > 0 ? relMax / relMean : -1.0));
        return (int) frames;
    }

    /** One aboard frame, from the render seam. {@code deckRef} is the deck reference this frame, or
     *  null when no capture episode holds one — its absence breaks the relative chain rather than
     *  silently continuing it from the last episode. */
    public static void sample(double x, double y, double z, double[] deckRef) {
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
