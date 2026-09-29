package zmaster587.advancedRocketry.test.unit;

import net.minecraft.util.math.AxisAlignedBB;

import org.junit.Test;

import org.valkyrienskies.mod.common.util.ValkyrienUtils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>The boundary of "is this body at that hull?" — the question that decides whether a ship is
 * allowed to move a body at all.</b>
 *
 * <p>A ship carries a body by transforming it by the hull's rigid between-tick motion, and a rigid
 * motion's displacement at a point grows with that point's distance from the hull. So the licence to
 * apply it cannot be time alone: a body that stood on a deck and was then teleported still holds its
 * association, and the hull goes on writing its position from thousands of blocks away. The
 * predicate under test is what separates a body at a hull from a body somewhere else.</p>
 *
 * <p><b>Why the standing case is a boundary and not an easy interior point.</b> A ship's box is built
 * from integer block coordinates and grown only on its max side, so its top face sits on the block
 * grid; a body standing on the topmost deck block has its own minimum on that same grid line. The two
 * meet to the bit. Minecraft's own {@code AxisAlignedBB.intersects} is strict on every axis and
 * answers "no" to boxes that meet exactly — right for asking whether two things OVERLAP, wrong for
 * asking whether one is standing on the other. A predicate written the obvious way therefore drops
 * exactly the bodies the mechanism exists to carry, and drops them only on LEVEL hulls, since a
 * rotated hull's enclosing box misses the grid anyway.</p>
 *
 * <p>What is pinned is the CONTRACT — standing counts, being just off the deck counts, a far plot
 * does not — never the arithmetic that delivers it.</p>
 */
public class StandingOnAHullCountsAsBeingAtItTest {

    /** A hull as the substrate builds it: integer block coordinates, grown only on the max side. */
    private static AxisAlignedBB hull() {
        return new AxisAlignedBB(100, 68, 100, 100, 68, 100).expand(1, 1, 1);
    }

    /** A body of a player's size whose feet rest on {@code feetY}. */
    private static AxisAlignedBB bodyStandingAt(double x, double feetY, double z) {
        return new AxisAlignedBB(x - 0.3, feetY, z - 0.3, x + 0.3, feetY + 1.8, z + 0.3);
    }

    @Test
    public void aBodyStandingOnTheTopFaceIsAtTheHull() {
        AxisAlignedBB hull = hull();
        AxisAlignedBB stander = bodyStandingAt(100.5, hull.maxY, 100.5);

        assertTrue("a body standing on a hull's top face must count as being at that hull, or the"
                        + " ship stops carrying the very bodies it exists to carry",
                ValkyrienUtils.isWithinShipBounds(stander, hull));

        // The control that gives the assertion above its meaning: the plain strict test answers the
        // OPPOSITE for this posture, so this really is the boundary and not a restatement of "these
        // two boxes obviously overlap".
        assertFalse("sensitivity control — if the strict form also accepted a body standing exactly"
                        + " on the top face, this test would pass against the defect it exists to"
                        + " catch",
                stander.intersects(hull));
    }

    @Test
    public void aBodyInsideTheHullIsAtIt() {
        assertTrue("a body within the hull's own box is at it",
                ValkyrienUtils.isWithinShipBounds(bodyStandingAt(100.5, 68.2, 100.5), hull()));
    }

    @Test
    public void aBodyJustOffTheDeckIsStillAtTheHull() {
        AxisAlignedBB hull = hull();
        // Mid-step, mid-jump, or a tick where contact simply did not register: the association is
        // meant to bridge exactly this, and a predicate that drops it here re-creates the jitter the
        // stickiness was introduced to remove.
        assertTrue("a body a hair off the deck has not left the hull",
                ValkyrienUtils.isWithinShipBounds(bodyStandingAt(100.5, hull.maxY + 0.5, 100.5), hull));
    }

    @Test
    public void aBodyOnAFarPlotIsNotAtTheHull() {
        // The case the predicate was introduced for: same altitude, thousands of blocks away, which
        // is what a teleport leaves behind while the association is still live.
        assertFalse("a body thousands of blocks from a hull is not being carried by it, however"
                        + " recently it stood on it",
                ValkyrienUtils.isWithinShipBounds(bodyStandingAt(4600.5, 69, 100.5), hull()));
    }

    @Test
    public void aBodyOneStepToTheSideAtDeckLevelIsNotAtTheHull() {
        // The horizontal counterpart, so the verdict above cannot be satisfied by distance in ONE
        // axis: a body clear of the hull sideways is off it just as a body clear of it vertically is.
        AxisAlignedBB hull = hull();
        assertFalse("a body clear of the hull sideways is off it",
                ValkyrienUtils.isWithinShipBounds(bodyStandingAt(105.5, hull.maxY, 100.5), hull));
    }
}
