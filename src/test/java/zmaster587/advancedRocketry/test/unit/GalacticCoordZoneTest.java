package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.space.GalacticCoord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A sector triple means nothing without the lattice it is expressed in.
 *
 * <p>A zone's cell size is a property of the ZONE, so the same triple denotes different places in
 * different zones. Before this, {@code GalacticCoord} had one lattice and the
 * triple was the whole identity — carrying zones in the key alone would have left two coordinates in
 * DIFFERENT zones comparing EQUAL, and every map, ledger row and slot binding keyed by one of them
 * answering for the other. That is the failure these assertions exist for, and it is silent.</p>
 *
 * <p><b>The galactic lattice is the null zone</b>, so every coordinate written before zones existed
 * keeps its exact meaning — which the round-trip case below pins directly.</p>
 */
public class GalacticCoordZoneTest {

    private static final String EARTH = "19_0_0";
    private static final String MARS = "23_0_0";

    /**
     * Earth's zone cell, in blocks: {@code 2 * r_SOI / ZoneScale.CELLS_ACROSS_A_ZONE} at the real
     * value (3 704 000 blocks of sphere across 1024 cells). Stated as a literal rather than computed
     * from a body so these cases stay pure arithmetic on the coordinate; the point every one of them
     * makes is that this number is nothing like {@link GalacticCoord#CELL}.
     */
    private static final long EARTH_CELL = 7_235L;
    private static final long MARS_CELL = 4_512L;

    @Test
    public void theSameTripleInTwoZonesIsTwoDifferentCells() {
        GalacticCoord inEarth = GalacticCoord.inZone(EARTH, EARTH_CELL, 3, 0, 0, 0, 0, 0);
        GalacticCoord inMars = GalacticCoord.inZone(MARS, MARS_CELL, 3, 0, 0, 0, 0, 0);

        assertFalse("two zones' cell 3_0_0 must not be the same cell", inEarth.sameCell(inMars));
        assertNotEquals("nor equal, or a map keyed by one answers for the other", inEarth, inMars);
        assertNotEquals("and their keys must differ", inEarth.cellKey(), inMars.cellKey());
    }

    @Test
    public void aZonedCellIsNotTheGalacticCellOfTheSameNumbers() {
        GalacticCoord galactic = GalacticCoord.ofSectorLocal(3, 0, 0, 0, 0, 0);
        GalacticCoord zoned = GalacticCoord.inZone(EARTH, EARTH_CELL, 3, 0, 0, 0, 0, 0);

        assertNull("the galactic lattice is the null zone", galactic.zone());
        assertEquals("and a zoned coordinate remembers whose lattice it is in", EARTH, zoned.zone());
        assertFalse("so they are not the same cell", galactic.sameCell(zoned));
    }

    @Test
    public void theKeyRoundTripsThroughItsOwnFormat() {
        GalacticCoord zoned = GalacticCoord.inZone(EARTH, EARTH_CELL, 3, -2, 7, 0, 0, 0);
        String key = zoned.cellKey();

        assertEquals("the key names the parent lattice and then this level",
                EARTH + GalacticCoord.ZONE_SEPARATOR + "3_-2_7", key);
        assertEquals("and reading it back gives the same cell", zoned, GalacticCoord.fromCellKey(key));

        // The pre-zone form must survive untouched: these keys are on disk, in ship-ledger rows and
        // in every `cell_<key>` store folder already written.
        GalacticCoord flat = GalacticCoord.ofSectorLocal(19, 0, 0, 0, 0, 0);
        assertEquals("a galactic key gains no prefix", "19_0_0", flat.cellKey());
        assertEquals("and still round-trips", flat, GalacticCoord.fromCellKey("19_0_0"));
    }

    @Test
    public void theSeparatorIsSafeForTheStoreFolderTheKeyBecomes() {
        // A cell key is used verbatim as a directory name (`cell_<key>`) and read straight back out
        // of it. A separator the filesystem treats as structure would turn one cell's store into a
        // tree, and the read-back would recover a fragment.
        String key = GalacticCoord.inZone(EARTH, EARTH_CELL, 3, 0, 0, 0, 0, 0).cellKey();
        assertFalse("a cell key may not contain a path separator: " + key, key.indexOf('/') >= 0);
        assertFalse("nor a Windows one: " + key, key.indexOf('\\') >= 0);
        assertTrue("and the level separator is what the key actually uses",
                key.indexOf(GalacticCoord.ZONE_SEPARATOR) > 0);
    }

    @Test
    public void movingInsideAZoneStaysInThatZone() {
        GalacticCoord start = GalacticCoord.inZone(EARTH, EARTH_CELL, 0, 0, 0, 0, 0, 0);
        GalacticCoord moved = start.plusLocal(1_000_000L, 0L, 0L);
        GalacticCoord centre = moved.cellCentre();

        assertEquals("a local move keeps the lattice it moved in", EARTH, moved.zone());
        assertEquals("and so does snapping to the cell centre", EARTH, centre.zone());
        assertEquals("and its width, which is what the next move will carry at",
                EARTH_CELL, centre.cellBlocks());
    }

