package dev.stannismod.stellurgy.test.server;

import org.junit.Test;


import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.CellInfo;
import dev.stannismod.stellurgy.test.FixtureSite;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;

/**
 * A recorded defect's contract, at the server tier: <b>a jump's aim moves with the SPACE clock and with
 * nothing else.</b>
 *
 * <p>The measured defect was a jump that arrived about 8&nbsp;000 blocks from the moon it was aimed
 * at. The standoff ring was innocent — it displaced the arrival by exactly its 1024 blocks. The aim
 * had been evaluated on a different clock from the arrival: it read
 * {@code proxy.getWorldTimeUniversal(0)}, whose client-side implementation ignores the dimension it
 * is asked about and answers with the world the player is standing in, and every dimension but the
 * overworld carries a clock that advances only while it ticks. The two had drifted 14&nbsp;912 ticks
 * apart, and a moon covers about half a block a tick.</p>
 *
 * <h2>Why this cannot be the defect's own repro, stated rather than hidden</h2>
 * The defect needs a physical CLIENT JVM hosting the server, because that is the only arrangement in
 * which the sided proxy hands server logic the client implementation. This harness has no such tier:
 * every test here runs against a real DEDICATED server, whose proxy honours the dimension argument
 * and is therefore already correct. So what is reproduced is the DRIVER rather than the condition —
 * the clock that accessor answers with standing apart from the space clock — by moving the
 * overworld's clock ({@code stellurgytest space set-world-clock}), which is what that accessor reads
 * here. The final word on the single-player symptom stays a playtest.
 *
 * <h2>The two legs, and why the first one is not optional</h2>
 * Leg&nbsp;B alone ("the aim did not move") is satisfied by an aim that never moves for any reason —
 * by a console that failed to resolve its body, by a probe that reports a stale field, by a target
 * that was null all along. Leg&nbsp;A moves the SPACE clock by the same magnitude and requires the
 * aim to move a long way, which is what makes leg&nbsp;B's zero a measurement instead of a silence.
 * Leg&nbsp;A fires on a broken build and a fixed one alike; only leg&nbsp;B discriminates.
 *
 * <h2>Why a moon</h2>
 * The fastest-turning body a shipped universe offers, so a clock split of a given size produces the
 * largest miss and the control leg is unambiguous. It used to be a NECESSITY rather than a choice:
 * the aim was read as an in-cell offset, which is static for a planet, so the defect was invisible
 * on one. That is no longer so — every body's cell rides it, so the reading is the aim's ABSOLUTE
 * position and a planet would serve — but a moon is still the sharpest instrument, and the test
 * refuses to run rather than quietly pass if it cannot find one.
 */
public class AimAndArrivalShareOneClockTest extends AbstractSharedServerTest {

    private static final int NAV_X = 7400, NAV_Y = FixtureSite.OPEN_AIR_Y, NAV_Z = 7400;

    /**
     * How far the clocks are driven apart. The playtest's own split was 14&nbsp;912 ticks; this is
     * two orders of magnitude past it, so the miss it produces cannot be mistaken for rounding.
     */
    private static final long SPLIT_TICKS = 1_000_000L;

    /**
     * How far the aim may move when only a NON-space clock moved. Zero is the contract; a few blocks
     * of slack absorbs the rounding in an orbit evaluated at two nearby ticks. Far below anything
     * the defect produces: a moon covers about half a block per tick, so the split above is worth
     * hundreds of thousands of blocks.
     */
    /**
     * How much of the CONTROL's movement a wrong clock is allowed to reproduce.
     *
     * <p>Stated as a fraction rather than as blocks, because blocks are not what this test is about:
     * the same angular leak on a bigger orbit is a bigger number without anything having changed
     * about the contract. The old bound was 8 blocks against a control required to exceed 1 000 —
     * 0.8 % — and that ratio is kept exactly. It broke when the Moon moved to its real distance,
     * fifty-one times further out, where 0.8 % of the arc is 44 blocks; a bound that a correct
     * universe invalidates was measuring the universe, not the clock.</p>
     */
    private static final double ALLOWED_DRIFT_FRACTION_OF_CONTROL = 0.008;

    /** A floor under the fraction, for the numeric noise of two long round-trips. */
    private static final double ALLOWED_DRIFT_FLOOR_BLOCKS = 8.0;

