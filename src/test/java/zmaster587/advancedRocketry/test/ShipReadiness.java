package zmaster587.advancedRocketry.test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Getting a dimension to the state where a ship is actually LOADED, and saying so from the event
 * log rather than from a count.
 *
 * <p>This is arrangement — what a scenario does BEFORE the thing it means to test. It is never the
 * subject of an assertion, which is exactly why it was allowed to rot: seventeen server-tier classes
 * carried a private {@code waitForLoadedShip}, and hashing their bodies gave SIX different ones. Six
 * experiments under one name, and nothing in any single file could show it. The budget, the floor,
 * the order of the two steps and what the failure prints are not properties of any one scenario.</p>
 *
 * <h2>Registration and loading are two events, and the pump only bridges them</h2>
 *
 * <p>A headless server has no player standing near a ship, so nothing auto-loads one;
 * {@code artest vs load-ships <dim>} is what asks. But it forces the ships the world already KNOWS,
 * so pumping before the physics mod has registered the craft does nothing whatsoever. Hence the
 * order here — wait for the registry to carry it, pump once, then wait for the load to commit.</p>
 *
 * <h2>Why the commit is read from the log and not from the count</h2>
 *
 * <p>A count answers "how many are loaded right now". It cannot tell <em>it never loaded</em> from
 * <em>it loaded and was collected again before this read</em>, and those two send a reader to
 * different subsystems. The record log keeps the history, so the failure prints the chain that DID
 * happen instead of a number that is zero for four different reasons.</p>
 *
 * <h2>MEASURED 2026-09-07: no caller currently reaches the waiting half</h2>
 *
 * <p>Every one of the seventeen scenarios that used to carry a private copy of this was run with the
 * fast path planted to fail, and then with the event path planted to fail. The first plant failed all
 * of them on their FIRST call; the second failed none of them. Seventeen classes, twenty tests, zero
 * executions of the wait — <b>by the time any of them asks, the ship is already loaded</b>, so what
 * they carried was never a wait at all. The old copies short-circuited on their first poll iteration
 * for the same reason, which is why nobody noticed.</p>
 *
 * <p>The waiting half is kept deliberately, and this note is what stops it being taken for tested
 * ground: a caller that DOES need it will be the first, and until then it is unexercised code with a
 * green suite standing beside it. A green run of those scenarios is evidence about the fast path and
 * about nothing else.</p>
 */
public final class ShipReadiness {

    private ShipReadiness() {
    }

    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");

    /**
     * Which way {@link #awaitLoaded} reached the state it returns, so a caller's failure message can
     * say it. A ship that was ALREADY loaded is a state, not a degraded answer: nothing had to
     * happen, so no record could be waited for, and that is worth telling apart from a load this
     * call actually drove.
     */
    public enum How {
        /** The floor already held on entry; nothing was pumped and nothing was awaited. */
        ALREADY_LOADED,
        /** The registry already carried the craft; this call pumped and awaited the load. */
        PUMPED,
        /** This call waited for registration first, then pumped and awaited the load. */
        REGISTERED_THEN_PUMPED,
    }

    /** How many ships are loaded, and how that came to be true. */
    public static final class Loaded {
        public final int count;
        public final How how;

        Loaded(int count, How how) {
            this.count = count;
            this.how = how;
        }

        @Override
        public String toString() {
            return count + " loaded (" + how + ")";
        }
    }

    /**
     * Get {@code dim} to at least {@code want} loaded ships, and answer how many there are.
     *
     * @param probe  how to run one probe command
     * @param events this tier's reader of the event log, already built against the same probe
     * @param dim    the dimension to look in
     * @param want   the floor the loaded count must reach
     * @param budget tick budget for each of the two waits
     * @throws AssertionError naming the whole recorded chain when either step never commits
     */
    public static Loaded awaitLoaded(Events.Probe probe, Events events, int dim, int want, int budget)
            throws Exception {
        int already = countOf(probe.exec("artest vs ship-count " + dim));
        if (already >= want) {
            return new Loaded(already, How.ALREADY_LOADED);
        }

        long mark = events.mark();
        How how = How.PUMPED;
        if (countOf(probe.exec("artest vs ship-count-all " + dim)) < want) {
            how = How.REGISTERED_THEN_PUMPED;
            events.await(mark, "ship_spawned",
                    "the craft must reach the physics mod's registry in dimension " + dim
                            + " before anything can load it — `vs load-ships` forces the ships the"
                            + " world already knows, so pumping ahead of registration is a no-op",
                    budget);
        }

        probe.exec("artest vs load-ships " + dim);
        events.await(mark, "ship_loaded",
                "the registered craft must actually load in dimension " + dim
                        + "; a headless server has no player near it to do so on its own", budget);

        return new Loaded(countOf(probe.exec("artest vs ship-count " + dim)), how);
    }

    /** One loaded ship in {@code dim} — the floor every caller of the old private copies used. */
    public static Loaded awaitLoaded(Events.Probe probe, Events events, int dim, int budget)
            throws Exception {
        return awaitLoaded(probe, events, dim, 1, budget);
    }

    /** The {@code count} of a probe reply, or {@link Integer#MIN_VALUE} when it carries none. */
    private static int countOf(String reply) {
        Matcher m = COUNT.matcher(String.valueOf(reply));
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }
}
