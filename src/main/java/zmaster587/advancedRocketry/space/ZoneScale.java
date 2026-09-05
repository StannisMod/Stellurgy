package zmaster587.advancedRocketry.space;

import zmaster587.advancedRocketry.universe.SystemBody;

/**
 * How big a cell is INSIDE a body's zone.
 *
 * <p>A zone's extent is its body's sphere of influence, which is live at a tick; a cell is the
 * granularity at which that region is realized and named. Those are different quantities and this
 * class owns the second one.</p>
 *
 * <h2>Why the cell size cannot be one global constant — twice over</h2>
 *
 * <p><b>Not the galactic one.</b> A body is NAMED by the cell it occupies in its parent's zone.
 * With a single lattice at {@code GalacticCoord.CELL} that naming collapses at the first step:
 * Earth's whole sphere of influence is <b>0.23</b> of one such cell, so its local lattice would
 * hold exactly ONE cell, Luna would be named by that same cell, and "a moon shares its parent's
 * name" would come back through the door it was shown out of.</p>
 *
 * <p><b>And not a global COUNT either</b>, which is the subtler half and cost a shipped defect. A
 * cell is also the region a craft flies in before a seam moves it, so how many cells a zone holds
 * is bounded from ABOVE by the body's own descent shell and from BELOW by its innermost moon's
 * orbit — and those two bounds sit at different places in every zone. The count is therefore
 * derived per body ({@link #cellsAcrossZone}), never chosen once.</p>
 *
 * <p>Pure arithmetic on bodies and a tick: no world, no registry, no client state.</p>
 */
public final class ZoneScale {

    private ZoneScale() {
    }

    /**
     * How many cells span {@code body}'s zone — <b>derived from the body, not a constant</b>, and
     * squeezed between two bounds that pull opposite ways.
     *
     * <p><b>B, the upper bound — a body's own descent shell must fit inside its own cell.</b> A cell
     * is not only a name: it is the region a craft flies in before a seam carries it to the next one
     * ({@code CellWorldMapper} maps a cell-local offset to a slot-world pose one for one). So a body
     * whose shell reaches past its own cell has a craft at that shell sitting in a NEIGHBOURING
     * cell, which rides the body's PARENT — the craft is then carried by the wrong thing while
     * being, physically, right beside the body. That gives {@code count <= span / (2 * shell)}.</p>
     *
     * <p><b>A, the lower bound — the innermost moon must land on an index of its own</b>, or "a moon
     * shares its parent's name" comes back one level down. Floor division puts a body at index 1
     * once it is half a cell out, so this is {@code count >= span / (2 * orbit)}.</p>
     *
     * <p><b>Taking B and not A is what makes this body-only.</b> A depends on the zone's CHILDREN, so
     * deriving from it would make a body's name a function of its siblings — add a moon in XML and
     * every sibling is renamed. B depends on the body alone, and it satisfies A <i>by
     * construction</i>: at {@code count = B} a cell is about twice the shell, so a moon lands on its
     * own index once it orbits outside its parent's shell — and every moon does, because
     * {@code SystemContent} floors a moon at 2.5 parent radii while a shell is 1.016 of one. The
     * naming bound is therefore met without ever being consulted.</p>
     *
     * <p><b>Measured, at the shipped metric</b> — {@code A}, {@code B} and what this picks:</p>
     *
     * <table>
     *   <tr><th>zone of</th><th>span (blocks)</th><th>A</th><th>B</th><th>picked</th><th>cell</th></tr>
     *   <tr><td>Luna (no moons)</td><td>528 000</td><td>1</td><td>37</td><td>32</td><td>16 500</td></tr>
     *   <tr><td>Earth</td><td>7 408 000</td><td>3</td><td>143</td><td>128</td><td>57 875</td></tr>
     *   <tr><td>Mars</td><td>4 616 000</td><td>62</td><td>167</td><td>128</td><td>36 063</td></tr>
     *   <tr><td>Jupiter</td><td>32 000 000</td><td>32</td><td>55</td><td>32</td><td>1 000 000</td></tr>
     *   <tr><td>Saturn</td><td>32 000 000</td><td>30</td><td>65</td><td>64</td><td>500 000</td></tr>
     *   <tr><td>Neptune</td><td>32 000 000</td><td>83</td><td>159</td><td>128</td><td>250 000</td></tr>
     * </table>
     *
     * <p><b>What this replaces, and why that number was wrong everywhere.</b> It was a flat
     * {@code 1024}, derived from A alone and — the actual error — computed as the moon's orbit
     * against the body's UNCLAMPED sphere of influence rather than against the span the zone is
     * realized over. For a giant those differ by the clamp, six to twelve times, so the bound came
     * out six to twelve times too tight; and B had not been noticed at all. The result was finer
     * than B in EVERY zone, and in the home system by a factor of two: Earth's cell came out 7 235
     * blocks against Luna's own 7 059-block shell, so a craft parked one shell off Luna fell into
     * the next cell and was carried by Earth. That is the whole defect this class exists to remove,
     * surviving inside the fix for it.</p>
     *
     * <p>A power of two is not cosmetic: the lattice index is a division, and a body that sits
     * exactly on a cell boundary is a body whose name depends on a rounding mode.</p>
     */
    public static int cellsAcrossZone(SystemBody body, SystemBody primary, long tick) {
        long span = 2L * realizedRadiusBlocks(body, primary, tick);
        if (span <= 0L) {
            return 0;
        }
        long shell = DescentShell.radiusAround(body);
        long bound = span / (2L * Math.max(1L, shell));
        int count = 1;
        while ((long) count * 2L <= bound) {
            count *= 2;
        }
        return count;
    }

