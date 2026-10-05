package dev.stannismod.stellurgy.test.unit;

import org.junit.Test;

import dev.stannismod.stellurgy.atmosphere.AtmosphereHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Telling players what the air looks like is cheap, and it does not tell them all at once.
 *
 * <p>The routine readout is a broadcast: every player gets one, forever, for as long as the server
 * runs. Two things have to be true of a broadcast like that, and neither is visible in a green run of
 * anything else — a server that served every player on the same tick would behave correctly and
 * merely stutter, which is exactly the kind of defect that ships.</p>
 *
 * <p><b>What this test cannot see</b>: that the packet is actually sent, or that it arrives. It pins
 * the DECISION — how often, and whose clock decides — because that decision is what regresses. The
 * arrival is the client tier's business.</p>
 */
public class AtmosphereSyncIsCheapAndPhasedTest {

    /** Two seconds of ticks: long enough to count a rate rather than catch a moment. */
    private static final int WINDOW = 40;

    /**
     * <p>red-witnessed: with {@code AtmosphereHandler#isSyncTick} at {@code return ticksExisted % SYNC_PERIOD_TICKS == 0;} serving on half the period: "a readout
     * nobody decides anything with must not cost more than one packet a second per player
     * expected:&lt;2&gt; but was:&lt;4&gt;", 2026-09-30. This does not see the period itself: the
     * expectation is computed from {@code AtmosphereHandler#SYNC_PERIOD_TICKS} at {@code 20}, and with
     * {@code AtmosphereHandler#SYNC_PERIOD_TICKS} at {@code 20} set to 1 - a packet every tick - all three methods here stay
     * green.</p>
     */
    @Test
    public void aPlayerIsToldOnceASecondAndNotMoreOften() {
        int told = 0;
        for (int age = 0; age < WINDOW; age++) {
            if (AtmosphereHandler.isSyncTick(age)) {
                told++;
            }
        }
        assertEquals("a readout nobody decides anything with must not cost more than one packet a"
                        + " second per player",
                WINDOW / AtmosphereHandler.SYNC_PERIOD_TICKS, told);
    }

    /**
     * <p>red-witnessed: with {@code AtmosphereHandler#isSyncTick} at {@code return ticksExisted % SYNC_PERIOD_TICKS == 0;} serving on the first two ticks of every
     * period: "players whose ages differ by 1 ticks must never be served on the same tick ...
     * expected:&lt;0&gt; but was:&lt;2&gt;", 2026-09-30.</p>
     */
    @Test
    public void twoPlayersWhoJoinedAtDifferentMomentsAreNotToldOnTheSameTick() {
        // Ages differing by anything that is not a whole period. A shared clock — world time, say —
        // would make this fail for every offset at once, which is the regression being guarded: the
        // period may be shared, the phase may not.
        for (int offset = 1; offset < AtmosphereHandler.SYNC_PERIOD_TICKS; offset++) {
            int collisions = 0;
            for (int tick = 0; tick < WINDOW; tick++) {
                boolean first = AtmosphereHandler.isSyncTick(tick);
                boolean second = AtmosphereHandler.isSyncTick(tick + offset);
                if (first && second) {
                    collisions++;
                }
            }
            assertEquals("players whose ages differ by " + offset + " ticks must never be served on"
                            + " the same tick — the phase is the player's own, not the world's",
                    0, collisions);
        }
    }

    /**
     * <p>red-witnessed: with {@code AtmosphereHandler#isSyncTick} at {@code return ticksExisted % SYNC_PERIOD_TICKS == 0;} never answering yes: "a player joining at
     * offset 0 must still hear about the air", 2026-09-30.</p>
     */
    @Test
    public void everyPlayerIsToldEventually() {
        // The mirror of the two above: phasing that spread players out by NEVER telling some of them
        // would satisfy both, and would be worse than the stutter it avoided.
        for (int offset = 0; offset < AtmosphereHandler.SYNC_PERIOD_TICKS; offset++) {
            boolean everTold = false;
            for (int tick = 0; tick < WINDOW; tick++) {
                everTold |= AtmosphereHandler.isSyncTick(tick + offset);
            }
            assertTrue("a player joining at offset " + offset + " must still hear about the air",
                    everTold);
        }
    }
}
