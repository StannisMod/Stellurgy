package zmaster587.advancedRocketry.test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * How many ships are LOADED in a dimension — read once, asserted immediately, never waited for.
 *
 * <h2>Why there is no wait here, and how that was decided</h2>
 *
 * <p>Seventeen server-tier scenarios used to carry a private {@code waitForLoadedShip}: poll until
 * the registry knows a ship, pump {@code vs load-ships}, poll until one is loaded, up to 200 ticks.
 * Hashing the seventeen bodies gave SIX different ones — six experiments under one name.</p>
 *
 * <p>Before collapsing them, the question "what does this wait actually wait for" was measured
 * rather than assumed, with a pair of plants: first the already-satisfied branch was made to throw,
 * then the branch that waits was. <b>Every scenario failed on the first plant, at its FIRST call.
 * None failed on the second — at one fork, and again at six.</b> Seventeen classes, twenty tests,
 * zero executions of the wait under six-way concurrent load. By the time any of them asks, the ship
 * is loaded. The old polls exited on their first iteration for the same reason, which is why nobody
 * noticed in the years they stood there.</p>
 *
 * <p><b>So the wait was not waiting for anything, and a wait that waits for nothing is a defect
 * wearing a helper's clothes.</b> A wait exists because something is not true synchronously after
 * the action; when it IS true, what remains is either a fossil of a fault since fixed or a habit
 * adopted without measuring. Either way it costs the same thing: it converts a state that should
 * fail LOUDLY into up to 200 ticks of absorbed ambiguity, and then returns a number that is zero for
 * several different reasons.</p>
 *
 * <p>What replaces it is stronger, not weaker. A read and an assertion say "a ship is loaded here"
 * as a postcondition, fail at once when it is not, and name what to look at next. If a genuinely
 * asynchronous path ever appears, this is where it will surface — as a red with a discriminating
 * message, rather than as a wait that silently makes it go away.</p>
 */
public final class ShipReadiness {

    private ShipReadiness() {
    }

    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");

    /**
     * How many ships are LOADED in {@code dim} right now — a plain read, no pump and no wait.
     *
     * <p>The form to use inside somebody else's poll condition, where a failed read is an answer
     * ("not yet") and must not throw.</p>
     */
    public static int loadedCount(Events.Probe probe, int dim) throws Exception {
        return countOf(probe.exec("artest vs ship-count " + dim));
    }

    /** How many ships the registry KNOWS in {@code dim}, loaded or not. Read for the same reason. */
    public static int registeredCount(Events.Probe probe, int dim) throws Exception {
        return countOf(probe.exec("artest vs ship-count-all " + dim));
    }

    /**
     * Assert that at least {@code want} ships are loaded in {@code dim}, and answer how many.
     *
     * <p>The failure separates the two states a bare count cannot: a craft the registry never heard
     * of, and one it knows but which is not loaded. Those send a reader to different subsystems, so
     * the message carries both counts and says which it is.</p>
     *
     * @param what a scenario-facing sentence for what this ship being loaded MEANS here
     */
    public static int requireLoaded(Events.Probe probe, int dim, int want, String what)
            throws Exception {
        int loaded = loadedCount(probe, dim);
        if (loaded >= want) {
            return loaded;
        }
        int registered = registeredCount(probe, dim);
        assertTrue(what + " — dimension " + dim + " holds " + loaded + " loaded ship(s), wanted "
                        + want + ". The registry knows " + registered + " there, so this is "
                        + (registered < want
                                ? "a craft that never REGISTERED: look at what was supposed to create"
                                        + " it, not at loading"
                                : "a craft that registered and did not LOAD: a headless server has no"
                                        + " player near it, so something was expected to ask —"
                                        + " `artest vs load-ships " + dim + "` is that ask")
                        + ". This is asserted rather than waited for: measured across this tier at"
                        + " one and at six forks, the ship is always already loaded by the time a"
                        + " scenario asks, so a delay here is a finding and not something to sit out",
                loaded >= want);
        return loaded;
    }

    /** One loaded ship in {@code dim} — the floor almost every scenario asks for. */
    public static int requireLoaded(Events.Probe probe, int dim, String what) throws Exception {
        return requireLoaded(probe, dim, 1, what);
    }

    /** The {@code count} of a probe reply, or {@link Integer#MIN_VALUE} when it carries none. */
    private static int countOf(String reply) {
        Matcher m = COUNT.matcher(String.valueOf(reply));
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }
}
