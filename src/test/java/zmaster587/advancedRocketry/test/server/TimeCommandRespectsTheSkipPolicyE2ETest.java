package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.CellInfo;
import org.junit.Test;


import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.ArrangementFailure.requireArranged;

/**
 * {@code /time set} obeys the time-skip policy per world: it moves the clocks it is allowed to move,
 * leaves the rest alone, and says how many it left.
 *
 * <p>Vanilla's {@code /time} is not a per-world command — {@code CommandTime.setAllWorldTimes} walks
 * every loaded world and writes it — so a player on the overworld used to drag every planet's clock
 * with him. That is the arcade half of the day cycle, and past the atmosphere it contradicts what a
 * planet is: a body turning at its own rate.</p>
 *
 * <h2>Both sides of the flag, in one server</h2>
 * A test that only ever ran with the shipped default would be satisfied by a build that ignored the
 * flag and hard-coded the locked behaviour; one that only ran with it on, by the opposite. So the
 * flag is flipped at runtime — it is read at every use, not cached — and the SAME command is issued
 * either side of the flip. The pair is the assertion; neither half is one on its own.
 */
public class TimeCommandRespectsTheSkipPolicyE2ETest extends AbstractSharedServerTest {

    /** Distinctive and far from any natural value, so "it moved" cannot be a coincidence of timing. */
    private static final long LOCKED_PROBE_TIME = 7777L;
    private static final long ALLOWED_PROBE_TIME = 3333L;

    /** How far a clock may drift on its own between two probe round-trips. */
    private static final long DRIFT_ALLOWANCE = 400L;

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    /** A planet dimension the shipped universe actually has, or {@link Integer#MIN_VALUE}. */
    private int findAPlanet() throws Exception {
        CellInfo home = CellInfo.atSector(this::exec, 0, 0, 0, 0);
        if (home.dimCell == null) {
            return Integer.MIN_VALUE;
        }
        // Asked by the registry's OWN key rather than by splitting it into three numbers: the key's
        // shape is the registry's business, and a split that stops matching answers MIN_VALUE, which
        // this method's callers read as "the shipped universe has no such body".
        CellInfo cell = CellInfo.atKey(this::exec, home.dimCell, 0);
        // Any body with a real dimension behind it that is NOT the overworld. Each body is read as
        // its own object: the regex this replaces matched `dim` and `kind` in one expression and so
        // held only while the producer kept them adjacent and in that order.
        for (CellInfo.Body one : cell.systemBodies) {
            if (("PLANET".equals(one.kind) || "MOON".equals(one.kind)) && one.dim != 0) {
                return one.dim;
            }
        }
        return Integer.MIN_VALUE;
    }

    @Test
    public void timeSetSkipsTheWorldsWhoseSkipIsLockedAndMovesTheRest() throws Exception {
        int planet = findAPlanet();
        requireArranged("this test needs a non-overworld planet dimension in the shipped"
                + " universe; without one there is nothing for the policy to protect and the test"
                + " would pass on any build.", planet != Integer.MIN_VALUE);

        long overworldBefore = dimTime(0);
        try {
            // ---- LOCKED: the shipped default. The planet must not move; the overworld must. ----
            assertTrue(Reply.of(exec("artest config set allowTimeSkipOnPlanets false")).ok());
            assertTrue(Reply.of(exec("artest config set allowTimeSkipOnOverworld true")).ok());

            long planetBefore = dimTime(planet);
            exec("time set " + LOCKED_PROBE_TIME);

            long planetAfterLocked = dimTime(planet);
            long overworldAfterLocked = dimTime(0);

            // The CONTROL first: if the command did nothing at all, the clause below is vacuous.
            assertLandedOn("CONTROL: the overworld is unlocked, so the very same command must have"
                            + " moved it — otherwise 'the planet did not move' says nothing about"
                            + " the policy and everything about a command that failed",
                    LOCKED_PROBE_TIME, overworldAfterLocked);

            assertTrue("THE CONTRACT: a planet's time of day is not the overworld's to set. Its"
                            + " clock moved " + Math.abs(planetAfterLocked - planetBefore)
                            + " ticks across a /time set (allowed " + DRIFT_ALLOWANCE + " of"
                            + " ordinary elapsed time); before=" + planetBefore + " after="
                            + planetAfterLocked,
                    Math.abs(planetAfterLocked - planetBefore) <= DRIFT_ALLOWANCE);

            // ---- ALLOWED: opt the arcade mechanic back in. The same command must now reach it. ----
            assertTrue(Reply.of(exec("artest config set allowTimeSkipOnPlanets true")).ok());
            exec("time set " + ALLOWED_PROBE_TIME);

            assertLandedOn("with the flag on, the same command must reach the planet — this is the"
                            + " half that makes the half above a measurement rather than a build"
                            + " that ignores the flag",
                    ALLOWED_PROBE_TIME, dimTime(planet));
        } finally {
            // This server is shared with every other test in the fork.
            exec("artest config set allowTimeSkipOnPlanets false");
            exec("artest config set allowTimeSkipOnOverworld true");
            exec("time set " + (overworldBefore % 24000L));
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    /**
     * A clock landed on {@code target} — meaning it is there or a few ticks past it, never exactly
     * on it. The world keeps turning between the command and the probe that reads it, so the honest
     * assertion is a window of the same size the "did not move" clauses allow. Measured: the first
     * run of this test failed on `expected 7777 but was 7779`, which is two ticks of a running
     * server and not a policy anybody broke.
     */
    private static void assertLandedOn(String what, long target, long actual) {
        long since = Math.floorMod(actual - target, 24000L);
        assertTrue(what + " — expected the clock at " + target + " (+ up to " + DRIFT_ALLOWANCE
                + " ticks of elapsed time), got " + actual + " which is " + since + " past it",
                since <= DRIFT_ALLOWANCE);
    }

    /** The per-dimension day-cycle clock, straight off the probe that reads each world's own. */
    private long dimTime(int dim) throws Exception {
        String raw = exec("artest dim time " + dim);
        Reply mReply = Reply.of(raw);
        assertTrue("the dim-time probe reports no worldTime for dim " + dim + ": " + raw, mReply.has("worldTime"));
        return (long) mReply.number("worldTime");
    }
}
