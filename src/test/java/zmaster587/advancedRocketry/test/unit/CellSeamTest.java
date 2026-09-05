package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.space.CellSeam;
import zmaster587.advancedRocketry.space.CellWorldMapper;
import zmaster587.advancedRocketry.space.GalacticCoord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for {@link CellSeam} — the arithmetic of flying THROUGH a cell face.
 *
 * <p>What these pin is deliberately narrow: that a ship inside its cell is left alone, that one far
 * enough past a face is carried into the neighbour it left through, that it arrives inside that
 * neighbour rather than on its face, and that the return trip costs more than the outbound overshoot
 * — which is the whole content of "no ping-pong". The margins themselves are read from the class, not
 * restated, so a re-tuning changes the behaviour these tests describe without making them lie.</p>
 */
public class CellSeamTest {

    private static final GalacticCoord CELL = GalacticCoord.ofSectorLocal(3L, -1L, 7L, 0, 0, 0);

    /** The world-frame pose whose local offset is {@code (lx,ly,lz)} — the inverse of the mapping. */
    private static double[] poseOfLocal(long lx, long ly, long lz) {
        return new double[]{lx, ly + GalacticCoord.HALF_CELL + CellWorldMapper.POSE_BAND_Y, lz};
    }

    @Test
    public void aShipInsideItsCellIsNotCarried() {
        double[] deepInside = poseOfLocal(0L, 0L, 0L);
        assertFalse(CellSeam.shouldCarry(deepInside[0], deepInside[1], deepInside[2]));

        // Right up against the face, and even a little past it: still not a crossing. This is the case
        // the margin exists for — a report may saturate here, a ship may not change worlds here.
        double[] onTheFace = poseOfLocal(GalacticCoord.HALF_CELL, 0L, 0L);
        assertFalse(CellSeam.shouldCarry(onTheFace[0], onTheFace[1], onTheFace[2]));
        double[] justPast = poseOfLocal(GalacticCoord.HALF_CELL + CellSeam.CARRY_MARGIN, 0L, 0L);
        assertFalse(CellSeam.shouldCarry(justPast[0], justPast[1], justPast[2]));
    }

    @Test
    public void aShipPastTheMarginIsCarriedIntoTheNeighbourItLeftThrough() {
        double[] out = poseOfLocal(GalacticCoord.HALF_CELL + CellSeam.CARRY_MARGIN + 1L, 0L, 0L);
        assertTrue(CellSeam.shouldCarry(out[0], out[1], out[2]));

        GalacticCoord dest = CellSeam.carriedCoord(CELL, out[0], out[1], out[2]);
        assertEquals("the +X neighbour, and only that one", CELL.sectorX() + 1L, dest.sectorX());
        assertEquals(CELL.sectorY(), dest.sectorY());
        assertEquals(CELL.sectorZ(), dest.sectorZ());
        assertEquals("placed inside the face it came in by",
                -GalacticCoord.HALF_CELL + CellSeam.REENTRY_DEPTH, dest.localX());
    }

    @Test
    public void theAxesThatDidNotCrossKeepWhereThePilotFlewThem() {
        long ly = 4_242L;
        long lz = -1_000_000L;
        double[] out = poseOfLocal(-GalacticCoord.HALF_CELL - CellSeam.CARRY_MARGIN - 1L, ly, lz);

        GalacticCoord dest = CellSeam.carriedCoord(CELL, out[0], out[1], out[2]);
        assertEquals(CELL.sectorX() - 1L, dest.sectorX());
        assertEquals("left through -X, so it arrives just inside the +X face",
                GalacticCoord.HALF_CELL - CellSeam.REENTRY_DEPTH, dest.localX());
        assertEquals(ly, dest.localY());
        assertEquals(lz, dest.localZ());
    }

    @Test
    public void aCornerExitCarriesEveryAxisThatCrossed() {
        double[] out = poseOfLocal(
                GalacticCoord.HALF_CELL + CellSeam.CARRY_MARGIN + 1L,
                -GalacticCoord.HALF_CELL - CellSeam.CARRY_MARGIN - 1L,
                GalacticCoord.HALF_CELL + CellSeam.CARRY_MARGIN + 1L);

        GalacticCoord dest = CellSeam.carriedCoord(CELL, out[0], out[1], out[2]);
        assertEquals(CELL.sectorX() + 1L, dest.sectorX());
        assertEquals(CELL.sectorY() - 1L, dest.sectorY());
        assertEquals(CELL.sectorZ() + 1L, dest.sectorZ());
    }

