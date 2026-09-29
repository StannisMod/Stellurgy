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
