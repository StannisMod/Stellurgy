package zmaster587.advancedRocketry.test;

import com.github.stannismod.forge.testing.server.TestClient;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A wait that actually waits: budget in TICKS, and say which clock you mean.
 *
 * <p>Console commands are drained on the server thread — the one thread that advances the game — so a
 * probe handler that polls a clock is blocking the very thing it is watching. Measured 2026-08-17:
 * {@code artest server wait 0 20} reports {@code elapsedTicks=0} on the OVERWORLD, a world that ticks
 * by definition. Every call site that read that verb as "N ticks have now happened" was reading a
 * sleep, and two wrong diagnoses were paid for it.</p>
 *
 * <p>So the waiting lives here, in the TEST jvm, which is idle while the server ticks: read the clock,
 * sleep, read again. The same shape the client half already uses — {@code ClientBot.waitTicks} polls a
 * counter from the bridge thread rather than from the client thread.</p>
 *
 * <h3>Why the budget is in ticks</h3>
 *
 * <p>An asynchronous mechanic needs a certain amount of WORLD to happen — so many ticks of a
 * controller, a queue, a settle. A budget in seconds does not ask for that: it asks for a certain
 * amount of the machine's attention, and a busy machine gives the same test less world for the same
 * money. That is how a green test turns red because something unrelated was running, and it is why
 * budgets here used to be multiplied by the build's fork count: a number about the machine, standing
 * in for a number about the game. How OFTEN this polls is wall-clock and deliberately unimportant.</p>
 *
 * <h3>Two clocks, and the caller says which</h3>
 *
 * <p>They are different numbers and the difference decides what a test measures:</p>
 * <ul>
 *   <li>{@link #server()} — the server's own tick counter. It advances once per server tick whatever
 *       the worlds are doing, so it always makes progress while the server is alive. The right clock
 *       for anything driven by the server tick loop: a controller, a queue, an entry state machine.</li>
 *   <li>{@link #world(int)} — one world's clock, which advances only while THAT world ticks. The right
 *       clock for anything whose subject lives in a particular world — where a ship has drifted to is
 *       measured in the ticks of the world the ship is in, not in the ticks of the server. Its own
 *       silence is a finding: a world that is not ticking (no players, no forced chunks, a slot world
 *       nobody drives) fails the wait saying so, rather than reporting a mechanic that never arrived.</li>
 * </ul>
 *
 * <p>There is no default. A wait that picks a clock for its caller is picking what the test measures.</p>
 */
public final class GameTicks {

    /** One game tick, nominal. The server may be slower; it is never faster. */
    private static final long TICK_MS = 50L;

    /**
     * How far past nominal a wait may run before it is called a STALL rather than a slow box.
     *
     * <p>This is a liveness net and NOT the budget: it exists so a server that has stopped ticking
     * surfaces as a red naming the step that hung, instead of spinning until the harness kills the
     * whole class with nothing to say. Set far above any honest run, and deliberately NOT scaled by
     * the fork count — a fork multiplier is the machine-shaped number this class exists to remove,
     * and 20x nominal already covers any load a box can be under and still be ticking.</p>
     */
    private static final int STALL_FACTOR = 20;

    /** Floor for the net: a very short advance still gets room for a slow round-trip. */
    private static final Duration MIN_STALL_NET = Duration.ofSeconds(3);

    private static final Pattern TICK_FIELD = Pattern.compile("\"tick\":(-?\\d+)");

    private GameTicks() { }

    // ------------------------------------------------------------------ clocks

    /** A clock the game keeps. Reading it is one round-trip and changes nothing. */
    public interface Clock {
        long read(TestClient client) throws Exception;

        /** How this clock names itself in a failure — a red must say WHICH clock did not move. */
        String describe();
    }

    /**
     * The SERVER's own tick counter: {@code /artest clock}.
     *
     * <p>Advances while the server is alive whatever the worlds are doing, so a wait on it always
     * makes progress. Use it for work driven by the server tick loop.</p>
     */
    public static Clock server() {
        return new Clock() {
            @Override
            public long read(TestClient client) throws Exception {
                return field(client, "artest clock", "the server");
            }

            @Override
            public String describe() {
                return "the server's tick counter";
            }
        };
    }

    /**
     * One WORLD's clock: {@code /artest server tick-count <dim>}.
     *
     * <p>Advances only while that world ticks. Use it when the subject lives in that world — a ship's
     * pose, a tile's countdown, anything you would measure in the world's own ticks. Its refusal to
     * move is a finding about the world, and the wait says so rather than blaming the mechanic.</p>
     */
    public static Clock world(final int dim) {
        return new Clock() {
            @Override
            public long read(TestClient client) throws Exception {
                return field(client, "artest server tick-count " + dim, "dim " + dim);
            }

            @Override
            public String describe() {
                return "dim " + dim + "'s clock";
            }
        };
    }

    private static long field(TestClient client, String command, String who) throws Exception {
        String reply = String.join("\n", client.execute(command));
        Matcher matcher = TICK_FIELD.matcher(reply);
        if (!matcher.find()) {
            throw new AssertionError(command + " did not report a clock for " + who
                    + " (is the dimension loaded?): " + reply);
        }
        return Long.parseLong(matcher.group(1));
    }

    /** That clock, right now. */
    public static long read(TestClient client, Clock clock) throws Exception {
        return clock.read(client);
    }

    // ------------------------------------------------------------------ waiting

    /**
     * Something a test is waiting for. It may talk to the server, so it may throw.
     *
     * <p>Deliberately not {@code java.util.function.BooleanSupplier}: that one cannot throw, so every
     * probe round-trip inside a condition would need its own try/catch that swallows the failure —
     * which turns a broken probe into a condition that is merely false, i.e. into a timeout with the
     * wrong story attached.</p>
     */
    public interface Condition {
        boolean holds() throws Exception;
    }

    /**
     * Work a headless test has to keep doing WHILE it waits — arrangement a player would be providing.
     *
     * <p>Its own parameter rather than folded into the condition because the two answer different
     * questions: a condition is asked and must not change anything, an action is performed and answers
     * nothing. A side effect inside a predicate is how "why does this only pass when the assertion
     * runs" gets written.</p>
     */
    public interface Action {
        void run() throws Exception;
    }

    /**
     * Wait for {@code condition}, giving it at most {@code tickBudget} ticks of {@code clock}.
     *
     * <p>Returns whether it held, so the CALL SITE keeps its own assertion and its own message — which
     * is usually the whole diagnosis (the last status blob, the cell it was aiming at). A helper that
     * threw would take that away and replace it with a sentence that knows less.</p>
     *
     * <p>Asked once before any waiting at all, so a condition that already holds costs nothing.</p>
     *
     * @throws AssertionError if the CLOCK stops moving — a finding about the instrument, not a verdict
     *         on the condition, and the budget was never spent.
     */
    public static boolean until(TestClient client, Clock clock, int tickBudget, Condition condition)
            throws Exception {
        return until(client, clock, tickBudget, condition, null);
    }

    /** As above, performing {@code eachPoll} after every unsatisfied check. */
    public static boolean until(TestClient client, Clock clock, int tickBudget, Condition condition,
                                Action eachPoll) throws Exception {
        if (tickBudget <= 0) {
            throw new IllegalArgumentException("the budget must be positive, got " + tickBudget);
        }
        long start = clock.read(client);
        long deadline = System.nanoTime() + stallNet(tickBudget).toNanos();
        while (true) {
            if (condition.holds()) {
                return true;
            }
            if (eachPoll != null) {
                eachPoll.run();
            }
            long elapsed = clock.read(client) - start;
            if (elapsed >= tickBudget) {
                return false;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError(clock.describe() + " stopped moving while waiting: "
                        + elapsed + " of " + tickBudget + " ticks elapsed. That is a stalled or"
                        + " unticking clock, NOT a verdict on the condition — the budget was never"
                        + " spent.");
            }
            Thread.sleep(TICK_MS);
        }
    }

    // ------------------------------------------------------------------ advancing

    /** Let {@code clock} run for this many ticks. */
    public static void advance(TestClient client, Clock clock, int ticks) throws Exception {
        advanceObserved(client, clock, ticks);
    }

    /**
     * As {@link #advance}, returning how far the clock actually went (never less than {@code ticks}).
     *
     * @throws AssertionError if the clock does not get there — which is the interesting case, and the
     *         one the old probe reported as success.
     */
    public static long advanceObserved(TestClient client, Clock clock, int ticks) throws Exception {
        return advanceObserved(client, clock, ticks, stallNet(ticks));
    }

    /** As above, with a caller-chosen liveness net. */
    public static long advanceObserved(TestClient client, Clock clock, int ticks, Duration net)
            throws Exception {
        if (ticks <= 0) {
            throw new IllegalArgumentException("ticks must be positive, got " + ticks);
        }
        long start = clock.read(client);
        long target = start + ticks;
        long deadlineNanos = System.nanoTime() + net.toNanos();

        long observed = start;
        while (observed < target) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError(clock.describe() + " advanced only " + (observed - start)
                        + " of the " + ticks + " ticks asked for, inside " + net.toMillis()
                        + " ms. Either it is not ticking (no players, no forced chunks, a slot world"
                        + " nobody drives) or the server is stalled — both are findings, and neither"
                        + " is a wait.");
            }
            // Sleep the time the remaining ticks would take at nominal rate, so a long wait costs one
            // or two round-trips rather than one per tick. Bounded so a slow server is noticed early
            // rather than at the deadline.
            long remaining = target - observed;
            Thread.sleep(Math.max(TICK_MS, Math.min(500L, remaining * TICK_MS)));
            observed = clock.read(client);
        }
        return observed - start;
    }

    /**
     * Take {@code samples} readings, letting {@code clock} run at least {@code ticksBetween} ticks
     * between them — the OBSERVATION half, where the caller's assertions live inside the sample.
     *
     * <p><b>This is the shape to sweep first, and the reason is asymmetry.</b> A wall-clock WAIT turns
     * green into red on a busy box: noisy, and it announces itself. A wall-clock WINDOW turns red into
     * green: readings a fixed number of milliseconds apart cover fewer of the subject's own ticks when
     * the machine is loaded, so a drift that needs forty ticks to appear is simply not looked at and
     * the test reports stability. Nothing announces that.</p>
     *
     * <p><b>Why COUNT x GAP and not window / step.</b> A window divided by a step makes the number of
     * readings depend on how fast this box's console answers: each sample is a round-trip, a
     * round-trip is worth a few ticks, and a 40-tick window with a 5-tick step delivered <b>two</b>
     * readings instead of eight when it was measured. Stated the other way round, both halves are the
     * caller's and neither is the instrument's: {@code samples} readings happen, and each is at least
     * {@code ticksBetween} ticks of world after the last. The window is then a RESULT — at least
     * {@code (samples - 1) x ticksBetween}, more on a slow box, which is the harmless direction.</p>
     *
     * <p>Samples immediately, so the first reading costs nothing.</p>
     */
    public static void observe(TestClient client, Clock clock, int samples, int ticksBetween,
                               Action sample) throws Exception {
        if (samples <= 0 || ticksBetween <= 0) {
            throw new IllegalArgumentException("samples and gap must be positive, got "
                    + samples + " / " + ticksBetween);
        }
        sample.run();
        for (int i = 1; i < samples; i++) {
            advance(client, clock, ticksBetween);
            sample.run();
        }
    }

    /** The liveness net for a budget of {@code ticks} — see {@link #STALL_FACTOR}. */
    static Duration stallNet(int ticks) {
        Duration nominal = Duration.ofMillis((long) ticks * TICK_MS * STALL_FACTOR);
        return nominal.compareTo(MIN_STALL_NET) < 0 ? MIN_STALL_NET : nominal;
    }

    // ------------------------------------------------------------------ the world-clock shorthand

    /** {@code world(dim)}'s clock, right now. The shorthand 22 call sites already use. */
    public static long count(TestClient client, int dim) throws Exception {
        return read(client, world(dim));
    }

    /** Let dim {@code dim}'s clock run for {@code ticks}, returning how far it went. */
    public static long await(TestClient client, int dim, int ticks) throws Exception {
        return advanceObserved(client, world(dim), ticks);
    }

    /** As {@link #await(TestClient, int, int)}, with a caller-chosen liveness net. */
    public static long await(TestClient client, int dim, int ticks, Duration net) throws Exception {
        return advanceObserved(client, world(dim), ticks, net);
    }
}