    /**
     * The edge length, in chart blocks, of one cell inside {@code body}'s zone at {@code tick} —
     * {@link #realizedRadiusBlocks the span} divided by {@link #cellsAcrossZone the count}.
     *
     * <p>{@code 0} when the body has no zone to divide (see the span). A zero is NOT a small cell:
     * callers must read it as "this body has no lattice of its own" and pass over it. Handing back a
     * stand-in would produce names for a zone that does not exist.</p>
     */
    public static long cellBlocks(SystemBody body, SystemBody primary, long tick) {
        long radius = realizedRadiusBlocks(body, primary, tick);
        int count = cellsAcrossZone(body, primary, tick);
        if (radius <= 0L || count <= 0) {
            return 0L;
        }
        // Rounded UP, so a lattice is never finer than the count asks for; a cell of zero blocks
        // would divide by zero at every naming site, which is a crash rather than a wrong name only
        // because nothing has called it yet.
        return Math.max(1L, (long) Math.ceil(2d * radius / count));
    }

    /**
     * How far {@code body}'s zone is REALIZED, in blocks — the radius the lattice spans.
     *
     * <p>{@code 0} when the body has no zone to divide, which is exactly one case: it has no MASS.
     * A zero is NOT a small zone — callers must read it as "this body has no lattice of its own" and
     * pass over it, exactly as {@link ReferenceFrames#soiRadiusBlocks} requires of its own zero.</p>
     *
     * <p>The sphere of influence, capped at what one cell can hold. These are different quantities
     * and only for the small bodies do they coincide: measured against
     * {@code HALF_CELL = 16 000 000}, Luna's sphere is 0.02 of it and Earth's 0.23, but Jupiter's is
     * <b>12&times;</b> and Neptune's <b>21.65&times;</b>. A lattice reference-spanning twenty times
     * what can be realized would name most of its cells after places no slot world reaches. The cap
     * is on the REALIZATION, never on the extent — station-keeping still ends at a property of the
     * body.</p>
     *
     * <p>A body with <b>no primary</b> — a rogue, which left its star behind — has no Laplace sphere
     * for the first term to exist, so its bound is the realized one alone. That is the same rule
     * with a term absent, not a special case: a rogue's moons get their own cells exactly as a
     * star-lit planet's do, where reading the missing sphere as "no zone" would leave them sharing
     * their parent's address.</p>
     */
    public static long realizedRadiusBlocks(SystemBody body, SystemBody primary, long tick) {
        if (body == null || !(body.massEarths() > 0d)) {
            return 0L;
        }
        double soi = ReferenceFrames.soiRadiusBlocks(body, primary, tick);
        return (long) (soi > 0d ? Math.min(soi, GalacticCoord.HALF_CELL) : GalacticCoord.HALF_CELL);
    }

    /**
     * Which cell of {@code body}'s zone lattice a point {@code offsetBlocks} from that body falls in,
     * along one axis.
     *
     * <p>Floor division, so the lattice is uniform across zero rather than having a double-width cell
     * at the origin — the trap of truncating division, which maps both {@code -0.5} and {@code +0.5}
     * of a cell onto index 0 and makes the body's own cell twice its neighbours.</p>
     */
    public static long cellIndex(long offsetBlocks, long cellBlocks) {
        if (cellBlocks <= 0L) {
            return 0L;
        }
        return Math.floorDiv(offsetBlocks + cellBlocks / 2L, cellBlocks);
    }

    /**
     * The cell of {@code zoneBody}'s lattice that a point {@code offset} from that body falls in — as
     * a NAME, in the zone whose key is {@code zoneBody}'s own cell.
     *
     * <p>{@code null} when {@code zoneBody} defines no zone, and the caller must say what it does
     * about that rather than being handed a plausible cell: a stand-in here is a body named inside a
     * lattice that does not exist, which is indistinguishable from a real address at every point
     * downstream.</p>
     *
     * @param primary the body {@code zoneBody} orbits — its sphere of influence is measured against it
     * @param tick    the moment the zone's extent, and hence its lattice, is evaluated at
     */
    public static GalacticCoord cellWithin(SystemBody zoneBody, SystemBody primary, BlockDelta offset,
                                           long tick) {
        if (zoneBody == null || offset == null) {
            return null;
        }
        long width = cellBlocks(zoneBody, primary, tick);
        if (width <= 0L) {
            return null;
        }
        return GalacticCoord.inZone(zoneBody.name().cellKey(), width,
                cellIndex(offset.dx(), width), cellIndex(offset.dy(), width),
                cellIndex(offset.dz(), width), 0L, 0L, 0L);
    }
}