    /**
     * The hysteresis, measured on the ship rather than on the constants: from where a carry actually
     * PUT it, flying straight back must cost at least {@code REENTRY_DEPTH + CARRY_MARGIN}.
     *
     * <p>Every distance below is derived from the arrival coordinate. An earlier version of this test
     * compared the two constants to each other and asserted about poses computed from them, and it
     * stayed green against a build that landed the ship ON the face — which is the whole defect this
     * test exists to catch, with the return trip cut by a factor of ten.</p>
     */
    @Test
    public void aCarriedShipCannotPingPongBackAcrossTheFace() {
        double[] out = poseOfLocal(GalacticCoord.HALF_CELL + CellSeam.CARRY_MARGIN + 1L, 0L, 0L);
        GalacticCoord dest = CellSeam.carriedCoord(CELL, out[0], out[1], out[2]);

        double[] arrival = CellWorldMapper.poseWorldOf(dest);
        assertFalse("the arrival pose must not itself be a crossing",
                CellSeam.shouldCarry(arrival[0], arrival[1], arrival[2]));

        // Where it landed, and how far back the return threshold is FROM THERE.
        long arrivedAt = CellSeam.localOf(arrival[0], false);
        long returnThreshold = -GalacticCoord.HALF_CELL - CellSeam.CARRY_MARGIN;
        long returnTrip = arrivedAt - returnThreshold;
        assertTrue("returning must cost the re-entry depth plus the margin, not merely the margin: "
                        + "arrived at " + arrivedAt + ", threshold " + returnThreshold,
                returnTrip >= CellSeam.REENTRY_DEPTH + CellSeam.CARRY_MARGIN);

        // And the threshold is where it says it is: one block short does not cross, one past does.
        double[] almostBack = poseOfLocal(arrivedAt - returnTrip + 1L, 0L, 0L);
        assertFalse("one block short of the return threshold is still not a crossing",
                CellSeam.shouldCarry(almostBack[0], almostBack[1], almostBack[2]));
        double[] allTheWayBack = poseOfLocal(arrivedAt - returnTrip - 1L, 0L, 0L);
        assertTrue("one block past it must carry the ship back",
                CellSeam.shouldCarry(allTheWayBack[0], allTheWayBack[1], allTheWayBack[2]));
    }

    /**
     * Both margins are fractions of the cell. Pinned because the failure they guard against is silent:
     * an absolute margin keeps its number when the cell is resized and quietly becomes a different
     * duration — which is exactly what happened to the moon band before it was expressed this way.
     */
    @Test
    public void theMarginsScaleWithTheCell() {
        assertEquals(GalacticCoord.HALF_CELL / 10_000L, CellSeam.CARRY_MARGIN);
        assertEquals(GalacticCoord.HALF_CELL / 1_000L, CellSeam.REENTRY_DEPTH);
        assertTrue("the re-entry depth must exceed the carry margin, or the hysteresis is inverted",
                CellSeam.REENTRY_DEPTH > CellSeam.CARRY_MARGIN);
    }

    // ─── The SPHERE boundary, inside a zone ────────────────────────────────────

    /** A zone whose cells are this wide — Earth's, at the shipped metric. */
    private static final long ZONE_CELL = 1_849_294L;
    private static final String ZONE = "19_0_0";

    /**
     * <b>Distance is measured from the BODY, not from the cell a craft happens to sit in.</b>
     *
     * <p>A sphere of influence is centred on a body and never on a lattice, so a craft in an EMPTY
     * cell of a zone is displaced from the body by its cell's own offset PLUS its offset inside that
     * cell. Reading the in-cell offset alone would put every craft at most half a cell from the body
     * however far across the zone it had flown — which for Earth's zone is an error of millions of
     * blocks and always in the direction of "you have not left yet".</p>
     */
    @Test
    public void distanceIsMeasuredFromTheBodyAndNotFromTheCell() {
        // The body's own cell, a third of a cell out: the offset IS the distance.
        GalacticCoord atHome = GalacticCoord.inZone(ZONE, ZONE_CELL, 0, 0, 0, ZONE_CELL / 3L, 0L, 0L);
        assertEquals(ZONE_CELL / 3d, CellSeam.distanceFromZoneBody(atHome), 1d);

        // Three cells out along +x, at that cell's centre: three cell widths from the body.
        GalacticCoord threeOut = GalacticCoord.inZone(ZONE, ZONE_CELL, 3, 0, 0, 0L, 0L, 0L);
        assertEquals(3d * ZONE_CELL, CellSeam.distanceFromZoneBody(threeOut), 1d);

        // A galactic coordinate is in no zone, and the answer says so rather than inventing one.
        assertTrue("a galactic coordinate has no zone to be measured from",
                CellSeam.distanceFromZoneBody(GalacticCoord.ofSectorLocal(19, 0, 0, 0, 0, 0)) < 0d);
    }

