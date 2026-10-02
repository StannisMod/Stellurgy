package dev.stannismod.stellurgy.test.unit;

import org.junit.Test;

import dev.stannismod.stellurgy.space.CellSeam;
import dev.stannismod.stellurgy.space.CellWorldMapper;
import dev.stannismod.stellurgy.space.GalacticCoord;

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
        return new double[]{lx, ly, lz};
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
        long arrivedAt = CellSeam.localOf(arrival[0]);
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
     *
     * <p>red-witnessed: 2026-09-29, with {@code CellSeam#distanceFromZoneBody} at {@code double dx = (double) coord.sectorX() * width + coord.localX()} reading the in-cell
     * offset alone (the {@code sector * width} term dropped), this fails with "expected:&lt;5547882.0&gt;
     * but was:&lt;0.0&gt;" — a craft three cells out read as standing on the body.</p>
     */
    @Test
    public void distanceIsMeasuredFromTheBodyAndNotFromTheCell() {
        // The body's own cell, a third of a cell out: the offset IS the distance. The 1-block
        // tolerance on both reads is the offset's own rounding — an in-cell offset is a whole block,
        // and ZONE_CELL / 3 in long arithmetic drops the .33 that ZONE_CELL / 3d keeps.
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
     *
     * <p>red-witnessed: 2026-09-29, with {@code CellSeam#hasEnteredZone} at {@code && distanceBlocks < zoneRadiusBlocks * (1d - SPHERE_REENTRY_FRACTION)} entering at the sphere itself
     * (the {@code 1 - SPHERE_REENTRY_FRACTION} factor dropped), this fails with "but a whisker inside
     * is not — that is the gap"; every other test in the class stays green on that inversion.</p>
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

        // Both are FRACTIONS, so they move with the sphere. A zone's radius spans four orders of
        // magnitude across one system; an absolute margin would be a different rule for each body.
        double tiny = 512d;
        assertTrue("the same rule must hold at a tiny sphere",
                CellSeam.hasLeftZone(tiny * (1d + CellSeam.SPHERE_CARRY_FRACTION) + 1d, tiny));
    }

    /**
     * A zone with no radius has no sphere to leave or to enter, and both answers are NO rather than
     * a guess.
     *
     * <p>That is the galactic lattice, where a cell's extent IS its cube. A sphere test that answered
     * "yes, you have left" for a radius of zero would carry every craft in deep space out of a zone
     * it was never in; one that answered "entered" for the {@code -1} that
     * {@link CellSeam#distanceFromZoneBody} returns for a galactic coordinate would take it into one.</p>
     *
     * <p>red-witnessed: 2026-09-29, twice. With {@code CellSeam#hasLeftZone} at {@code return zoneRadiusBlocks > 0d} stripped of its
     * {@code zoneRadiusBlocks > 0} guard, this fails (then with a bare {@code AssertionError}; the
     * assertions carry messages since). With {@code CellSeam#hasEnteredZone} at {@code return zoneRadiusBlocks > 0d} stripped of the same
     * guard, it fails with "with no sphere there is nothing to enter, even for the -1 a galactic
     * coordinate's distance is" — the assertion that read {@code (0, 0)} before could not see that
     * guard at all, since {@code 0 < 0} is false without it.</p>
     */
    @Test
    public void noSphereMeansNoSphereAnswerRatherThanZero() {
        // The POSITIVE half first, in this method (STEP 7): the same distance against a real sphere
        // IS a departure, so the "no" below is the missing sphere answering and not a predicate that
        // never says yes.
        assertTrue("a million blocks out of a 264 000-block sphere is a departure",
                CellSeam.hasLeftZone(1_000_000d, 264_000d));
        assertTrue("and half-way into one is an entry", CellSeam.hasEnteredZone(132_000d, 264_000d));

        assertFalse("with no sphere there is nothing to leave",
                CellSeam.hasLeftZone(1_000_000d, 0d));
        assertFalse("with no sphere there is nothing to enter, even for the -1 a galactic "
                + "coordinate's distance is", CellSeam.hasEnteredZone(-1d, 0d));
    }

    /**
     * <b>Where the sphere and the cube both fire, the SPHERE aims the carry.</b>
     *
     * <p>The cube a carry consults is the galactic one — {@code HALF_CELL + CARRY_MARGIN} from the
     * cell's centre, in every cell, zoned or not ({@code CellSeam.shouldCarry}) — and the widest
     * sphere production realizes is capped at {@code HALF_CELL} ({@code ZoneScale.realizedRadiusBlocks}).
     * At that cap a craft just past the sphere is also past the cube, so "the sphere fires first" is
     * not arithmetic there: both fire, and only the ORDER inside
     * {@code CellCrossingController.carryDestination} decides where the craft goes. Asked of the
     * cube, it would be the +X neighbour — a cube face nowhere near the sphere it crossed.</p>
     *
     * <p>red-witnessed: 2026-09-29, with {@code CellCrossingController#carryDestination} at {@code if (bySphere != null)} asking the cube
     * BEFORE the sphere, this fails with "where both fire the carry must be aimed by the sphere
     * (20_0_0), not at the cube's neighbour: got 2_0_0". (The version before that date asserted
     * {@code past < 2R/2 + CARRY_MARGIN} on a cube of the test's own making, which production never
     * consults.)</p>
     */
    @Test
    public void theSphereFiresBeforeTheCubeWhereBothApply() {
        double radius = GalacticCoord.HALF_CELL;           // the widest sphere production realizes
        long past = (long) Math.ceil(radius * (1d + CellSeam.SPHERE_CARRY_FRACTION)) + 1L;
        GalacticCoord cell = GalacticCoord.inZone(ZONE, ZONE_CELL, 1L, 0L, 0L, 0L, 0L, 0L);
        GalacticCoord bySphere = GalacticCoord.ofSectorLocal(19L, 0L, 0L, past, 0L, 0L);

        // ARRANGEMENT: both answers are "carry", or there is no order to decide.
        assertTrue("arrangement: the sphere must call this a departure",
                CellSeam.hasLeftZone(past, radius));
        assertTrue("arrangement: and so must the cube, or this is not the case where both apply",
                CellSeam.shouldCarry(past, 0d, 0d));

        dev.stannismod.stellurgy.space.CellCrossingController controller =
                new dev.stannismod.stellurgy.space.CellCrossingController(null, null, null,
                        () -> 0L, (craft, tick) -> bySphere);
        GalacticCoord aimed = controller.carryDestination(cell, new double[]{past, 0d, 0d});
        assertTrue("where both fire the carry must be aimed by the sphere (" + bySphere.cellKey()
                        + "), not at the cube's neighbour: got "
                        + (aimed == null ? "null" : aimed.cellKey()),
                aimed != null && aimed.sameCell(bySphere));
    }
}
