package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.space.AutoTakeoffPlanner;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The take-off corridor: the column a climb to orbit goes straight up through is clear exactly when no
 * block of it, from the craft's height to the top, is solid. The world's notion of solid is the caller's;
 * this pins that every block of the column is asked and that nothing outside it decides.
 */
public class AutoTakeoffPlannerTest {

    /** A constant: the column's footprint across, blocks; an input. */
    private static final double X = 0.5D;

    /** A constant: the column's footprint along, blocks; an input. */
    private static final double Z = 0.5D;

    /** A constant: where the climb starts, blocks; an input. */
    private static final double FROM_Y = 100.0D;

    /** A constant: where the corridor tops out, blocks; an input. */
    private static final double TO_Y = 300.0D;

    @Test
    @RedWitness(verdict = "nothing solid", member = "AutoTakeoffPlanner#corridorClear", at = "return true;",
            replacedBy = "return false;", fails = "a column with nothing solid in it is clear", on = "2026-10-23")
    @RedWitness(verdict = "outside", member = "AutoTakeoffPlanner#corridorClear",
            at = "long bx = (long) Math.floor(x);", replacedBy = "long bx = (long) Math.floor(x) + 1L;",
            fails = "solid ground everywhere OUTSIDE the column does not block it", on = "2026-10-23")
    @RedWitness(verdict = "below", member = "AutoTakeoffPlanner#corridorClear",
            at = "for (long y = (long) Math.floor(fromY);", replacedBy = "for (long y = (long) Math.floor(fromY) - 1L;",
            fails = "solid ground below the climb's start does not block it", on = "2026-10-23")
    public void clearCorridorEngages() {
        assertTrue("a column with nothing solid in it is clear",
                AutoTakeoffPlanner.corridorClear(X, Z, FROM_Y, TO_Y, p -> false));
        assertTrue("solid ground everywhere OUTSIDE the column does not block it",
                AutoTakeoffPlanner.corridorClear(X, Z, FROM_Y, TO_Y, p -> p[0] != 0L || p[2] != 0L));
        assertTrue("solid ground below the climb's start does not block it",
                AutoTakeoffPlanner.corridorClear(X, Z, FROM_Y, TO_Y, p -> p[1] < (long) FROM_Y));
    }

    @Test
    @RedWitness(member = "AutoTakeoffPlanner#corridorClear", at = "y++", replacedBy = "y += 2",
            fails = "a one-block slab at y=101 in the column blocks it", on = "2026-10-23")
    public void obstructedCorridorIsDeclined() {
        // One block thick, at every height of the column in turn: none may be stepped over.
        for (long y = (long) FROM_Y; y <= (long) TO_Y; y++) {
            final long slab = y;
            assertFalse("a one-block slab at y=" + slab + " in the column blocks it",
                    AutoTakeoffPlanner.corridorClear(X, Z, FROM_Y, TO_Y,
                            p -> p[0] == 0L && p[2] == 0L && p[1] == slab));
        }
    }
}
