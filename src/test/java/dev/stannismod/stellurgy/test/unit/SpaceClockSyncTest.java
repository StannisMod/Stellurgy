package dev.stannismod.stellurgy.test.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.stannismod.stellurgy.space.SpaceClockSync;

/**
 * A client's copy of the space clock, as a contract rather than as an implementation.
 *
 * <p>What is pinned here is what a CONSUMER may rely on: that an un-synced copy is distinguishable
 * from one told "tick zero", that a baseline plus elapsed client ticks is the answer, and that a later
 * baseline WINS — including one that moves the answer backwards, because the server's counter is the
 * truth and a client that ran ahead of it is simply wrong. Nothing here pins the field layout or the
 * arithmetic's internal form. That a new connection starts from an un-synced copy is the view's
 * lifetime, not this class's, and the client disconnect e2e pins it.</p>
 */
public class SpaceClockSyncTest {

    private final SpaceClockSync clock = new SpaceClockSync();

    @Test
    public void aClientNobodyHasToldIsDistinguishableFromOneToldItIsTickZero() {
        assertFalse("a client that has never been synced must say so", clock.hasSync());
        assertEquals("and must answer 0 rather than a stale or random value",
                0L, clock.now());

        clock.accept(0L);

        assertTrue("being told the clock IS zero is a sync, not an absence of one",
                clock.hasSync());
        assertEquals(0L, clock.now());
    }

    @Test
    public void theAnswerIsTheBaselinePlusTheClientTicksSinceIt() {
        clock.accept(1_000L);
        assertEquals("immediately after a sync the answer is the value synced",
                1_000L, clock.now());

        for (int i = 0; i < 7; i++) {
            clock.onClientTick();
        }

        assertEquals("seven client ticks after a baseline of 1000 the clock reads 1007",
                1_007L, clock.now());
    }

    @Test
    public void ticksBeforeTheFirstSyncDoNotAccumulateIntoTheAnswer() {
        // The control for the test above: a client ticks for a long time before it is ever told the
        // clock. Those ticks belong to no baseline, so the first sync must answer exactly what it
        // was given - if they leaked in, this reads 1500 instead of 1000.
        for (int i = 0; i < 500; i++) {
            clock.onClientTick();
        }

        clock.accept(1_000L);

        assertEquals("the first baseline is the answer, whatever the client did before it",
                1_000L, clock.now());
    }

    @Test
    public void aLaterBaselineWinsEvenWhenItMovesTheAnswerBackwards() {
        clock.accept(1_000L);
        for (int i = 0; i < 100; i++) {
            clock.onClientTick();
        }
        assertEquals("the client has run 100 ticks ahead on its own", 1_100L, clock.now());

        // The server was lagging: 100 client ticks were only 60 server ticks.
        clock.accept(1_060L);

        assertEquals("a correction from the server is authoritative, downwards included",
                1_060L, clock.now());
    }
}
