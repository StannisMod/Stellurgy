package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

/**
 * One round, one fresh wall, and it marks it.
 *
 * <h3>Written as a reproduction, kept as a pin</h3>
 * <p>This class was written to fail. Another scenario had a round fly through a stone wall with its
 * budget untouched, and the trigger looked like "the FIRST round fired into a freshly prepared
 * arrangement". Three versions of this test were built to reproduce that, each closer to the sequence
 * it was seen in — a single lane; two lanes prepared and built; and a preceding scenario firing down a
 * third lane and left in the air. <b>All three passed.</b> So the characterisation was wrong, and it is
 * recorded here rather than quietly dropped.</p>
 *
 * <p>What it leaves behind is worth keeping anyway: the plain property that a round fired once into a
 * wall it flies down the middle of comes out having marked it. The verdict is the wall's own stage
 * write ({@code block_stage_set}) on the row the round flies down — a link on the thing that must
 * happen, rather than a depth read after a wait on the round.</p>
 */
public class FirstShotIntoAFreshWallTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    /** A lane of this class's own, so nothing else has fired down it. */
    private static final int X = 2400, Y = 70, Z = 1400;
    /**
     * A SECOND arrangement, prepared and built after the first and never fired into. It is part of the
     * reproduction rather than scenery: with only one lane prepared the round bores normally, and the
     * minimal version of this test went green.
     */
    private static final int Z2 = 1420;
    /** The preceding scenario's lane — fired down and abandoned, never measured. */
    private static final int Z3 = 1440;
    private static final int WALL_DEPTH = 10;
    /**
     * How long the preceding scenario's round is left in the air before the arrangement is cleared:
     * the 1.5 s the reproduction was built with, at twenty ticks a second. It is part of the
     * arrangement being reproduced, not a wait for anything.
     */
    private static final int PRECEDING_FLIGHT_TICKS = 30;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /**
     * red-witnessed: with {@code ShotSubstrate#step} at {@code boolean structureFirst = first.isStructure();}'s {@code structureFirst} forced false (a round
     * that never sees a wall), this fails with "a round fired once into a wall it flew down the middle
     * of marked nothing ... no `block_stage_set` on the wall's row was recorded within 600 ticks".
     * 2026-09-30.
     */
    @Test
    public void theFirstRoundFiredIntoAFreshWallDamagesIt() throws Exception {
        // A PRECEDING scenario, because two standalone versions of this test went green without one:
        // rounds fired down another lane and left to fly, exactly as the scenario before the failing
        // one does.
        ask("stellurgytest fill " + DIM + " " + (X - 8) + " " + (Y - 2) + " " + (Z3 - 3) + " "
                + (X + 40) + " " + (Y + 4) + " " + (Z3 + 3) + " minecraft:air").requireOk("clear the preceding lane");
        ask("stellurgytest fill " + DIM + " " + X + " " + Y + " " + Z3 + " " + (X + WALL_DEPTH - 1)
                + " " + Y + " " + Z3 + " minecraft:glass_pane").requireOk("build the preceding wall");
        ask("stellurgytest shot fire " + DIM + " " + (X - 3.0D) + " " + (Y + 0.5D) + " " + (Z3 + 0.5D)
                + " 0.45 0 0 3000 1200 KINETIC 0.25 1.0").requireOk("fire the preceding round");
        // EXPERIMENT: the preceding round's time in the air is part of the arrangement reproduced.
        GameTicks.advance(client(), GameTicks.server(), PRECEDING_FLIGHT_TICKS);

        ask("stellurgytest shot clear " + DIM).requireOk("clear the air");
        for (int cx = (X - 16) >> 4; cx <= (X + 32) >> 4; cx++) {
            ask("stellurgytest chunk forceload " + DIM + " " + cx + " " + (Z >> 4)).requireOk("hold a chunk");
        }
        ask("stellurgytest fill " + DIM + " " + (X - 8) + " " + (Y - 2) + " " + (Z - 3) + " " + (X + 40)
                + " " + (Y + 4) + " " + (Z + 3) + " minecraft:air").requireOk("clear the lane");
        ask("stellurgytest fill " + DIM + " " + (X - 8) + " " + (Y - 2) + " " + (Z2 - 3) + " " + (X + 40)
                + " " + (Y + 4) + " " + (Z2 + 3) + " minecraft:air").requireOk("clear the second lane");
        ask("stellurgytest fill " + DIM + " " + X + " " + Y + " " + Z + " " + (X + WALL_DEPTH - 1) + " " + Y
                + " " + Z + " minecraft:stone").requireOk("build the wall");
        ask("stellurgytest fill " + DIM + " " + X + " " + Y + " " + Z2 + " " + (X + WALL_DEPTH - 1) + " "
                + Y + " " + Z2 + " minecraft:stone").requireOk("build the second wall");

        // Priced off the wall itself: rich enough that failing to mark it cannot be a budget story.
        Reply priced = ask("stellurgytest damage stage " + DIM + " " + X + " " + Y + " " + Z)
                .requireOk("price the wall");
        int budget = priced.integer("stageCost") * Math.max(1, priced.integer("maxStage")) * 20;

        long fired = events.markInstrumented();
        Reply shot = ask("stellurgytest shot fire " + DIM + " " + (X - 3.0D) + " " + (Y + 0.5D) + " "
                + (Z + 0.5D) + " 0.45 0 0 " + budget + " 1200 KINETIC 0.25 1.0").requireOk("fire the round");

        try {
            events.awaitMatching(fired, "block_stage_set", reply -> {
                for (String record : Events.recordsWhere(reply, "dim", String.valueOf(DIM))) {
                    String[] at = String.valueOf(Events.text(record, "pos")).split(",");
                    if (Integer.parseInt(at[1]) == Y && Integer.parseInt(at[2]) == Z
                            && Integer.parseInt(at[0]) >= X && Integer.parseInt(at[0]) < X + WALL_DEPTH) {
                        return true;
                    }
                }
                return false;
            }, "on the wall's row", "a round fired once into a wall it flew down the middle of marked"
                    + " nothing. Its budget (" + budget + ") is no question of price: the wall was never"
                    + " seen. round=" + shot, Weapons.SUBJECT_TICKS);
        } catch (AssertionError unmarked) {
            // An unmarked wall has two causes that look alike from outside — the step never found the
            // wall, or it found it and the impact was refused — and they differ only in what the step
            // and the damage service decided. Those decisions are records; the red carries them.
            String decided = String.valueOf(Events.recordsWhere(events.since(fired, "shot_crossing_decided"),
                    "shot", String.valueOf(shot.longInteger("id"))));
            String answered = String.valueOf(Events.recordsWhereAll(events.since(fired, "shot_impact_answered"),
                    "y", String.valueOf(Y), "z", String.valueOf(Z)));
            throw new AssertionError(unmarked.getMessage() + " | this round's crossing decisions: " + decided
                    + " | the impacts answered on its row: " + answered, unmarked);
        }
    }
}