    /**
     * A move inside a zone carries at the ZONE's cell width, not at the galactic one.
     *
     * <p>This is the assertion the whole width field exists for. Carrying at
     * {@link GalacticCoord#CELL} does not fail and does not warn: every offset a zone can hold is
     * far under 16 000 000, so the carry is always zero and every point of the zone collapses onto
     * sector 0 — one cell for the whole sphere of influence, which is "a moon shares its parent's
     * name" back again, one level down.</p>
     */
    @Test
    public void aMoveInsideAZoneCarriesAtTheZonesOwnWidthAndNotTheGalacticOne() {
        GalacticCoord start = GalacticCoord.inZone(EARTH, EARTH_CELL, 0, 0, 0, 0, 0, 0);

        // Ten zone cells along +x. At the galactic width this offset does not carry at all.
        GalacticCoord moved = start.plusLocal(10L * EARTH_CELL, 0L, 0L);

        assertEquals("ten cells along is ten sectors along", 10L, moved.sectorX());
        assertEquals("with nothing left over", 0, moved.localX());
        assertNotEquals("so it is NOT the cell it started in", start.cellKey(), moved.cellKey());

        // The floor-division boundary, which is where a TRUNCATING carry would give the origin cell
        // double width. The canonical range is [-half, width - half), so with an odd width the far
        // face sits one block further out than the near one — uniform cells, not a centred window.
        long farFace = EARTH_CELL - EARTH_CELL / 2L;
        assertEquals("just inside the face is still sector 0", 0L,
                start.plusLocal(farFace - 1L, 0L, 0L).sectorX());
        assertEquals("one block past it is sector 1", 1L,
                start.plusLocal(farFace, 0L, 0L).sectorX());
        assertEquals("and the near face is symmetric about it", -1L,
                start.plusLocal(-EARTH_CELL / 2L - 1L, 0L, 0L).sectorX());
    }

    /**
     * A key does not carry the lattice width, so a coordinate read back from one REFUSES the
     * arithmetic that would need it instead of assuming the galactic width.
     *
     * <p>Refusing is the whole point: assuming would not fail, it would rename the cell. The
     * coordinate still compares, keys and round-trips — those need the name, not the scale.</p>
     */
    @Test
    public void aCoordinateReadFromAKeyRefusesWidthArithmeticRatherThanAssumingOne() {
        GalacticCoord named = GalacticCoord.inZone(EARTH, EARTH_CELL, 3, 0, 0, 0, 0, 0);
        GalacticCoord fromKey = GalacticCoord.fromCellKey(named.cellKey());

        assertEquals("a key names the same cell", named, fromKey);
        assertEquals("but it carries no width", GalacticCoord.WIDTH_UNKNOWN, fromKey.cellBlocks());

        try {
            fromKey.plusLocal(1L, 0L, 0L);
            fail("a carry with no width must refuse, not carry at the galactic width");
        } catch (IllegalStateException expected) {
            assertTrue("the refusal must name what is missing: " + expected.getMessage(),
                    expected.getMessage().contains(EARTH));
        }

        // ...and re-attaching the width from the zone's own body makes it usable again.
        assertEquals("re-attached, it moves at the zone's width", 4L,
                fromKey.inLattice(EARTH_CELL).plusLocal(EARTH_CELL, 0L, 0L).sectorX());
    }

    /**
     * Where a zoned cell sits IN THE GALAXY is a question about its zone, never about its own triple.
     *
     * <p>Everything that attributes a cell to a system — the anchor lookup, the neighbourhood box —
     * counts galactic cells. A zone-local index is a count of cells four orders of magnitude smaller,
     * so reading one as galactic answers about a place thousands of light years away while looking
     * perfectly well-formed.</p>
     */
    @Test
    public void aZonedCellAnswersAboutTheGalaxyThroughItsZone() {
        GalacticCoord zoned = GalacticCoord.inZone(EARTH, EARTH_CELL, 500, -3, 0, 0, 0, 0);

        assertEquals("the galactic cell is the zone's, not this level's",
                EARTH, zoned.galacticCell().cellKey());
        GalacticCoord flat = GalacticCoord.ofSectorLocal(19, 0, 0, 0, 0, 0);
        assertEquals("and a galactic cell is already its own", flat, flat.galacticCell());
    }

    /**
     * There is no static-frame distance between cells of two different lattices, and asking for one
     * is refused rather than answered.
     *
     * <p>A sector index is a count of ITS lattice's cells. Subtracting a zone-local index from a
     * galactic one and scaling the difference by either width produces a number with no referent —
     * and it is the kind of number that gets compared to a threshold and acted on.</p>
     */
    @Test
    public void thereIsNoStaticDistanceAcrossTwoLattices() {
        GalacticCoord galactic = GalacticCoord.ofSectorLocal(19, 0, 0, 0, 0, 0);
        GalacticCoord zoned = GalacticCoord.inZone(EARTH, EARTH_CELL, 1, 0, 0, 0, 0, 0);

        try {
            galactic.staticFrameDistanceTo(zoned);
            fail("a distance across two lattices must refuse");
        } catch (IllegalArgumentException expected) {
            // the message names both lattices; what matters is that no number came back
        }

        // Inside ONE zone it is a real reading, taken at that zone's width.
        GalacticCoord alsoZoned = GalacticCoord.inZone(EARTH, EARTH_CELL, 4, 0, 0, 0, 0, 0);
        assertEquals("three zone cells apart is three widths of blocks",
                3d * EARTH_CELL, zoned.staticFrameDistanceTo(alsoZoned), 1d);
    }
}
