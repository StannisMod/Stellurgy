package zmaster587.advancedRocketry.test.server;

import org.junit.Test;
import zmaster587.advancedRocketry.test.GameTicks;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Does {@code artest server wait} measure the WORLD, or does it measure itself?
 *
 * <p>Measured 2026-08-17 on a space slot world: {@code wait <slotDim> 60} reported
 * {@code elapsedTicks=0} after 12 s of wall clock, and a test built on that reading spent two
 * revisions hunting a crossing bug that did not exist. Two causes fit equally: the slot world really
 * does not tick (headless, no player, no ticking chunks), or the probe polls
 * {@code getTotalWorldTime()} from the command — i.e. on the server thread, the one thread that
 * advances it — and so blocks its own subject.</p>
 *
 * <p>This is that discriminator, asked of a world nobody doubts. <b>Green</b> = the probe reports real
 * elapsed ticks on a ticking world, so a zero elsewhere is a fact about that world. <b>Red</b> = the
 * probe cannot observe ticks at all and every reading it has ever produced is its own reflection.</p>
 *
 * <p>It stays in the suite rather than being deleted with its answer: what it pins is a HARNESS
 * contract that other tests read as ground truth, and the day it starts failing is the day those
 * tests begin measuring nothing.</p>
 */
public class ServerWaitProbeReportsRealTicksTest extends AbstractSharedServerTest {

    /** Small enough to stay fast, large enough that a scheduler hiccup cannot fake it. */
    private static final int TICKS = 20;

    /**
     * ANSWERED 2026-08-17: it measures itself. On the OVERWORLD — a world that ticks by definition —
     * the probe reported zero elapsed ticks, so the handler runs on the very thread that advances the
     * clock and can never see it move. Every "wait N ticks" in the suite has been a sleep.
     *
     * <p>What this test pins now is therefore not "the clock advances" (it cannot, until the probe is
     * rebuilt) but the property that keeps the next reader out of the same hole: <b>the probe must SAY
     * that it did not advance</b>. A reply claiming success with no such field is what cost this
     * session two wrong diagnoses.</p>
     */
    @Test
    public void theWaitProbeNeverClaimsTicksItDidNotObserve() throws Exception {
        String reply = exec("artest server wait 0 " + TICKS);
        assertTrue("the wait probe failed on the overworld: " + reply, reply.contains("\"requested\""));

        int elapsed = extractInt(reply, "elapsedTicks");
        boolean claimsAdvanced = reply.contains("\"advanced\":true");
        if (elapsed >= TICKS) {
            assertTrue("the clock DID advance, so the probe must say so — a real wait that reports "
                    + "itself as a non-wait is the same defect mirrored: " + reply, claimsAdvanced);
            return;
        }
        assertTrue("the probe returned fewer ticks than asked and must not report that as a wait: "
                + reply, reply.contains("\"advanced\":false"));
        assertTrue("and it must name what to do instead, or the next caller repeats the mistake: "
                + reply, reply.contains("\"hint\""));
    }

    /**
     * The other half of the same contract: a test that asks for N ticks must be able to SEE the
     * world's own clock move by N. The probe above cannot deliver that from the server thread, so the
     * waiting lives in the test jvm ({@link GameTicks}) and this is its acceptance — asked, again,
     * of a world whose answer is not in doubt.
     *
     * <p>Note what is asserted and what is not: the clock advanced by at least what was asked. Not
     * how long it took, not that it stopped there. A wall-clock pin here would be a test of this
     * machine's load, which is the very confusion the task exists to end.</p>
     */
    @Test
    public void aTestSideWaitAdvancesTheWorldsOwnClock() throws Exception {
        // The premise, measured rather than asserted in a comment: the handler answering this runs on
        // the thread that advances the clock. That is WHY the wait cannot live in a probe, and it was
        // once written down here the other way round and believed for months.
        String clock = exec("artest server tick-count 0");
        assertTrue("a probe handler must report that it runs on the server thread — if this ever "
                + "flips, a probe-side wait becomes possible and GameTicks can be retired: " + clock,
                clock.contains("\"onServerThread\":true"));

        long before = GameTicks.count(client(), 0);
        long observed = GameTicks.await(client(), 0, TICKS);
        long after = GameTicks.count(client(), 0);

        assertTrue("the wait reported " + observed + " ticks but was asked for " + TICKS
                + " — a wait may never return short", observed >= TICKS);
        assertTrue("the overworld clock must have moved by at least " + TICKS + " ticks across the "
                + "wait, but went " + before + " -> " + after, after - before >= TICKS);
    }

