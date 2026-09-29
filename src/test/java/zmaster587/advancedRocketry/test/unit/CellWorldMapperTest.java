package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.space.CellWorldMapper;
import zmaster587.advancedRocketry.space.GalacticCoord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the honest-3D cell&harr;slot-world pose mapping: all three axes realize
 * directly and alike — world X/Y/Z are the cell-local offsets, with the cell centred on the world
 * origin — every realizable pose lands somewhere a pilot can legally be, and the mapping round-trips
 * exactly.
 */
public class CellWorldMapperTest {

    private static GalacticCoord at(long sx, long sy, long sz, long lx, long ly, long lz) {
        return GalacticCoord.ofSectorLocal(sx, sy, sz, lx, ly, lz);
    }

    @Test
    public void worldXAndZAreTheCellLocalOffsets() {
        double[] pose = CellWorldMapper.poseWorldOf(at(3, -2, 7, 1500, 0, -42_000));
        assertEquals(1500.0, pose[0], 0.0);
        assertEquals(-42_000.0, pose[2], 0.0);
    }

    /**
     * The vanilla limit a realized pose must respect, and the only one that DISCONNECTS rather than
     * corrects: {@code NetHandlerPlayServer.isMovePlayerPacketInvalid} refuses a position packet
     * whose {@code |x|}, {@code |y|} or {@code |z|} exceeds this, and the server drops the player on
     * the spot. Read from vanilla, not chosen here.
     */
    private static final double VANILLA_POSITION_PACKET_LIMIT = 3.0e7;

    /**
     * Every pose a cell can realize must be somewhere a PILOT can be.
     *
     * <p>This replaces a test that pinned the opposite arrangement — that the whole band realized
     * ABOVE vanilla's Y=-64 void kill, which the mapping bought with a {@code +HALF_CELL} shift on Y
     * alone. That shift made Y spend the entire cell going up while X and Z spent half of it each
     * way, and the top of every cell then sat past the packet limit below: measured 2026-09-11, a
     * seated pilot teleported there was disconnected inside the same tick. The void kill is gated in
     * cell worlds instead, so the band is centred and this is the bound that matters.</p>
     */
    @Test
    public void everyRealizablePoseIsSomewhereAPilotCanBe() {
        double[] floor = CellWorldMapper.poseWorldOf(at(0, 0, 0, 0, -GalacticCoord.HALF_CELL, 0));
        double[] top = CellWorldMapper.poseWorldOf(at(0, 0, 0, 0, GalacticCoord.HALF_CELL - 1, 0));
        for (double[] pose : new double[][]{floor, top}) {
            for (int axis = 0; axis < 3; axis++) {
                assertTrue("a realizable cell pose must stay inside vanilla's position-packet limit,"
                                + " which DISCONNECTS a player rather than correcting him: axis "
                                + axis + " of pose [" + pose[0] + "," + pose[1] + "," + pose[2] + "]",
                        Math.abs(pose[axis]) <= VANILLA_POSITION_PACKET_LIMIT);
            }
        }
        // Centred, and monotone with it: the cell centre sits exactly HALF_CELL above the floor,
        // and the floor is as far below the origin as the top is above it.
        double[] centre = CellWorldMapper.poseWorldOf(at(0, 0, 0, 0, 0, 0));
        assertEquals(GalacticCoord.HALF_CELL, centre[1] - floor[1], 0.0);
        assertEquals(0.0, centre[1], 0.0);
    }

    /**
     * The physics mod clamps every ship's altitude per physics step, and a ship's own thrust can
     * never carry it past the clamp - so the ceiling the subsystem initializes at registration
     * must sit ABOVE every pose a cell can realize, or some part of the advertised cell range is
     * an invisible wall. Pins ceiling-covers-band, not any particular number.
     */
    @Test
    public void initializedShipCeilingCoversEveryRealizablePose() {
        double top = CellWorldMapper.poseWorldOf(
                at(0, 0, 0, 0, GalacticCoord.HALF_CELL - 1, 0))[1];
        assertTrue("the ship ceiling raised at subsystem registration ("
                        + zmaster587.advancedRocketry.space.SpaceSubsystem.requiredShipCeiling()
                        + ") must clear the topmost realizable cell pose (" + top + ")",
                zmaster587.advancedRocketry.space.SpaceSubsystem.requiredShipCeiling() > top);
    }

    @Test
    public void poseMappingRoundTripsExactly() {
        GalacticCoord original = at(5, 1, -9, 123_456, -777_777, 42);
        double[] pose = CellWorldMapper.poseWorldOf(original);
        GalacticCoord back = CellWorldMapper.coordOfPose(original.cellCentre(),
                pose[0], pose[1], pose[2]);
        assertEquals(original, back);
    }

    @Test
    public void outOfRangePoseRenormalisesIntoTheNeighbouringSector() {
        // A pose past the cell's +X edge belongs to the next sector - the seam-crossing semantics.
        GalacticCoord cell = at(0, 0, 0, 0, 0, 0);
        double[] centrePose = CellWorldMapper.poseWorldOf(cell);
        GalacticCoord past = CellWorldMapper.coordOfPose(cell,
                GalacticCoord.HALF_CELL + 10.0, centrePose[1], 0.0);
        assertEquals(1L, past.sectorX());
    }

