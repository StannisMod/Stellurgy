package dev.stannismod.stellurgy.test.unit;

import org.junit.Test;
import dev.stannismod.stellurgy.space.DescentShell;
import dev.stannismod.stellurgy.space.ShipEntryController;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The range a pilot is shown counts down to the surface he crosses.
 *
 * <p>The contract is not "subtract a constant" — it is that <b>the number reaches zero exactly when
 * the crossing happens</b>. That is why the legs below do not check arithmetic against itself: they
 * check the readout against {@link dev.stannismod.stellurgy.space.DescentController} the mechanic,
 * so a change to either that leaves them disagreeing is red. A test that only asserted
 * {@code max(0, d - R)} would pass just as happily on a build where the trigger fired at some other
 * radius, and the defect this exists for is precisely a readout describing a different surface from
 * the one the game acts on.</p>
 */
public class DescentShellTest {

    /** The shell as production sizes it today; read, never typed, so a retune moves the test with it. */
    private static final long R = ShipEntryController.DESCENT_RADIUS_BLOCKS;

    private static boolean triggers(double distanceToCentre) {
        return dev.stannismod.stellurgy.space.DescentController
                .shouldTriggerDescent(true, distanceToCentre, R);
    }

    @Test
    public void theRangeReachesZeroExactlyWhereTheDescentFires() {
        // Just outside: something left to fly, and the game has not taken the ship.
        double justOutside = R + 1d;
        assertTrue("a ship outside the shell must still have range to cover",
                DescentShell.distanceToShell(justOutside, R) > 0d);
        assertFalse("...and must not have been taken by the descent yet", triggers(justOutside));

        // On it: the readout is spent at the same instant the mechanic fires. This pair is the
        // contract; either one alone is satisfiable by a build that is wrong.
        assertEquals("the range must be spent AT the shell", 0d,
                DescentShell.distanceToShell(R, R), 0d);
        assertTrue("...and the descent must fire there", triggers(R));
    }

    @Test
    public void insideTheShellTheRangeIsZeroRatherThanNegative() {
        assertEquals("a ship already inside has nothing left to cover, not a negative amount", 0d,
                DescentShell.distanceToShell(R / 2d, R), 0d);
        assertEquals("and that holds at the body's own address", 0d,
                DescentShell.distanceToShell(0d, R), 0d);
    }

    @Test
    public void aBodyWithNoShellIsLabelledWithItsPlainDistance() {
        // A star is not something to descend to, so it carries a zero radius and the SAME arithmetic
        // must leave its distance untouched. This is what lets the renderer stay free of a
        // "which kinds have a shell" branch it would have to keep in step with the server.
        assertEquals("a shell-less body's range is its distance", 4_321d,
                DescentShell.distanceToShell(4_321d, 0L), 0d);
    }

    @Test
    public void theBoundaryOpensAsTheShipCloses() {
        // The property that makes the drawn boundary a PLACE rather than a decoration: it grows
        // monotonically on approach. The band this replaced was constant at every distance, which
        // is the thing being ruled out here.
        double far = DescentShell.boundaryHalfAngle(100d * R, R);
        double near = DescentShell.boundaryHalfAngle(2d * R, R);
        assertTrue("a distant shell must subtend less than a near one (" + far + " vs " + near + ")",
                far < near);
        assertTrue("and a distant one must still be visible at all", far > 0d);
    }

    @Test
    public void atTheCrossingTheBoundarySurroundsTheViewer() {
        assertEquals("on the shell the boundary is a great circle - you are on it",
                Math.PI / 2d, DescentShell.boundaryHalfAngle(R, R), 1.0E-9d);
        // Inside, asin's argument would exceed 1. The clamp must yield the same right angle rather
        // than NaN: a NaN here propagates into every vertex and the boundary silently vanishes at
        // exactly the moment it matters most.
        double inside = DescentShell.boundaryHalfAngle(R / 4d, R);
        assertFalse("inside the shell the angle must not be NaN", Double.isNaN(inside));
        assertEquals("...and stays the great circle", Math.PI / 2d, inside, 1.0E-9d);
    }

    @Test
    public void aBodyWithNoShellSubtendsNoBoundary() {
        assertEquals("nothing is drawn around a body that cannot be descended to", 0d,
                DescentShell.boundaryHalfAngle(5_000d, 0L), 0d);
    }

    @Test
    public void theRangeIsShorterThanTheDistanceByTheWholeShell() {
        // The defect in one line: a body-centre readout overstates the approach by a full shell.
        double d = 10_000d;
        assertEquals("the readout must differ from the centre distance by exactly the shell",
                R, d - DescentShell.distanceToShell(d, R), 0d);
    }

    // ── the shell is a property of the BODY ───────────────────────────────────

    /** A body of {@code radiusEarths}, standing at the origin, with nothing else stated. */
    private static dev.stannismod.stellurgy.universe.SystemBody sized(double radiusEarths) {
        return dev.stannismod.stellurgy.universe.SystemBody.fixedAt(
                        dev.stannismod.stellurgy.space.GalacticCoord.ORIGIN,
                        dev.stannismod.stellurgy.universe.SystemBodyKind.PLANET,
                        dev.stannismod.stellurgy.api.Constants.INVALID_PLANET, 0)
                .withRadius(radiusEarths);
    }