    /**
     * The two sphere thresholds are a HYSTERESIS: strictly apart, and the wider one on the way out.
     *
     * <p>Between them a craft stays where it is. Without the gap a craft drifting on the boundary
     * re-decides its frame every tick and pays a full cut-and-paste each time — the same failure the
     * cube's two margins exist for, one level down, and the same ratio: ten to one.</p>
     */
    @Test
    public void theSphereThresholdsAreAHysteresisAndNotOneBoundaryReadTwice() {
        double r = 264_000d; // Luna's sphere, in blocks

        assertFalse("exactly on the sphere is not yet out", CellSeam.hasLeftZone(r, r));
        assertFalse("nor is a whisker past it", CellSeam.hasLeftZone(r * 1.00001d, r));
        assertTrue("but past the carry fraction it is",
                CellSeam.hasLeftZone(r * (1d + CellSeam.SPHERE_CARRY_FRACTION) + 1d, r));

        assertFalse("exactly on the sphere is not yet IN either", CellSeam.hasEnteredZone(r, r));
        assertTrue("well inside it is", CellSeam.hasEnteredZone(r * 0.5d, r));
        assertFalse("but a whisker inside is not — that is the gap",
                CellSeam.hasEnteredZone(r * 0.99999d, r));

        assertTrue("the entry threshold must be strictly deeper than the exit one, or the two are "
                        + "one boundary read twice and a drifting craft flickers between frames",
                CellSeam.SPHERE_REENTRY_FRACTION > CellSeam.SPHERE_CARRY_FRACTION);

        // Both are FRACTIONS, so they move with the sphere. A zone's radius spans four orders of
        // magnitude across one system; an absolute margin would be a different rule for each body.
        double tiny = 512d;
        assertTrue("the same rule must hold at a tiny sphere",
                CellSeam.hasLeftZone(tiny * (1d + CellSeam.SPHERE_CARRY_FRACTION) + 1d, tiny));
    }

    /**
     * A zone with no radius has no sphere to leave, and both answers are NO rather than a guess.
     *
     * <p>That is the galactic lattice, where a cell's extent IS its cube. A sphere test that answered
     * "yes, you have left" for a radius of zero would carry every craft in deep space out of a zone
     * it was never in.</p>
     */
    @Test
    public void noSphereMeansNoSphereAnswerRatherThanZero() {
        assertFalse(CellSeam.hasLeftZone(1_000_000d, 0d));
        assertFalse(CellSeam.hasEnteredZone(0d, 0d));
    }

    /**
     * The sphere is INSCRIBED in the cell, so where both apply the sphere always fires first.
     *
     * <p>This is what makes the change safe to land ahead of the rest: a craft is never carried by
     * the cube out of a zone it had not already left by the sphere. The relation holds because a
     * childless body's zone is one cell of exactly twice its own radius, so the cell's half-width IS
     * the radius — the sphere touches each face and is inside everywhere else.</p>
     */
    @Test
    public void theSphereFiresBeforeTheCubeWhereBothApply() {
        long radius = 264_000L;
        long cell = 2L * radius;                 // a childless body's zone: one cell, span 2R
        // Straight out along +x, one block past the sphere's carry threshold.
        long past = (long) (radius * (1d + CellSeam.SPHERE_CARRY_FRACTION)) + 1L;
        GalacticCoord at = GalacticCoord.inZone(ZONE, cell, 0, 0, 0, past, 0L, 0L);

        assertTrue("the sphere must call this a departure",
                CellSeam.hasLeftZone(CellSeam.distanceFromZoneBody(at), radius));
        assertTrue("...while the cube has not, which is the ordering this rests on",
                past < cell / 2L + CellSeam.CARRY_MARGIN);
    }
}
