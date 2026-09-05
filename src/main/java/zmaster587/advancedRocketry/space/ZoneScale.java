package zmaster587.advancedRocketry.space;

import zmaster587.advancedRocketry.universe.SystemBody;

/**
 * How big a cell is INSIDE a body's zone.
 *
 * <p>A zone's extent is its body's sphere of influence, which is live at a tick; a cell is the
 * granularity at which that region is realized and named. Those are different quantities and this
 * class owns the second one.</p>
 *
 * <h2>Why the cell size cannot be one global constant</h2>
 *
 * <p>A body is NAMED by the cell it occupies in its parent's zone. With a single lattice at
 * {@code GalacticCoord.CELL} that naming collapses at the first step: Earth's whole sphere of
 * influence is <b>0.23</b> of one such cell, so its local lattice would hold exactly ONE cell, Luna
 * would be named by that same cell, and "a moon shares its parent's name" would come back through the
 * door it was shown out of. The lattice inside a zone is therefore sized to the zone.</p>
 *
 * <p>Pure arithmetic on bodies and a tick: no world, no registry, no client state.</p>
 */
public final class ZoneScale {

    private ZoneScale() {
    }

    /**
     * How many cells span a zone's DIAMETER. {@code tunable}, and derived rather than chosen.
     *
     * <p>The number a zone lattice has to beat is set by the tightest real moon relative to its
     * planet's sphere of influence — that is the closest two bodies ever come while still being two
     * destinations, and a lattice coarser than it names them both by the same cell. Measured on the
     * real system, as {@code orbit / r_SOI} and the {@code 2/f} it implies:</p>
     *
     * <table>
     *   <tr><th>moon</th><th>orbit</th><th>planet r_SOI</th><th>ratio</th><th>cells needed</th></tr>
     *   <tr><td>Luna</td><td>384 400 km</td><td>926 000 km</td><td>0.415</td><td>5</td></tr>
     *   <tr><td>Phobos</td><td>9 376 km</td><td>577 000 km</td><td>0.0163</td><td>123</td></tr>
     *   <tr><td>Io</td><td>421 700 km</td><td>48 200 000 km</td><td>0.00875</td><td>229</td></tr>
     *   <tr><td>Metis</td><td>128 000 km</td><td>48 200 000 km</td><td>0.00266</td><td>753</td></tr>
     *   <tr><td>Miranda</td><td>129 900 km</td><td>51 800 000 km</td><td>0.00251</td><td>798</td></tr>
     *   <tr><td><b>Pan</b></td><td>133 580 km</td><td>54 500 000 km</td><td><b>0.00245</b></td><td><b>816</b></td></tr>
     * </table>
     *
     * <p>816 is the worst case in the system every player meets first, so this is the next power of
     * two above it. A power of two is not cosmetic: the lattice index is a division, and a body that
     * sits exactly on a cell boundary is a body whose name depends on a rounding mode.</p>
     *
     * <p><b>What it costs at the top end</b>: a body whose sphere exceeds one cell has its lattice
     * spanning the realized region instead, so the coarsest cell in the game is
     * {@code CELL / 1024 = 31 250} blocks — Jupiter's, Neptune's and every rogue's. Earth's is 7 235
     * blocks (1 809 km). Earth's own descent shell is 25 513 blocks, so a big body SPANS several
     * cells of its own zone. That is correct and not a defect — a cell is a naming and realization
     * unit, never a claim that the thing inside it is smaller than it.</p>
     */
    public static final int CELLS_ACROSS_A_ZONE = 1024;

    /**
     * The edge length, in chart blocks, of one cell inside {@code body}'s zone at {@code tick}.
     *
     * <p>{@code 0} when the body has no zone to divide, which is exactly one case: it has no MASS.
     * A zero is NOT a small cell — callers must read it as "this body has no lattice of its own" and
     * pass over it, exactly as {@link ReferenceFrames#soiRadiusBlocks} requires of its own zero.
     * Handing back a stand-in would produce names for a zone that does not exist.</p>
     *
     * <h2>The lattice spans the REALIZED region, not the sphere of influence</h2>
     *
     * <p>These are different quantities and only for the small bodies do they coincide. A sphere of
     * influence is where a craft's motion is naturally described against this body; the realized
     * region is how much of that a slot world can hold, which is one cell. Measured against
     * {@code HALF_CELL = 16 000 000}: Luna's sphere is 0.02 of it and Earth's 0.23, so for everything
     * a player lands near the sphere is the binding term — but Jupiter's is <b>12&times;</b> and
     * Neptune's <b>21.65&times;</b>, and a lattice spanning a region twenty times what can be
     * realized would name most of its own cells after places no world reaches.</p>
     *
     * <p>A body with <b>no primary</b> — a rogue, which left its star behind — has no Laplace sphere
     * at all, because there is nothing for one to be bounded against. Its bound is the realized one
     * and nothing else, which is the same rule with the first term absent rather than a special case:
     * a rogue's moons get their own cells exactly as a star-lit planet's do. Reading the missing
     * sphere as "no zone" instead would leave every rogue's moons sharing their parent's address,
     * which is the rule this class exists to retire.</p>
     */
    public static long cellBlocks(SystemBody body, SystemBody primary, long tick) {
        if (body == null || !(body.massEarths() > 0d)) {
            return 0L;
        }
        double soi = ReferenceFrames.soiRadiusBlocks(body, primary, tick);
        double radius = soi > 0d ? Math.min(soi, GalacticCoord.HALF_CELL) : GalacticCoord.HALF_CELL;
        // Rounded UP, so a lattice is never finer than the count asks for; a cell of zero blocks
        // would divide by zero at every naming site, which is a crash rather than a wrong name only
        // because nothing has called it yet.
        return Math.max(1L, (long) Math.ceil(2d * radius / CELLS_ACROSS_A_ZONE));
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
