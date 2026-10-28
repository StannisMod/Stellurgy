package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.client.render.planet.ApparentSize;
import dev.stannismod.stellurgy.space.DescentShell;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A fed body is drawn at the angle it truly subtends, {@code asin(r / d)}, and never smaller than a
 * visible marker.
 */
public class ApparentSizeTest {

    /** A constant: a primitive. An Earth's radius in the chart blocks the feed sends. */
    private static final double EARTH_R = AstronomicalBodyHelper.EARTH_RADIUS_BLOCKS;

    /**
     * This test fails if {@code ApparentSize#halfAngleFor} stops deciding that a body near enough to
     * show its size is drawn at the angle it truly subtends: an Earth seen from twice its descent shell —
     * where an entering ship spawns — is ~29.5 degrees in radius, and one seen from its own shell nearly
     * fills half the sky.
     */
    @Pins("CON-C14-16")
    @RedWitness(member = "ApparentSize#halfAngleFor", at = "Math.asin(ratio)",
            replacedBy = "Math.min(Math.asin(ratio), Math.atan(16.0d / 90.0d))",
            fails = "a body near enough to show its size is drawn at the angle it truly subtends",
            on = "2026-10-28")
    @Test
    public void aBodyNearEnoughToShowItsSizeIsDrawnAtItsTrueAngle() {
        double shell = EARTH_R * (1d + DescentShell.ATMOSPHERE_FRACTION);
        for (double d : new double[] {2d * shell, shell, 10d * shell, 50d * shell}) {
            double truth = Math.asin(EARTH_R / d);
            dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged("the true angle at " + d
                    + " blocks must be above the floor", truth > ApparentSize.MIN_HALF_ANGLE);
            assertEquals("a body near enough to show its size is drawn at the angle it truly subtends (at "
                    + d + " blocks)", truth, ApparentSize.halfAngleFor(EARTH_R, d), 1e-9);
        }
    }

    /**
     * This test fails if {@code ApparentSize#halfAngleFor} stops deciding that a body too far to see is
     * still drawn, at the floor, rather than at its true sub-pixel angle.
     */
    @Pins("CON-C14-16")
    @RedWitness(member = "ApparentSize#halfAngleFor",
            at = "Math.max(MIN_HALF_ANGLE, Math.min(MAX_HALF_ANGLE, angle))",
            replacedBy = "Math.min(MAX_HALF_ANGLE, angle)",
            fails = "a body too far to see is still drawn at the floor", on = "2026-10-28")
    @Test
    public void aBodyTooFarToSeeIsStillDrawnAtTheFloor() {
        double far = 1.0e15d;
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                "the true angle there must be below the floor", Math.asin(EARTH_R / far) < ApparentSize.MIN_HALF_ANGLE);
        assertEquals("a body too far to see is still drawn at the floor", ApparentSize.MIN_HALF_ANGLE,
                ApparentSize.halfAngleFor(EARTH_R, far), 1e-12);
    }

    /**
     * This test fails if {@code ApparentSize#halfAngleFor} stops refusing a body with no radius — giving it
     * a size instead, a marker that would pass for a distant world.
     */
    @Pins("CON-C14-16")
    @RedWitness(member = "ApparentSize#halfAngleFor",
            at = "throw new IllegalArgumentException(\"a body with no radius (\" + radiusBlocks"
                    + " + \") has no angular size; it is not drawn as a sphere\");",
            replacedBy = "return MIN_HALF_ANGLE;", fails = "a body with no radius", on = "2026-10-28")
    @Test
    public void aBodyWithNoRadiusHasNoAngularSize() {
        for (double r : new double[] {0d, -3d, Double.NaN}) {
            try {
                ApparentSize.halfAngleFor(r, 100_000d);
                org.junit.Assert.fail("a body with no radius (" + r + ") was given an angular size");
            } catch (IllegalArgumentException refused) {
                // the decision under test
            }
        }
    }

    /**
     * This test fails if {@code ApparentSize#halfAngleFor} stops deciding that a distance that could not
     * be measured — or an observer at or inside the surface — is drawn as near as the representation
     * allows, rather than silently vanishing from the sky.
     */
    @Test
    public void aNonsenseDistanceIsTreatedAsNearRatherThanInvisible() {
        assertEquals(ApparentSize.MAX_HALF_ANGLE, ApparentSize.halfAngleFor(EARTH_R, Double.NaN), 1e-12);
        assertEquals(ApparentSize.MAX_HALF_ANGLE, ApparentSize.halfAngleFor(EARTH_R, -5d), 1e-12);
        assertEquals(ApparentSize.MAX_HALF_ANGLE, ApparentSize.halfAngleFor(EARTH_R, 0d), 1e-12);
        assertEquals(ApparentSize.MAX_HALF_ANGLE, ApparentSize.halfAngleFor(EARTH_R, EARTH_R / 2d), 1e-12);
    }

    @Test
    public void aDistanceLabelIsLegibleAcrossTheWholeRange() {
        // The label has to read at a glance over six decades; "1183472901 m" does not.
        assertEquals("500 m", ApparentSize.formatDistance(500d));
        assertEquals("120 km", ApparentSize.formatDistance(120_000d));
        assertEquals("45 Mm", ApparentSize.formatDistance(45_000_000d));
        assertEquals("12 Gm", ApparentSize.formatDistance(12_000_000_000d));
    }

    /**
     * This test fails if production breaks the contract that a range shown to a pilot is a range in
     * the units the label claims — so a chart length is converted before it is printed under a unit
     * of length, rather than having its block count relabelled as metres.
     */
    @Test
    public void aChartRangeIsPrintedInRealUnitsAndNotInBlocksWearingThem() {
        // One chart block is 250 m, so the two forms of the same call must differ by exactly that.
        assertEquals(ApparentSize.formatDistance(500d * AstronomicalBodyHelper.METRES_PER_CHART_BLOCK),
                ApparentSize.formatChartDistance(500d));
        assertEquals("125 km", ApparentSize.formatChartDistance(500d));

        // The measured case: Earth read from a parked ship at 5 657 554 chart blocks. Printing the
        // block count gave "6 Mm"; the range is 1.414e9 m, which this ladder spells "1414 Mm"
        // (the Gm step is at 10 Gm, so a gigametre-scale range still reads in Mm).
        assertEquals("1414 Mm", ApparentSize.formatChartDistance(5_657_554d));

        // A negative range is clamped before conversion, not after — the sign must not survive the
        // multiply and come back as a large positive number.
        assertEquals("0 m", ApparentSize.formatChartDistance(-1d));
    }
}