    /**
     * The other reading of the same pose, and the one a ship REPORTING its position must use: it
     * stays in the cell it is in.
     *
     * <p>The two are not interchangeable. A cell name is not a position — it names a world, a slot
     * binding and the ledger row that keeps that cell from being collected — and none of those follow
     * a pose over a cell face. A ship that renames itself by drifting ends up addressed in a cell
     * nobody loaded: its own cell's bodies vanish from its sky, its descent finds nothing to descend
     * to, and its jumps are refused for being somewhere it is not.</p>
     */
    @Test
    public void aReportedPosePastTheCellEdgeStaysInItsOwnCell() {
        GalacticCoord cell = at(0, 0, 0, 0, 0, 0);
        double[] centrePose = CellWorldMapper.poseWorldOf(cell);

        GalacticCoord held = CellWorldMapper.coordOfPoseWithin(cell,
                GalacticCoord.HALF_CELL + 10.0, centrePose[1], 0.0);

        assertEquals("a reported pose may not rename the cell", cell.cellKey(), held.cellKey());
        assertTrue("...and it is held at the boundary, not wrapped to the far side",
                held.localX() > 0L);
    }

    /**
     * An arrival's blocks are STAGED outside the cell, in the clearance between the cell face and
     * the physics mod's reserved shipyard — and that window is what this pins.
     *
     * <p>Below the face, a paste would land where a craft may legitimately be parked (every local
     * coordinate in a cell is somewhere a pilot can go). At or past the shipyard start, the physics
     * mod silently cancels a teleport into its own claims. The window between them is 3.17M blocks
     * wide today and it narrows as the cell grows, so a cell that outgrows it must fail HERE rather
     * than by pasting a ship into VS's storage.</p>
     */
    @Test
    public void anArrivalIsStagedBetweenTheCellFaceAndTheShipyard() {
        long staging = zmaster587.advancedRocketry.space.VSShipCrosser.ARRIVAL_STAGING_X;
        assertTrue("an arrival must be staged OUTSIDE the cell, where no craft can be flying:"
                        + " staging X " + staging + " vs cell face " + GalacticCoord.HALF_CELL,
                staging > GalacticCoord.HALF_CELL);
        long shipyardStart = 16L * (org.valkyrienskies.mod.common.ships.chunk_claims.ShipChunkAllocator
                .CHUNK_X_START - org.valkyrienskies.mod.common.ships.chunk_claims.ShipChunkAllocator
                .MAX_CHUNK_RADIUS);
        assertTrue("an arrival must be staged BELOW the physics mod's reserved shipyard, which"
                        + " silently cancels a teleport into it: staging X " + staging
                        + " vs shipyard start " + shipyardStart,
                staging < shipyardStart);
    }

    /**
     * The staging band's Y — an ordinary block altitude near 200 — is an ordinary INTERIOR Y for a
     * cell, not a hair's breadth outside it.
     *
     * <p>This is what the Y centring bought, and it is why the test is kept rather than deleted.
     * Under the old {@code +HALF_CELL} shift the cell's band began at {@code HALF_CELL + 256}, so
     * inverting any ordinary block altitude gave a local Y just BELOW the cell's range: a ship read
     * between the paste and the pose settle named the cell ONE SECTOR DOWN, on every single arrival,
     * and the saturating read existed to absorb it. Centred, world Y 200 is local Y 200 — just above
     * the cell's middle — and the commonest case in the game stopped being an edge case.</p>
     *
     * <p>Asked on the Y axis alone, deliberately: an arrival is STAGED outside the cell in X (see
     * {@link #anArrivalIsStagedBetweenTheCellFaceAndTheShipyard}), so the whole staging pose is an
     * escape and would answer this question about the wrong axis.</p>
     */
    @Test
    public void anOrdinaryBlockAltitudeIsAnInteriorYForACell() {
        GalacticCoord cell = at(57, 0, 5, 0, 0, 0);
        double blockBandY = 200.0;

        assertFalse("an ordinary block altitude must not read as an escape on Y",
                CellWorldMapper.poseEscapesCell(0.0, blockBandY, 0.0));
        assertEquals("...and must not name a neighbouring cell", cell.cellKey(),
                CellWorldMapper.coordOfPoseWithin(cell, 0.0, blockBandY, 0.0).cellKey());
        assertEquals("...with the honest inverse agreeing, no saturation needed to get there",
                cell.sectorY(),
                CellWorldMapper.coordOfPose(cell, 0.0, blockBandY, 0.0).sectorY());
    }

    /** A pose inside the cell is not "escaping" — the detector must not fire on ordinary flight. */
    @Test
    public void anOrdinaryPoseInsideTheCellIsNotAnEscape() {
        GalacticCoord cell = at(1, 2, 3, 0, 0, 0);
        double[] pose = CellWorldMapper.poseWorldOf(at(1, 2, 3, 120_000L, -80_000L, 5L));

        assertFalse("a pose well inside the cell must not read as an escape",
                CellWorldMapper.poseEscapesCell(pose[0], pose[1], pose[2]));
        assertEquals("...and it round-trips unchanged through the held reading",
                CellWorldMapper.coordOfPose(cell, pose[0], pose[1], pose[2]),
                CellWorldMapper.coordOfPoseWithin(cell, pose[0], pose[1], pose[2]));
    }
}