    /**
     * The SERVER's clock is a different number from a WORLD's, and both must move.
     *
     * <p>They are separate helpers on purpose — the caller says which one a budget is in — so the
     * property that makes them worth separating is that neither is a stand-in for the other: the
     * server counter advances while the server lives, a world's only while that world ticks. What is
     * pinned here is only that both are readable and both advance on a world nobody doubts; where
     * they DIVERGE is a property of an unticking world and cannot be arranged on the overworld.</p>
     */
    @Test
    public void bothClocksAreReadableAndBothAdvance() throws Exception {
        long serverBefore = GameTicks.read(client(), GameTicks.server());
        long worldBefore = GameTicks.read(client(), GameTicks.world(0));

        GameTicks.advance(client(), GameTicks.server(), TICKS);

        assertTrue("the server's own tick counter must advance: " + serverBefore + " -> "
                + GameTicks.read(client(), GameTicks.server()),
                GameTicks.read(client(), GameTicks.server()) - serverBefore >= TICKS);
        assertTrue("and the overworld's clock must have moved with it: " + worldBefore + " -> "
                + GameTicks.read(client(), GameTicks.world(0)),
                GameTicks.read(client(), GameTicks.world(0)) - worldBefore >= TICKS);
    }

    /**
     * A condition that already holds must cost nothing — no ticks, no waiting.
     *
     * <p>The whole reason a tick-budgeted wait is FASTER than the sleep it replaces: it stops the
     * moment the condition holds instead of spending its budget. A version that always spent its
     * ticks would satisfy every assertion the other two make and quietly reintroduce the cost this
     * task exists to remove.</p>
     */
    @Test
    public void aConditionThatAlreadyHoldsSpendsNoWorldAtAll() throws Exception {
        long before = GameTicks.read(client(), GameTicks.server());
        assertTrue("a condition that already holds must be answered true",
                GameTicks.until(client(), GameTicks.server(), 400, () -> true));
        long after = GameTicks.read(client(), GameTicks.server());

        assertTrue("and it must not have burned a 400-tick budget to say so: " + before + " -> "
                + after, after - before < 40);
    }

    /** A condition that comes true partway through is answered when it does, not at the budget. */
    @Test
    public void aConditionIsAnsweredWhenItComesTrue() throws Exception {
        final long start = GameTicks.read(client(), GameTicks.world(0));
        long before = GameTicks.read(client(), GameTicks.server());
        assertTrue("the wait must report the condition as held",
                GameTicks.until(client(), GameTicks.world(0), 400,
                        () -> GameTicks.read(client(), GameTicks.world(0)) - start >= TICKS));
        long spent = GameTicks.read(client(), GameTicks.server()) - before;

        assertTrue("it must not have run to its 400-tick budget once the condition held: " + spent,
                spent < 400);
    }

    /**
     * <b>The budget is spent in WORLD, not in wall clock.</b>
     *
     * <p>This is the property the whole migration turns on, and the one an assertion about "it
     * returned false" would not catch: a wait whose ceiling is seconds gives up early on a busy box,
     * having granted the mechanic fewer ticks than it granted an idle one. So the discriminator is
     * not that a never-true condition fails — it is that the clock ADVANCED past the budget before it
     * did.</p>
     */
    @Test
    public void aConditionThatNeverHoldsFailsAgainstTicksItWasActuallyGiven() throws Exception {
        final int budget = 40;
        long before = GameTicks.read(client(), GameTicks.server());
        assertFalse("a condition that never holds must not be reported as held",
                GameTicks.until(client(), GameTicks.server(), budget, () -> false));
        long after = GameTicks.read(client(), GameTicks.server());

        assertTrue("the wait must have let the game run its whole budget before giving up: " + before
                + " -> " + after + " against " + budget + " ticks", after - before >= budget);
    }

    /**
     * An observation takes the readings it was asked for, spaced in TICKS.
     *
     * <p>Both halves matter and only together: readings that all happen at once see the start and the
     * end of a drift and nothing between, and readings spaced in milliseconds are the wall-clock bug
     * wearing game units.</p>
     *
     * <p>MEASURED, and it is why the signature is count x gap rather than window / step: asked for a
     * 40-tick window with a 5-tick step, the first version delivered <b>2</b> readings, because every
     * sample is a console round-trip and a round-trip is worth a few ticks. The number of readings a
     * test gets may not depend on how fast this box answers.</p>
     */
    @Test
    public void anObservationTakesItsReadingsSpacedInTicks() throws Exception {
        final int wanted = 8;
        final int gap = 5;
        final int[] samples = {0};
        long before = GameTicks.read(client(), GameTicks.world(0));

        GameTicks.observe(client(), GameTicks.world(0), wanted, gap, () -> samples[0]++);

        long spanned = GameTicks.read(client(), GameTicks.world(0)) - before;
        assertEquals("every reading asked for must happen, whatever the box's latency",
                wanted, samples[0]);
        assertTrue("and they must be spaced in world: " + spanned + " ticks across " + wanted
                + " readings " + gap + " apart", spanned >= (wanted - 1) * gap);
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }
}
