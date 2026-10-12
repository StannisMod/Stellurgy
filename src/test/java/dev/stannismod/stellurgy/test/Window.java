package dev.stannismod.stellurgy.test;

/**
 * A WINDOW: one value read twice, the clock advanced between the two reads, and the ticks that passed
 * between them as the clock says right after each read -- never the number that was asked for.
 *
 * <p><b>Why a type.</b> A window's verdict is a statement about BOTH reads: a value that converges is
 * judged by how it changed over the ticks that really passed. Written by hand, the second read is easily
 * judged alone ("it is above X now"), which is an absolute-state read after a budget -- a poll with one
 * sample -- and a rate over the ticks ASKED for turns lenient as the box slows, silently. So this offers no
 * way to look at one read: what a test can take from it is {@link #change} and {@link #rate}, each a
 * function of the two, and {@link #toString} for the message, which prints both and the span. A window that
 * is consumed through one read does not compile.</p>
 *
 * <p><b>The span.</b> {@code stamp} is read immediately after each read, so the span carries the round
 * trip of each read on both ends, and is never the {@code ticks} asked of {@code advance}, which a slow box
 * overshoots.</p>
 *
 * <p>Not this: a window whose measurement is an EXTREMUM over many samples ({@code GameTicks#observe}), and
 * a LINK -- a record production publishes is waited on from a mark, never sampled.</p>
 *
 * @param <T> what one read answers
 */
public final class Window<T> {

    /** One read of the subject. It talks to the game, so it may throw. */
    public interface Read<T> {
        T read() throws Exception;
    }

    /** The tick of the clock the subject runs on, read right after a read. */
    public interface Stamp {
        long tick() throws Exception;
    }

    /** The number a verdict is about, out of one read. */
    public interface Measure<T> {
        double of(T reading);
    }

    private final T first;
    private final T second;
    private final long tickSpan;

    private Window(T first, T second, long tickSpan) {
        this.first = first;
        this.second = second;
        this.tickSpan = tickSpan;
    }

    /**
     * Read, stamp, advance {@code ticks}, read, stamp.
     *
     * @param advance the step that moves the clocks of every side taking part (STEP 6's table) -- a
     *                window has no default clock
     * @throws AssertionError when the stamp did not move between the reads: a clock that stood still has
     *         measured nothing, and a rate over no ticks is no verdict
     */
    public static <T> Window<T> over(Read<T> read, Stamp stamp, Events.Step advance, int ticks) throws Exception {
        if (ticks <= 0) {
            throw new IllegalArgumentException("a window advances a positive number of ticks, got " + ticks);
        }
        T first = read.read();
        long firstTick = stamp.tick();
        advance.ticks(ticks);
        T second = read.read();
        long secondTick = stamp.tick();
        if (secondTick <= firstTick) {
            throw new AssertionError("the clock stood still across the window (" + firstTick + " -> " + secondTick
                    + ", " + ticks + " ticks asked): nothing was measured. first=" + first + " second=" + second);
        }
        return new Window<>(first, second, secondTick - firstTick);
    }

    /** The ticks that passed between the two reads, by their stamps. */
    public long tickSpan() {
        return tickSpan;
    }

    /** How the measured number changed from the first read to the second. */
    public double change(Measure<T> measure) {
        return measure.of(second) - measure.of(first);
    }

    /** {@link #change} per tick that actually passed. */
    public double rate(Measure<T> measure) {
        return change(measure) / tickSpan;
    }

    /** Both reads and the span, for the message of the verdict that judged them. */
    @Override
    public String toString() {
        return "first=" + first + " second=" + second + " over " + tickSpan + " ticks";
    }
}
