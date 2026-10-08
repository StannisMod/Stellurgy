package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

import static org.junit.Assert.assertTrue;

/**
 * A round that comes out the far side of one plate is asked by the next one, in the SAME tick.
 *
 * <p>Spaced armour is the arrangement the reactive family exists for: two thin charges with air
 * between them stop more than one charge, because the second is asked only once the first is gone.
 * That claim was false for anything faster than a slow round. When a body passed through, the
 * substrate moved it the WHOLE of the tick's remaining travel in one step — not the distance the walk
 * had actually covered — so a plate standing inside that remaining travel was stepped straight over
 * and never consulted.</p>
 *
 * <p>The two legs differ in ONE thing: how far the round can travel in a tick. Slow, the second plate
 * is inside the next tick's travel and gets its question either way; fast, it is inside THIS tick's,
 * which is the case the bug lived in. Without the slow leg a test could pass because nothing ever
 * reached the second plate at all. "Asked" is read off the plate's own answer at the contact seam
 * ({@code contact_answered}), and "gone" off the world.</p>
 */
public class SpacedArmourIsAskedTwiceTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 84;
    private static final int X = 11_200;
    /** Two lanes, so the two legs cannot inherit each other's spent charges. */
    private static final int SLOW_Z = 11_300, FAST_Z = 11_320;

    /** The gap: wide enough that the second plate is a separate meeting, not the same voxel. */
    private static final int SECOND_PLATE_OFFSET = 3;

    /**
     * Slow enough that one tick cannot span the gap; fast enough that another OVERSHOOTS it.
     *
     * <p>The fast number is not "large": it is chosen so that what is left of the tick after the
     * first plate lands the round well BEYOND the second one. A first attempt used 6, which — from
     * three blocks out, with the second plate three further on — left exactly enough travel to land
     * the round on the second plate's own voxel, where the next tick met it anyway. The test passed
     * with the fix removed.</p>
     */
    private static final double SLOW = 0.45D;
    private static final double FAST = 12.0D;

    /** How far in front of the first plate a round is admitted. Short, so the fast leg overshoots. */
    private static final double MUZZLE_STANDOFF = 1.0D;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /**
     * The claim, on the arrangement that makes it matter.
     *
     * <p>red-witnessed: with {@code ShotSubstrate.step} advancing a body that came out the far side by the
     * whole of the tick's remaining travel ({@code ShotSubstrate#step} at {@code double advanced = Math.min(reachInside,}), this fails at "the second
     * plate was never asked by the fast round" (2026-09-29).</p>
     */
    @Test
    public void aRoundThroughTheFirstPlateIsAskedByTheSecondHoweverFastItIsGoing() throws Exception {
        for (double speed : new double[]{SLOW, FAST}) {
            int lane = speed == SLOW ? SLOW_Z : FAST_Z;
            String leg = speed == SLOW ? "slow" : "fast";

            prepare(lane);
            place(X, lane, "stellurgy:reactivePlate");
            place(X + SECOND_PLATE_OFFSET, lane, "stellurgy:reactivePlate");

            long fired = events.markInstrumented();
            fire(lane, speed);
            // The round has budget to spare and flies on past both plates for its whole lifetime, so
            // its ending is nothing to wait for; the second plate's answer is the link.
            String answers = events.awaitMatching(fired, "contact_answered",
                    one -> !Events.recordsWhere(one, "pos", Weapons.at(X + SECOND_PLATE_OFFSET, Y, lane))
                            .isEmpty(),
                    "at the second plate", "the second plate was never asked by the " + leg + " round"
                            + " that came through the first one: the round was advanced by the whole of"
                            + " the tick's remaining travel instead of by the distance the walk covered,"
                            + " and stepped clean over it — so spaced armour is one plate with a"
                            + " decoration behind it", Weapons.SUBJECT_TICKS);
            Events.assertInstrumentRan(answers, "contact_events", "the plates the " + leg + " round met");
            assertTrue("the FIRST plate was never asked by the " + leg + " round: nothing arrived at"
                    + " all, so this leg says nothing about the second one: " + answers,
                    !Events.recordsWhere(answers, "pos", Weapons.at(X, Y, lane)).isEmpty());
            assertTrue("the second plate was asked and is still standing after the " + leg + " round:"
                    + " a reactive charge that answers spends itself",
                    gone(X + SECOND_PLATE_OFFSET, lane));
        }
    }

    // ---- driving

    /** Enough to spend both charges and still be moving: the subject is the QUESTION, not the budget. */
    private long fire(int lane, double speed) throws Exception {
        Reply fired = ask("stellurgytest shot fire " + DIM + " " + (X - MUZZLE_STANDOFF) + " " + (Y + 0.5D) + " "
                + (lane + 0.5D) + " " + speed + " 0 0 200000 1200 KINETIC 0.25 1.0").requireOk("fire down the lane");
        long id = fired.longInteger("id");
        assertTrue("the round was refused, so this leg measured nothing: " + fired, id >= 0);
        return id;
    }

    private void place(int x, int lane, String block) throws Exception {
        Reply placed = ask("stellurgytest place " + DIM + " " + x + " " + Y + " " + lane + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + lane + ": " + placed, placed.bool("placed"));
    }

    private void prepare(int lane) throws Exception {
        ask("stellurgytest chunk warmup " + DIM + " " + ((X - 16) >> 4) + " " + ((lane - 16) >> 4) + " "
                + ((X + 40) >> 4) + " " + ((lane + 16) >> 4)).requireOk("warm the lane's chunks");
        ask("stellurgytest fill " + DIM + " " + (X - 8) + " " + (Y - 2) + " " + (lane - 3) + " " + (X + 40)
                + " " + (Y + 4) + " " + (lane + 3) + " minecraft:air").requireOk("clear the lane");
    }

    // ---- reading

    /** A reactive charge that was asked spent itself, so the voxel it held is air. */
    private boolean gone(int x, int lane) throws Exception {
        return "minecraft:air".equals(ask("stellurgytest damage stage " + DIM + " " + x + " " + Y + " " + lane)
                .requireOk("read a stage").text("block"));
    }
}
