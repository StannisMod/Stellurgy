package zmaster587.advancedRocketry.test.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;


import zmaster587.advancedRocketry.test.client.ClientPoll;

/**
 * Contract of {@link ClientPoll} — the poll-until-predicate that replaces fixed
 * {@code waitTicks(N)} budgets in the VS client e2e suite. Pure unit: the "client" is a
 * counter, so the poll's mechanics (early-exit, ceiling, already-satisfied, never-satisfied)
 * are pinned without a client boot.
 */
public class ClientPollTest {

    /** A monotonically rising counter is the stand-in for a converging world value. */
    @Test
    public void reachesThePredicateAndReportsTheIterationItTook() throws Exception {
        int[] value = {0};
        // Each poll step raises the "world" value by 1; predicate = value >= 3, so it takes 3 steps
        // (probe reads 0 before the loop, then 1,2,3 across three steps).
        ClientPoll.Result<Integer> r = ClientPoll.until(
                t -> value[0]++,
                () -> value[0],
                v -> v >= 3,
                1, 50);
        assertTrue("must reach the predicate: " + r, r.satisfied);
        assertEquals("exits the iteration the predicate first holds", 3, r.iterations);
        assertTrue("must early-exit well under the ceiling: " + r, r.iterations < r.ceiling);
    }

    /** Predicate already true on the first read: zero steps, nothing waited. */
    @Test
    public void alreadySatisfiedDoesNotStep() throws Exception {
        int[] steps = {0};
        ClientPoll.Result<Integer> r = ClientPoll.until(
                t -> steps[0] += t,
                () -> 7,
                v -> v >= 3,
                5, 20);
        assertTrue("already-true predicate is satisfied", r.satisfied);
        assertEquals("no iterations run when already satisfied", 0, r.iterations);
        assertEquals("no client ticks advanced when already satisfied", 0, steps[0]);
    }

    /** Predicate that never holds: the poll gives up at the ceiling and reports not-satisfied. */
    @Test
    public void neverSatisfiedStopsAtTheCeiling() throws Exception {
        int[] steps = {0};
        ClientPoll.Result<Integer> r = ClientPoll.until(
                t -> steps[0]++,
                () -> 0,
                v -> v >= 1,
                1, 8);
        assertFalse("a never-true predicate is not satisfied", r.satisfied);
        assertEquals("stops exactly at the ceiling it was given", 8, r.ceiling);
        assertEquals("runs exactly ceiling iterations before giving up", 8, r.iterations);
    }

    @Test
    public void rejectsNonPositiveBudgets() throws Exception {
        try {
            ClientPoll.until(t -> { }, () -> 0, v -> true, 0, 10);
            fail("stepTicks <= 0 must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            ClientPoll.until(t -> { }, () -> 0, v -> true, 1, 0);
            fail("baseIterations <= 0 must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
