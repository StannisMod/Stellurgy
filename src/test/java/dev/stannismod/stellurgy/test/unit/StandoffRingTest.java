package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.space.StandoffRing;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Contract tests for where a craft is put down NEAR something.
 *
 * <p>Three properties are the contract and the radius is not: a placement never leaves the anchor's
 * cell, is never further out than asked, and two craft placed at one anchor do not share a point. The
 * ring distance itself is {@code tunable} and is deliberately never pinned to a number.</p>
 */
public class StandoffRingTest {

    private static final long RING = 1024L;

    private static GalacticCoord at(long sx, long sy, long sz, long lx, long ly, long lz) {
        return GalacticCoord.ofSectorLocal(sx, sy, sz, lx, ly, lz);
    }

    /**
     * A placement may not rename the cell. An offset that carries into the next sector puts the craft
     * in a cell nobody materialized, nobody bound to a slot world and nobody told the ledger about —
     * so at a cell face the ring gives up distance, never identity.
     */
    @Test
    public void aRingAtTheCellFaceKeepsTheCellName() {
        GalacticCoord atFace = at(5, 1, -9, GalacticCoord.HALF_CELL - 1L, 0L, GalacticCoord.HALF_CELL - 1L);

        for (int seed = 0; seed < 256; seed += 7) {
            GalacticCoord placed = StandoffRing.pointAround(atFace, RING, seed);
            assertEquals("a ring around a body at its cell's face stays in that cell (seed " + seed + ")",
                    atFace.cellKey(), placed.cellKey());
        }
    }

    /**
     * The placement is never FURTHER out than it was asked for. A caller that also uses the radius as
     * a threshold — the survey tier is exactly the entry ring today — would otherwise land on one
     * side of its own boundary or the other depending on the bearing, which reads as a flickering
     * readout with no cause.
     */
    @Test
    public void aPlacementIsNeverFurtherOutThanTheRadiusAsked() {
        GalacticCoord anchor = at(1, 1, 1, 0, 0, 0);

        for (int seed = 0; seed < 256; seed++) {
            GalacticCoord placed = StandoffRing.pointAround(anchor, RING, seed);
            assertTrue("bearing " + seed + " overshot the ring: " + placed.staticFrameDistanceTo(anchor),
                    placed.staticFrameDistanceTo(anchor) <= RING);
        }
    }

    /**
     * Two craft placed at one body in the same tick do not share a point. Not a fairness nicety: two
     * ships pasted at identical coordinates are two ships in the same blocks.
     */
    @Test
    public void twoCraftAtOneBodyGetDifferentPoints() {
        GalacticCoord body = at(9, 0, 9, 0, 0, 0);

        assertTrue("different seeds must give different points",
                !StandoffRing.pointAround(body, RING, 0x10)
                        .equals(StandoffRing.pointAround(body, RING, 0x90)));
    }
}
