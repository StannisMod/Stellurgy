package zmaster587.advancedRocketry.test.client;

import java.util.function.Predicate;

import com.github.stannismod.forge.testing.TestTimeouts;

/**
 * Deterministic, load-scaled "poll a client stimulus until the EVENT it is waiting for actually
 * happens" — the reusable form of the ad-hoc early-exit loops scattered across the VS client e2e
 * suite.
 *
 * <p>Every client e2e in this repo synchronises on <em>time</em>, not on the <em>event</em> it is
 * waiting for: it drives a stimulus, blocks a fixed number of ticks ({@code waitTicks(N)}), then
 * reads the world once. When concurrent-fork load stretches the time-to-converge past that fixed
 * budget (a frame-starved client under-thrusts the drive; an attitude slew settles slower), the read
 * lands on the pre-state and the test goes red <em>though nothing is broken</em>. That is the
 * false-positive class this helper removes.</p>
 *
 * <p>The fix mirrors the harness's own convention (see {@link TestTimeouts}): a ceiling that scales
 * by the same {@code forge.test.timeout.factor} the rest of the framework uses, and an EARLY EXIT
 * the moment the predicate holds — so an idle machine exits at the same iteration it always did, a
 * loaded one gets the iterations it actually needs. It advances real CLIENT ticks through the caller's
 * stepper (a {@code ClientBot::waitTicks} reference), honouring the rule that a client stimulus is
 * driven by the real client; and it reads whatever the caller observes — a client
 * static / {@code reportState} for a client-observed threshold (the continuous-threshold member), or
 * a server probe for a SETUP convergence gate, which is permitted to ARRANGE a state but never as a
 * stand-in for the client behaviour under test.</p>
 *
 * <p>Stateless static util, mirroring {@link ClientGuiTestSupport}; nothing here belongs in
 * production — a stage is never faked into the mod to make a test converge.</p>
 */
public final class ClientPoll {

    private ClientPoll() { }

    /** Advances {@code ticks} real client ticks (typically {@code ClientBot::waitTicks}). */
    @FunctionalInterface
    public interface Step {
        void waitTicks(int ticks) throws Exception;
    }

    /**
     * A WINDOW, not a poll: take {@code samples} readings {@code ticksBetween} client ticks apart and
     * hand back every one of them. No predicate, no early exit, nothing to be satisfied.
     *
     * <h2>Why this exists beside {@link #until}</h2>
     *
     * <p>{@link #until} exits the moment its predicate holds, and for the question it was written for
     * — <i>has the ship started turning</i>, <i>has it climbed two blocks</i>, <i>is there a ship in
     * the registry</i> — that is exactly right: those are LATCHING. Once true they stay true, so the
     * first observation that sees it is as good as any later one.</p>
     *
     * <p><b>A predicate that can be satisfied TRANSIENTLY is the opposite, and an early exit there is
     * a defect rather than an optimisation.</b> Ask "has the rate fallen below X" of a quantity that
     * OSCILLATES and the loop stops at the first trough — which is not the same fact as "it came to
     * rest", and nothing downstream can tell the two apart.</p>
     *
     * <p><i>Measured 2026-09-14</i>, on the one site in this suite that had that shape. A brake was
     * polled with {@code omega <= 0.05}; it reported satisfied at iteration 103 with 0.0377, while
     * its own sampled trajectory over the same window read 0.219, 0.288, 0.372, 0.123, <b>0.393</b> —
     * a rate that was rising, sampled at a dip three iterations later. The scenario went green on a
     * ship that had plainly not stopped, and the run that reddened differed only in whether a sample
     * happened to land in a trough.</p>
     *
     * <p>So the question "did it stop AND stay stopped" is asked of the whole window: take the
     * readings, and let the caller assert on the WORST of them. The window cannot end early, which is
     * the only property that makes that assertion mean anything.</p>
     *
     * <p>Samples immediately, so the first reading costs no ticks — the window is then at least
     * {@code (samples - 1) * ticksBetween} client ticks, and more on a slow box, which is the
     * harmless direction. The tick counts are how much WORLD the window covers and are therefore
     * NEVER scaled by the harness's load factor: scaling them would make the same test a different
     * experiment on every machine.</p>
     */
    public static <T> java.util.List<T> observe(Step step, Probe<T> probe,
                                                int samples, int ticksBetween) throws Exception {
        if (samples <= 0) {
            throw new IllegalArgumentException("samples must be > 0, was " + samples);
        }
        if (ticksBetween <= 0) {
            throw new IllegalArgumentException("ticksBetween must be > 0, was " + ticksBetween);
        }
        java.util.List<T> readings = new java.util.ArrayList<T>(samples);
        readings.add(probe.read());
        for (int i = 1; i < samples; i++) {
            step.waitTicks(ticksBetween);
            readings.add(probe.read());
        }
        return readings;
    }

    /** Reads the observed value once (a client static, {@code reportState}, or a server probe). */
    @FunctionalInterface
    public interface Probe<T> {
        T read() throws Exception;
    }

    /** The outcome of a poll: whether the predicate ever held, how many steps it took, the last value. */
    public static final class Result<T> {
        /** True iff {@code predicate} held on the final observation (before the ceiling was hit). */
        public final boolean satisfied;
        /** Number of {@code step} iterations actually run — 0 if already satisfied on entry. */
        public final int iterations;
        /** The load-scaled iteration ceiling this poll was allowed. */
        public final int ceiling;
        /** The last value the probe returned. */
        public final T value;

        Result(boolean satisfied, int iterations, int ceiling, T value) {
            this.satisfied = satisfied;
            this.iterations = iterations;
            this.ceiling = ceiling;
            this.value = value;
        }

        @Override
        public String toString() {
            return "ClientPoll{satisfied=" + satisfied + " iters=" + iterations + "/" + ceiling
                    + " value=" + value + "}";
        }
    }

    /**
     * Poll {@code probe} every {@code stepTicks} client ticks until {@code predicate} holds, giving up
     * after {@code baseIterations} scaled by {@link TestTimeouts#factor()}.
     *
     * @param step           advances client ticks (real client stimulus)
     * @param probe          reads the observed value each iteration
     * @param predicate      the event being waited for
     * @param stepTicks      client ticks to advance per iteration (the old {@code waitTicks(N)} N)
     * @param baseIterations single-fork iteration ceiling (the old fixed loop count); scaled by factor()
     * @return the outcome; callers assert on {@link Result#satisfied} and read {@link Result#value}
     */
    public static <T> Result<T> until(Step step, Probe<T> probe, Predicate<T> predicate,
                                      int stepTicks, int baseIterations) throws Exception {
        if (stepTicks <= 0) {
            throw new IllegalArgumentException("stepTicks must be > 0, was " + stepTicks);
        }
        if (baseIterations <= 0) {
            throw new IllegalArgumentException("baseIterations must be > 0, was " + baseIterations);
        }
        // Never below the single-fork budget (factor() clamps to >= 1); round up so a fractional
        // factor never SHRINKS the ceiling below its base.
        int ceiling = (int) Math.ceil(baseIterations * TestTimeouts.factor());

        T last = probe.read();
        int iterations = 0;
        while (iterations < ceiling && !predicate.test(last)) {
            step.waitTicks(stepTicks);
            last = probe.read();
            iterations++;
        }
        return new Result<>(predicate.test(last), iterations, ceiling, last);
    }
}
