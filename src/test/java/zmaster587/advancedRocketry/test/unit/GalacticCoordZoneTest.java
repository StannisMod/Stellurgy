package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.space.GalacticCoord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A sector triple means nothing without the lattice it is expressed in.
 *
 * <p>C15 ADDR-19 makes a zone's cell size a property of the zone, so the same triple denotes
 * different places in different zones. Before this, {@code GalacticCoord} had one lattice and the
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

    @Test
    public void theSameTripleInTwoZonesIsTwoDifferentCells() {
        GalacticCoord inEarth = GalacticCoord.inZone(EARTH, 3, 0, 0, 0, 0, 0);
        GalacticCoord inMars = GalacticCoord.inZone(MARS, 3, 0, 0, 0, 0, 0);

        assertFalse("two zones' cell 3_0_0 must not be the same cell", inEarth.sameCell(inMars));
        assertNotEquals("nor equal, or a map keyed by one answers for the other", inEarth, inMars);
        assertNotEquals("and their keys must differ", inEarth.cellKey(), inMars.cellKey());
    }

    @Test
    public void aZonedCellIsNotTheGalacticCellOfTheSameNumbers() {
        GalacticCoord galactic = GalacticCoord.ofSectorLocal(3, 0, 0, 0, 0, 0);
        GalacticCoord zoned = GalacticCoord.inZone(EARTH, 3, 0, 0, 0, 0, 0);

        assertNull("the galactic lattice is the null zone", galactic.zone());
        assertEquals("and a zoned coordinate remembers whose lattice it is in", EARTH, zoned.zone());
        assertFalse("so they are not the same cell", galactic.sameCell(zoned));
    }

    @Test
    public void theKeyRoundTripsThroughItsOwnFormat() {
        GalacticCoord zoned = GalacticCoord.inZone(EARTH, 3, -2, 7, 0, 0, 0);
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
        String key = GalacticCoord.inZone(EARTH, 3, 0, 0, 0, 0, 0).cellKey();
        assertFalse("a cell key may not contain a path separator: " + key, key.indexOf('/') >= 0);
        assertFalse("nor a Windows one: " + key, key.indexOf('\\') >= 0);
        assertTrue("and the level separator is what the key actually uses",
                key.indexOf(GalacticCoord.ZONE_SEPARATOR) > 0);
    }

    @Test
    public void movingInsideAZoneStaysInThatZone() {
        GalacticCoord start = GalacticCoord.inZone(EARTH, 0, 0, 0, 0, 0, 0);
        GalacticCoord moved = start.plusLocal(1_000_000L, 0L, 0L);
        GalacticCoord centre = moved.cellCentre();

        assertEquals("a local move keeps the lattice it moved in", EARTH, moved.zone());
        assertEquals("and so does snapping to the cell centre", EARTH, centre.zone());
    }
}
