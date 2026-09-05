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
     * How many cells span {@code body}'s zone — <b>derived, never a constant</b> — as the COARSEST
     * lattice that still gives {@code tightestChildOffsetBlocks} an index of its own.
     *
     * <p>Three bounds act on this number and they do not all pull the same way.</p>
     *
     * <p><b>A, from below — the innermost child must land on an index of its own</b>, or "a moon
     * shares its parent's name" comes back one level down. Floor division puts a body at index 1
     * once it is half a cell out, so {@code count >= span / (2 * orbit)}. <b>This is the one that
     * decides</b>, and the smallest power of two above it is taken: nothing else wants the lattice
     * any finer, and everything wants the cells as large as they can be.</p>
     *
     * <p><b>C, from above — a cell must CONTAIN the sphere of influence of the body it names.</b>
     * A cell is not only a name: it is the region a craft flies in before a seam carries it out
     * ({@code CellWorldMapper} maps a cell-local offset to a slot pose one for one, concentric on
     * the body, because a cell's frame origin IS its body). If a body's sphere reaches past its own
     * cell, the cube face fires before the sphere ever can, and membership can then NEVER be decided
     * by the sphere however the crossing is written. C is satisfied by A automatically:
     * {@code r_SOI = a * (m/M)^(2/5)} is always less than the orbit {@code a}, and A makes the cell
     * about twice that orbit. Measured on the real system, worst case Callisto — 301 383 blocks of
     * sphere against Jupiter's 1 000 000-block cell.</p>
     *
     * <p><b>B — a body's own descent shell must fit inside its own cell</b> — is the same statement
     * as C with a much smaller number (a shell is 1.0157 radii, a sphere is thousands), so anything
     * satisfying C satisfies it. It is stated because it is the bound that FAILED: it was violated
     * in the home system by a factor of two and put a craft parked one shell off Luna in a
     * neighbouring cell riding Earth.</p>
     *
     * <p><b>Measured, at the shipped metric</b>:</p>
     *
     * <table>
     *   <tr><th>zone of</th><th>span (blocks)</th><th>innermost child</th><th>count</th><th>cell</th></tr>
     *   <tr><td>Luna (no moons)</td><td>528 000</td><td>none</td><td>1</td><td>528 000</td></tr>
     *   <tr><td>Earth</td><td>7 408 000</td><td>Luna, 1 537 600</td><td>4</td><td>1 852 000</td></tr>
     *   <tr><td>Mars</td><td>4 616 000</td><td>Phobos, 37 504</td><td>64</td><td>72 125</td></tr>
     *   <tr><td>Jupiter</td><td>32 000 000</td><td>Metis, 512 000</td><td>32</td><td>1 000 000</td></tr>
     * </table>
     *
     * <p><b>A body with no children gets ONE cell</b>, and that is the case the design turns on: for
     * every moon in the game the cell and the sphere of influence become the same region, so "which
     * body carries this craft" and "which cell is it in" cannot disagree.</p>
     *
     * <p><b>What this replaces, twice over.</b> It was a flat {@code 1024}, derived from A alone and
     * — the actual error — computing the child's orbit against the body's UNCLAMPED sphere rather
     * than against the span the zone is realized over, six to twelve times too tight for a giant.
     * Then it was B-driven and body-only, which satisfied A by construction but took the FINEST
     * lattice B allows: it spent the flying room the whole exercise is about and left Luna's cell
     * nine times smaller than Luna's own sphere, so C could not hold and membership could not be
     * decided by geometry. The lower bound is the one that decides; the upper ones are checks.</p>
     *
     * <p>A power of two is not cosmetic: the lattice index is a division, and a body that sits
     * exactly on a cell boundary is a body whose name depends on a rounding mode.</p>
     *
     * @param tightestChildOffsetBlocks how far the INNERMOST body that must be named apart sits from
     *                                  {@code body}, in blocks; {@code 0} when there is none
     */
    public static int cellsAcrossZone(SystemBody body, SystemBody primary,
                                      long tightestChildOffsetBlocks, long tick) {
        long span = 2L * realizedRadiusBlocks(body, primary, tick);
        if (span <= 0L) {
            return 0;
        }
        if (tightestChildOffsetBlocks <= 0L) {
            // NOTHING to name apart, so nothing wants the lattice divided: one cell, the whole zone.
            // This is the common case — every moon in the game — and it is where the design pays off:
            // the cell and the sphere of influence become the same region, so "which body carries
            // this craft" and "which cell is it in" cannot disagree.
            return 1;
        }
        long needed = ceilDiv(span, 2L * tightestChildOffsetBlocks);
        int count = 1;
        while ((long) count < needed) {
            count *= 2;
        }
        return count;
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1L) / b;
    }

    /**
     * The edge length, in chart blocks, of one cell inside {@code body}'s zone at {@code tick} —
     * {@link #realizedRadiusBlocks the span} divided by {@link #cellsAcrossZone the count}.
     *
     * <p>{@code 0} when the body has no zone to divide (see the span). A zero is NOT a small cell:
     * callers must read it as "this body has no lattice of its own" and pass over it. Handing back a
     * stand-in would produce names for a zone that does not exist.</p>
     */
    public static long cellBlocks(SystemBody body, SystemBody primary,
                                  long tightestChildOffsetBlocks, long tick) {
        long radius = realizedRadiusBlocks(body, primary, tick);
        int count = cellsAcrossZone(body, primary, tightestChildOffsetBlocks, tick);
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
    /**
     * The full ADDRESS a craft {@code offset} from {@code zoneBody} has inside that body's zone —
     * the cell it falls in AND where it stands inside that cell.
     *
     * <p>{@link #cellWithin} answers the first half and is the right call for NAMING a body, which
     * sits at its own cell's centre by construction. A craft does not: it is somewhere in a cell,
     * and a re-address that dropped the remainder would move it to the nearest cell centre — up to
     * half a cell, which in Earth's zone is 924 647 blocks of silent teleport at the moment a craft
     * crosses a sphere.</p>
     *
     * <p>{@code null} when the body defines no zone, for the same reason and with the same duty on
     * the caller as {@link #cellWithin}.</p>
     */
    public static GalacticCoord addressWithin(SystemBody zoneBody, SystemBody primary,
                                              BlockDelta offset, long tightestChildOffsetBlocks,
                                              long tick) {
        if (zoneBody == null || offset == null) {
            return null;
        }
        long width = cellBlocks(zoneBody, primary, tightestChildOffsetBlocks, tick);
        if (width <= 0L) {
            return null;
        }
        long ix = cellIndex(offset.dx(), width);
        long iy = cellIndex(offset.dy(), width);
        long iz = cellIndex(offset.dz(), width);
        return GalacticCoord.inZone(zoneBody.name().cellKey(), width, ix, iy, iz,
                offset.dx() - ix * width, offset.dy() - iy * width, offset.dz() - iz * width);
    }

    public static GalacticCoord cellWithin(SystemBody zoneBody, SystemBody primary, BlockDelta offset,
                                           long tightestChildOffsetBlocks, long tick) {
        if (zoneBody == null || offset == null) {
            return null;
        }
        long width = cellBlocks(zoneBody, primary, tightestChildOffsetBlocks, tick);
        if (width <= 0L) {
            return null;
        }
        return GalacticCoord.inZone(zoneBody.name().cellKey(), width,
                cellIndex(offset.dx(), width), cellIndex(offset.dy(), width),
                cellIndex(offset.dz(), width), 0L, 0L, 0L);
    }
}
