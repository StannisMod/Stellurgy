package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.space.ZoneScale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The one thing a zone's lattice has to do: <b>give two bodies two names.</b>
 *
 * <p>A body is named by the cell it occupies in its parent's zone, so a lattice too coarse for the
 * gap between a planet and its innermost moon names them both by the same cell — and "a moon shares
 * its parent's name" returns through the back door, taking the
 * defect this whole change exists to remove with it: a craft parked beside the moon is then carried
 * by the PARENT and left behind by the moon.</p>
 *
 * <p><b>Why these assertions can fail.</b> The single global lattice they replace DOES produce the
 * collapse — Earth's whole sphere of influence is 0.23 of one {@code GalacticCoord.CELL}, so its
 * local lattice held exactly one cell. Every case below is a real moon, and the tightest of them is
 * what {@link ZoneScale#CELLS_ACROSS_A_ZONE} is derived from rather than chosen against.</p>
 *
 * <p>Pure arithmetic — no bodies are constructed, because the lattice is a function of ONE number
 * (the sphere of influence in blocks) and building a `SystemBody` to carry it would test the fixture
 * rather than the law.</p>
 */
public class ZoneScaleTest {

    /** Metres per chart block — the metric, restated here only to convert the reference values. */
    private static final double D = 250d;

    private static long blocks(double km) {
        return Math.round(km * 1000d / D);
    }

    /** The lattice cell for a zone of the given SOI radius, without needing a body to carry it. */
    private static long cellFor(double soiRadiusKm) {
        return Math.max(1L, (long) Math.ceil(
                2d * blocks(soiRadiusKm) / ZoneScale.CELLS_ACROSS_A_ZONE));
    }

    /**
     * Every real moon lands in a cell that is not its planet's own — which is the whole contract.
     *
     * <p>Pan is the tightest case in the system and therefore the one that sets the constant; it is
     * asserted alongside the others rather than alone, because a lattice sized for Pan and wrong for
     * Luna would pass a single-case test.</p>
     */
    @Test
    public void everyRealMoonGetsACellOfItsOwn() {
        double[][] cases = {
                // { moon orbit km, planet SOI radius km }
                {384_400, 926_000},        // Luna / Earth
                {9_376, 577_000},          // Phobos / Mars
                {421_700, 48_200_000},     // Io / Jupiter
                {128_000, 48_200_000},     // Metis / Jupiter — innermost Jovian
                {129_900, 51_800_000},     // Miranda / Uranus
                {133_580, 54_500_000},     // Pan / Saturn — the tightest there is
        };
        for (double[] c : cases) {
            long cell = cellFor(c[1]);
            long index = ZoneScale.cellIndex(blocks(c[0]), cell);
            assertTrue("a moon orbiting " + (long) c[0] + " km out of a " + (long) c[1]
                            + " km sphere shares its planet's cell: lattice cell = " + cell
                            + " blocks, index = " + index + ". The planet sits at index 0, so this "
                            + "moon has no name of its own, and it is sharing its planet's.",
                    index != 0L);
        }
    }

    /**
     * The lattice is uniform across zero — the body's own cell is not twice its neighbours'.
     *
     * <p>Truncating division would map both −0.5 and +0.5 of a cell onto index 0. That is not a
     * rounding nicety: a double-width central cell is exactly where a planet sits, so it would widen
     * the one cell that must not swallow its moons.</p>
     */
    @Test
    public void theCentralCellIsNotWiderThanTheRest() {
        long cell = 1_000L;
        assertEquals("just inside the central cell, positive side", 0L, ZoneScale.cellIndex(499L, cell));
        assertEquals("just inside the central cell, negative side", 0L, ZoneScale.cellIndex(-500L, cell));
        assertEquals("one block further out is the next cell", 1L, ZoneScale.cellIndex(500L, cell));
        assertEquals("and symmetrically on the other side", -1L, ZoneScale.cellIndex(-501L, cell));
    }

    /**
     * A zone that does not exist has no lattice, and says so with a zero rather than a stand-in.
     *
     * <p>A body with no mass defines no sphere of influence, and a small-but-nonzero
     * cell handed back here would be indistinguishable from a real lattice at every call site — the
     * naming would succeed and produce an address for a zone nobody owns.</p>
     */
    @Test
    public void noZoneMeansNoLatticeAndTheZeroIsTheAnswer() {
        assertEquals("a body with no sphere of influence has no lattice",
                0L, ZoneScale.cellBlocks(null, null, 0L));
        assertEquals("and an index against no lattice is not invented", 0L,
                ZoneScale.cellIndex(1_234_567L, 0L));
    }
}