    /**
     * Pins CLOCK-1 (space-subsystem evaluations read the space clock and no other clock).
     * Pins CLOCK-2 (the aim and the arrival share one clock, so a non-space clock cannot move the aim).
     */
    @Test
    public void theAimMovesWithTheSpaceClockAndWithNoOtherClock() throws Exception {
        int moonDim = findAMoon();
        requireArranged("this test needs the fastest-turning body the shipped universe offers, so"
                + " that a clock split of this size is unmistakable. No moon was found.",
                moonDim != Integer.MIN_VALUE);

        assertTrue("the navigation console must place: ",
                Reply.of(exec("stellurgytest nav place 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z)).ok());
        String aimed = exec("stellurgytest nav target-body 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z + " " + moonDim);
        assertTrue("the console must accept the moon as its target body: " + aimed,
                Reply.of(aimed).ok());

        long clockBefore = jsonLong(exec("stellurgytest space frame 0 0 0"), "clock");
        // How far leg B moved the overworld's clock back, so the finally can move it forward again.
        long overworldShift = 0L;
        try {
            // ---- LEG A: the positive control. Move the SPACE clock; the aim must follow it. ----
            exec("stellurgytest nav refresh 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z);
            long[] aimAtStart = targetAbs(exec("stellurgytest nav status 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z));

            String moved = exec("stellurgytest space set-clock " + (clockBefore + SPLIT_TICKS));
            assertTrue("the space clock must move: " + moved, Reply.of(moved).ok());
            exec("stellurgytest nav refresh 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z);
            String afterSpaceMove = exec("stellurgytest nav status 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z);
            long[] aimAfterSpaceMove = targetAbs(afterSpaceMove);

            double controlMove = distance(aimAtStart, aimAfterSpaceMove);
            assertTrue("CONTROL: the aim must track the SPACE clock — without this, leg B's \"it did"
                            + " not move\" is a statement about an aim that never moves at all."
                            + " clock +" + SPLIT_TICKS + " ticks moved the aim " + controlMove
                            + " blocks; status=" + afterSpaceMove,
                    controlMove > 1_000.0);

            // ---- LEG B: the contract. Move a clock that is NOT the space clock. Nothing may follow. ----
            // The clock the defective aim read was proxy.getWorldTimeUniversal(0), which on this
            // tier's dedicated server IS the overworld's total time: putting the overworld SPLIT_TICKS
            // behind the space clock reproduces the driver without replacing anything.
            String lagged = exec("stellurgytest space set-world-clock behind-space " + SPLIT_TICKS);
            assertTrue("the overworld clock must move: " + lagged, Reply.of(lagged).ok());
            overworldShift = jsonLong(lagged, "overworld") - jsonLong(lagged, "before");

            // Measure the INPUT before asserting the outcome: a green bought by an arrangement that
            // silently failed to diverge is the failure mode this line exists to make impossible.
            long spaceClock = jsonLong(lagged, "spaceClock");
            long worldClock = jsonLong(lagged, "overworld");
            requireArranged("the two clocks must actually be " + SPLIT_TICKS + " ticks apart"
                            + " before anything is concluded from the aim. spaceClock=" + spaceClock
                            + " overworld=" + worldClock + " split=" + (spaceClock - worldClock),
                    spaceClock - worldClock == SPLIT_TICKS);

            exec("stellurgytest nav refresh 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z);
            String afterLag = exec("stellurgytest nav status 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z);
            long[] aimAfterLag = targetAbs(afterLag);

            double drift = distance(aimAfterSpaceMove, aimAfterLag);
            double allowed = Math.max(ALLOWED_DRIFT_FLOOR_BLOCKS,
                    controlMove * ALLOWED_DRIFT_FRACTION_OF_CONTROL);
            assertTrue("THE CONTRACT: an aim is evaluated on the SPACE clock, so a clock"
                            + " that is not the space clock may not move it. An overworld clock "
                            + SPLIT_TICKS + " ticks behind moved the aim " + drift + " blocks —"
                            + " that is " + (drift / controlMove * 100d) + "% of the " + controlMove
                            + " blocks the RIGHT clock moves it, and at most "
                            + (ALLOWED_DRIFT_FRACTION_OF_CONTROL * 100d) + "% is allowed. The control"
                            + " above proves the aim does move when the SPACE clock moves, so this is"
                            + " not a dead instrument. status=" + afterLag,
                    drift <= allowed);
        } finally {
            // This server is shared with every other test in the fork: a shifted world clock or an
            // aged space clock is exactly the state that fails somebody else three classes later.
            String restored = exec("stellurgytest space set-clock " + clockBefore);
            if (overworldShift != 0L) {
                exec("stellurgytest space set-world-clock "
                        + (jsonLong(restored, "overworld") - overworldShift));
            }
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    /** The dimension id of the first MOON in the overworld body's own cell, or {@link Integer#MIN_VALUE}. */
    private int findAMoon() throws Exception {
        CellInfo home = CellInfo.atSector(this::exec, 0, 0, 0, 0);
        if (home.dimCell == null) {
            return Integer.MIN_VALUE;
        }
        // Asked by the registry's OWN key rather than by splitting it into three numbers: the key's
        // shape is the registry's business, and a split that stops matching answers MIN_VALUE, which
        // this method's callers read as "the shipped universe has no such body".
        CellInfo cell = CellInfo.atKey(this::exec, home.dimCell, 0);
        // Each body as its own object. The regex this replaces matched `dim` and `kind` in one
        // expression, so it held only while the two stayed adjacent and in that order.
        for (CellInfo.Body one : cell.systemBodies) {
            if ("MOON".equals(one.kind)) {
                return one.dim;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Where the aim POINTS, absolutely, at the space clock.
     *
     * <p>Not {@code targetLocal}. That is the aim's offset inside its cell, and every body's cell
     * now rides the body, so the offset is zero at every tick for every target: differencing two of
     * them yields zero on a broken build and a working one alike. The aim still moves — its CELL
     * moves — and this is the field that says so.</p>
     */
    private static long[] targetAbs(String status) {
        int[] aim = Reply.of("stellurgytest nav status", status).blockPos("targetAbs");
        assertTrue("the console reports no resolved aim, so there is nothing to measure: " + status,
                aim != null);
        return new long[]{aim[0], aim[1], aim[2]};
    }

    private static double distance(long[] a, long[] b) {
        assertNotNull(a);
        assertNotNull(b);
        double dx = (double) a[0] - b[0];
        double dy = (double) a[1] - b[1];
        double dz = (double) a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The space clock advances on its own, ONCE per server tick, without anybody setting it.
     *
     * <p>Every leg that moves a clock and reads it back is satisfied by a counter that only holds what
     * it was last told; this one touches nothing and requires the number to have grown — the direct
     * witness for the defect the owned counter replaced, a subsystem reading an unresolvable world and
     * answering a frozen zero. The RATE is asserted against the overworld's own once-per-tick counter
     * over the same window: a second writer on the server-tick event would double it and leave every
     * "it moved" assertion green. That compares deltas, not values, so the clocks stay decoupled.</p>
     * Pins CLOCK-5 (the subsystem owns the counter: it advances once per tick, is independent of world clocks, and survives a reboot).
     */
    @Test
    public void theClockAdvancesWithoutBeingTold() throws Exception {
        // Ticks of the SERVER's own counter the clocks are watched across. The wait is on one counter
        // and the claims are about two others, so this is not circular.
        final int observedTicks = 60;
        String first = exec("stellurgytest space clock");
        // WINDOW: both clocks read on either side of the stretch; the rate check compares the two deltas
        // over the SAME stretch, so its length — overshoot included — cancels out.
        dev.stannismod.stellurgy.test.GameTicks.advance(client(),
                dev.stannismod.stellurgy.test.GameTicks.server(), observedTicks);
        String second = exec("stellurgytest space clock");

        long spaceMoved = jsonLong(second, "spaceClock") - jsonLong(first, "spaceClock");
        long worldMoved = jsonLong(second, "overworld") - jsonLong(first, "overworld");
        assertTrue("the space clock must advance by itself — a counter that only holds what it was last"
                        + " set to is not a clock. moved=" + spaceMoved + " (" + first + " -> " + second + ")",
                spaceMoved > 0L);
        requireArranged("the reference clock must have moved too, or the rate check below compares against"
                + " a stopped server. overworld moved=" + worldMoved, worldMoved > 0L);
        assertTrue("...and it must advance ONCE per server tick, not twice: against the overworld's own"
                        + " once-per-tick counter over the same window: space=" + spaceMoved + " world=" + worldMoved,
                Math.abs(spaceMoved - worldMoved) <= Math.max(4L, worldMoved / 4L));
    }

    private static long jsonLong(String json, String field) {
        assertTrue("probe response carries no numeric \"" + field + "\": " + json, Reply.of(json).has(field));
        return Reply.of(json).integer(field);
    }
}