    @Test
    public void aShellStandsOutsideTheWorldItBounds() {
        // The defect this closes: radiusAround ignored its argument and returned a flat 512 blocks,
        // chosen when a body had no size. Against the radii that now exist that is 1/50 of an Earth
        // and 1/548 of a Jupiter — the surface a descent fires at lay deep inside the world it belongs
        // to. Every size the generator can produce is checked, not one convenient case.
        double[] radii = {0.1d, 0.5d, 1d, 2.5d, 11d, 30d};
        for (double r : radii) {
            dev.stannismod.stellurgy.universe.SystemBody body = sized(r);
            long shell = dev.stannismod.stellurgy.space.DescentShell.radiusAround(body);
            double surface = r * dev.stannismod.stellurgy.util.AstronomicalBodyHelper.EARTH_RADIUS_BLOCKS;

            assertTrue("a descent shell must stand OUTSIDE the body it bounds: " + shell
                            + " against a surface at " + Math.round(surface) + " (r=" + r + ")",
                    shell > surface);
            assertTrue("and an entering ship must spawn outside that shell, or it arrives already "
                            + "inside the trigger it is flying towards (r=" + r + ")",
                    dev.stannismod.stellurgy.space.ShipEntryController.entryRingAround(body) > shell);
        }
    }

    /**
     * A pilot leaving a world and one coming back cross the SAME surface: the takeoff line is the
     * descent shell's depth above the body, read in the world's own metric.
     *
     * <p>This fails if production breaks the contract that <b>takeoff fires where descent does</b> —
     * {@link DescentShell#orbitLineWorldY} and {@link DescentShell#radiusAround} describing two
     * different atmospheres. The two lengths are in two metrics (a chart block is 250 m, a world block
     * one), so the comparison converts the shell's depth and allows the half chart block the shell is
     * rounded to; Earth's line is checked against the Kármán line it is defined from.</p>
     *
     * <p>red-witnessed: 2026-10-05, with {@code DescentShell#orbitLineWorldY} at {@code double depthWorldBlocks = depthChartBlocks * AstronomicalBodyHelper.METRES_PER_CHART_BLOCK;}
     * left in chart blocks (the multiplication removed), this fails with "the takeoff line must be the
     * descent shell's depth in world blocks (r=0.2727)".</p>
     */
    @Test
    public void theTakeoffLineIsTheDescentShellSeenFromInsideTheWorld() {
        int metresPerChartBlock = dev.stannismod.stellurgy.util.AstronomicalBodyHelper.METRES_PER_CHART_BLOCK;
        for (double r : new double[]{0.2727d, 1d, 11d}) {
            long shell = DescentShell.radiusAround(sized(r));
            double surface = r * dev.stannismod.stellurgy.util.AstronomicalBodyHelper.EARTH_RADIUS_BLOCKS;
            double depthInWorldBlocks = (shell - surface) * metresPerChartBlock;
            assertEquals("the takeoff line must be the descent shell's depth in world blocks (r=" + r + ")",
                    depthInWorldBlocks, DescentShell.orbitLineWorldY(r), metresPerChartBlock);
        }
        assertEquals("and Earth's is the Kármán line, 100 km above its surface, within the 0.11 % its "
                        + "6 378 km radius and the line's 6 371 km definition differ by",
                100_000d, DescentShell.orbitLineWorldY(1d), 150d);
    }

    /**
     * A line a ship could cross while parked is the floor, not an atmosphere: a body small enough that
     * its derived line would sit inside the block band takes the top of the band, and a body with no
     * radius has no line to derive.
     *
     * <p>red-witnessed: 2026-10-05, with {@code DescentShell#orbitLineWorldY} at {@code return (int) Math.max(TerrainHeightFinder.MAX_BUILD_Y, Math.round(depthWorldBlocks));}
     * returning the bare rounded depth, this fails with "expected:&lt;255&gt; but was:&lt;100&gt;".</p>
     */
    @Test
    public void aTakeoffLineNeverSitsInsideTheBlockBand() {
        assertEquals(dev.stannismod.stellurgy.space.TerrainHeightFinder.MAX_BUILD_Y,
                DescentShell.orbitLineWorldY(0.001d));
        try {
            DescentShell.orbitLineWorldY(0d);
            org.junit.Assert.fail("a body with no radius has no line, and must not be handed one");
        } catch (IllegalArgumentException expected) {
            // the refusal is the answer
        }
    }

    @Test
    public void aBodyWithNoSizeKeepsTheFlatProximityRadius() {
        // A belt or a station slot is not a sphere and has no surface to stand above, so the constant
        // is the right answer there rather than a fallback — and a body whose atmosphere would be
        // thinner than a ship's manoeuvring scale keeps it too.
        assertEquals(dev.stannismod.stellurgy.space.ShipEntryController.DESCENT_RADIUS_BLOCKS,
                dev.stannismod.stellurgy.space.DescentShell.radiusAround(sized(0d)));
        assertEquals(dev.stannismod.stellurgy.space.ShipEntryController.DESCENT_RADIUS_BLOCKS,
                dev.stannismod.stellurgy.space.DescentShell.radiusAround(null));
    }
}
