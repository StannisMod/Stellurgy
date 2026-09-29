package zmaster587.advancedRocketry.test;

/**
 * The failure a test raises when it could not BUILD the state its contract is about.
 *
 * <p>It is a distinct TYPE and not a distinct message, and that is the whole point: a reader — human
 * or script — must be able to tell "the fixture never came up" from "the product is broken" without
 * reading English. The JUnit XML records the exception class on every failure, so this separation
 * survives into the gate's own output, where a prose prefix does not.</p>
 *
 * <h2>Why it lives here and not on the shared-harness base</h2>
 *
 * <p>{@code Scenario} — the journal, the declared phase, the verdict line — belongs to a class that
 * shares one client across its scenarios, and it raises exactly this type through
 * {@code scenario().requireArranged}. But most of this suite is NOT on that base and cannot be: a
 * class whose subject is a server RESTART, or that must write a config before the server boots, pays
 * its own harness by necessity. Those classes were left unable to type anything, so their
 * arrangement failures arrived as ordinary {@code AssertionError}s carrying an "ARRANGEMENT:" prefix
 * — a convention that only works while every reader remembers it.</p>
 *
 * <p>So the TYPE is tier-agnostic and lives at the test root; the JOURNAL stays with the base that
 * can afford one. A per-boot class gets the distinction that matters and none of the machinery it
 * cannot use.</p>
 *
 * <pre>
 * import static zmaster587.advancedRocketry.test.ArrangementFailure.requireArranged;
 * ...
 * requireArranged("the fixture must build before anything is measured on it: " + reply, ok);
 * </pre>
 */
public final class ArrangementFailure extends AssertionError {

    private static final long serialVersionUID = 1L;

    public ArrangementFailure(String message) {
        super(message);
    }

    /** Fails as an ARRANGEMENT problem — the contract was never reached. */
    public static void arrangementFailed(String message) {
        throw new ArrangementFailure(message);
    }

    /**
     * Fails as an ARRANGEMENT problem unless {@code condition} holds.
     *
     * <p>Same argument order as {@code assertTrue}, deliberately: the migration off the prefix
     * convention is then a change of the call's NAME and nothing else, which is a change that cannot
     * silently invert a condition.</p>
     */
    public static void requireArranged(String message, boolean condition) {
        if (!condition) {
            arrangementFailed(message);
        }
    }
}
