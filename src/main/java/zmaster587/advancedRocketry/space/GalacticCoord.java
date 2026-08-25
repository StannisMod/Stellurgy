package zmaster587.advancedRocketry.space;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Absolute galactic position as a <b>sectorized fixed-point</b> coordinate - the exact-integer
 * absolute frame for the movable-ship space subsystem. Decoupled from (and NOT to be confused with)
 * the legacy {@code SpacePosition}, which is the {@code double} solar-map render coordinate.
 *
 * <p>Each axis is a {@code long sector} index plus a local block offset, with
 * {@code absolute = sector * CELL + local}. Integer arithmetic gives uniform precision at any
 * magnitude and does not drift when a position is integrated over a long automatic transit - unlike
 * a {@code double}, whose spacing grows with magnitude (~220&nbsp;km per ULP at galactic scale).</p>
 *
 * <p>The sector grid <i>is</i> the bubble grid: a cell is one {@link #CELL}-block cube, so two
 * positions with equal sector triples share a cell (and, once loaded, the same world). The local
 * offset is kept canonical in {@code [-HALF_CELL, HALF_CELL)}, i.e. within &plusmn;16M blocks of the
 * cell centre. Cell-centre content is at local {@code (0,0,0)}.</p>
 *
 * <p><b>The sector triple is a cell NAME, not a place.</b> A cell rides the body it belongs
 * to, so {@code absolute = sector * CELL + local} is the STATIC-frame reading — true for a void cell
 * and for the sector arithmetic that keeps names apart, and false the moment either endpoint's frame
 * has moved. Hence {@link #staticFrameDistanceTo}'s spelling, and hence {@link AbsolutePos}, which is
 * what "where this is, at tick t" is expressed in.</p>
 *
 * <p>Immutable value type. Proximity within one cell is computed on the (small, near) local delta cast
 * to {@code double} via {@link #staticFrameDistanceSqTo(GalacticCoord)} - precise because the delta
 * between nearby positions is small even though the absolute magnitudes are huge.</p>
 */
public final class GalacticCoord {

    /**
     * Edge length of one cell / sector, in blocks. The sector grid is the bubble grid.
     *
     * <p><b>Why 32M and not the 4M this started at.</b> The old size rested on one sentence — that
     * entity doubles, chunks and lighting degrade past ~&plusmn;2M in 1.12.2 — and all three named
     * mechanisms were measured CLEAN out to 24M, on a real player and a real flying ship: walking
     * distance, collision stand-off, standing, client/server agreement, camera-step granularity and a
     * sub-block position round trip, each against an origin control in the same run. The wall that
     * actually existed was a mod constant (the physics mod's reserved shipyard quadrant), and it is
     * moved out of the way by {@code ShipChunkAllocator.CHUNK_X_START}, which this size is paired
     * with: the two must move together or a pose past the old quadrant is silently cancelled.</p>
     *
     * <p>16M of half-cell against 24M measured clean is 1.5&times; margin. What is NOT covered:
     * vanilla documents sound-positioning degradation at 2&sup2;&#8308; = 16 777 216, which the far
     * shell of this cell crosses — accepted knowingly, and cosmetic.</p>
     *
     * <p>The size is what lets a system fit inside its own cell at the chart metric the mod already
     * ships ({@code AstronomicalBodyHelper.METRES_PER_CHART_BLOCK}): at 250 m/block Jupiter's outer
     * moons sit ~7.5M blocks out, which is under half of this half-cell and nearly four times the
     * old one.</p>
     */
    public static final long CELL = 32_000_000L;

    /** Half a cell; the canonical local offset lives in {@code [-HALF_CELL, HALF_CELL)}. */
    public static final long HALF_CELL = CELL / 2L;

    /** Absolute origin: sector {@code (0,0,0)}, local {@code (0,0,0)}. */
    public static final GalacticCoord ORIGIN = new GalacticCoord(0L, 0L, 0L, 0, 0, 0);

    private final long sectorX;
    private final long sectorY;
    private final long sectorZ;
    private final int localX; // canonical: [-HALF_CELL, HALF_CELL)
    private final int localY;
    private final int localZ;

    /**
     * The zone whose lattice this coordinate's sector triple is expressed in — the parent's own cell
     * key — or {@code null} for the GALACTIC lattice, which is the outermost and has no parent.
     *
     * <p>A sector triple means nothing on its own: C15 ADDR-19 makes a zone's cell size a property of
     * the zone, so the same triple denotes different places in different lattices. Two coordinates in
     * different zones are therefore never the same cell, however their numbers compare — which is why
     * this participates in {@link #equals} and {@link #sameCell} rather than being decoration on the
     * key.</p>
     *
     * <p>The galactic lattice is the null path, so every coordinate written before zones existed
     * keeps its exact meaning.</p>
     */
    private final String zone;

    private GalacticCoord(long sectorX, long sectorY, long sectorZ, int localX, int localY, int localZ) {
        this(null, sectorX, sectorY, sectorZ, localX, localY, localZ);
    }

    private GalacticCoord(String zone, long sectorX, long sectorY, long sectorZ,
                          int localX, int localY, int localZ) {
        this.zone = zone;
        this.sectorX = sectorX;
        this.sectorY = sectorY;
        this.sectorZ = sectorZ;
        this.localX = localX;
        this.localY = localY;
        this.localZ = localZ;
    }

    /**
     * Build from a sector triple and a (possibly out-of-range) local offset triple, renormalising the
     * local offsets into {@code [-HALF_CELL, HALF_CELL)} and carrying the overflow into the sectors.
     */
    public static GalacticCoord ofSectorLocal(long sectorX, long sectorY, long sectorZ,
                                              long localX, long localY, long localZ) {
        return inZone(null, sectorX, sectorY, sectorZ, localX, localY, localZ);
    }

    /**
     * The same, in the lattice of {@code zone} — the parent's cell key, or {@code null} for the
     * galactic lattice.
     *
     * <p>The carry still uses the GLOBAL {@link #CELL}. That is a deliberate first step and not the
     * finished state: C15 ADDR-19 makes the cell size a property of the zone, so a zone-local lattice
     * will carry at its own width. Until every construction site can supply that width, a zoned
     * coordinate normalises at the global one — exact for the galactic lattice, and what every
     * existing caller already gets.</p>
     */
    public static GalacticCoord inZone(String zone, long sectorX, long sectorY, long sectorZ,
                                       long localX, long localY, long localZ) {
        long carryX = Math.floorDiv(localX + HALF_CELL, CELL);
        long carryY = Math.floorDiv(localY + HALF_CELL, CELL);
        long carryZ = Math.floorDiv(localZ + HALF_CELL, CELL);
        return new GalacticCoord(zone,
                sectorX + carryX, sectorY + carryY, sectorZ + carryZ,
                (int) (localX - carryX * CELL),
                (int) (localY - carryY * CELL),
                (int) (localZ - carryZ * CELL));
    }

    /** The zone this coordinate's lattice belongs to, or {@code null} for the galactic one. */
    public String zone() {
        return zone;
    }

    /**
     * Build from an absolute block coordinate triple. Precise up to the {@code long} range of the
     * absolute value; beyond that the caller must supply sectors directly via
     * {@link #ofSectorLocal(long, long, long, long, long, long)}.
     */
    public static GalacticCoord ofAbsolute(long absX, long absY, long absZ) {
        return ofSectorLocal(0L, 0L, 0L, absX, absY, absZ);
    }

    public long sectorX() { return sectorX; }
    public long sectorY() { return sectorY; }
    public long sectorZ() { return sectorZ; }

    public int localX() { return localX; }
    public int localY() { return localY; }
    public int localZ() { return localZ; }

    // absoluteX/Y/Z — sector * CELL + local — are gone. A sector index reaches 9.2e18 while the
    // product overflows at 2.9e11, so they could NAME a position they could not express, silently,
    // over seven orders of magnitude. Nothing materialises a single global block absolute any more:
    // a distance comes from the sector delta plus the offset delta (staticFrameDistanceTo below, or
    // AbsolutePos for a position at a tick), which is exact nearby and cannot overflow far away.

    /** {@code true} iff {@code other} is in the same cell (equal sector triple) as this coordinate. */
    public boolean sameCell(GalacticCoord other) {
        return java.util.Objects.equals(zone, other.zone)
                && sectorX == other.sectorX && sectorY == other.sectorY && sectorZ == other.sectorZ;
    }

    /**
     * The centre of this coordinate's cell (local offsets zeroed), keeping the sector triple. This is
     * where precision-critical content (stations, docking) is snapped so it never carries float jitter.
     */
    public GalacticCoord cellCentre() {
        return new GalacticCoord(zone, sectorX, sectorY, sectorZ, 0, 0, 0);
    }

    /**
     * This coordinate shifted by a local block delta, renormalised. The unit step of transit
     * integration: repeatedly adding a per-tick velocity vector never drifts (exact integer carry).
     */
    public GalacticCoord plusLocal(long dx, long dy, long dz) {
        return inZone(zone, sectorX, sectorY, sectorZ, localX + dx, localY + dy, localZ + dz);
    }

    /**
     * This coordinate shifted by a local block delta, <b>saturated inside its own cell</b> instead of
     * carrying into a neighbouring sector.
     *
     * <p>Use this wherever the cell is already the answer and the offset is only a position within
     * it: a placement ring, a flight clamp, anything that has decided which cell it is talking about.
     * {@link #plusLocal} is the opposite tool &mdash; it is for INTEGRATING a path, where crossing
     * into the next sector is the whole point.</p>
     *
     * <p>Choosing the wrong one is not a rounding difference. A carried offset renames the cell, and
     * the caller is then holding a coordinate in a cell nobody materialized, nobody bound to a slot
     * world, and nobody told the ledger about. Saturating costs at most the few blocks by which the
     * offset would have overshot the cell face.</p>
     */
    public GalacticCoord plusLocalSaturating(long dx, long dy, long dz) {
        return new GalacticCoord(sectorX, sectorY, sectorZ,
                (int) saturateLocal(localX + dx),
                (int) saturateLocal(localY + dy),
                (int) saturateLocal(localZ + dz));
    }

    /** {@code true} iff {@code local} is a canonical in-cell offset, i.e. one that would not carry. */
    public static boolean localWithinCell(long local) {
        return local >= -HALF_CELL && local < HALF_CELL;
    }

    /** {@code local} held inside {@code [-HALF_CELL, HALF_CELL)} — the range that does not carry. */
    private static long saturateLocal(long local) {
        if (local >= HALF_CELL) {
            return HALF_CELL - 1L;
        }
        return local < -HALF_CELL ? -HALF_CELL : local;
    }

    /**
     * Squared distance to {@code other} read over the STATIC grid, in blocks&sup2;, as a
     * {@code double}. Computed from the sector delta plus the local delta so nearby positions are
     * exact even at galactic magnitude.
     *
     * <p><b>This is not the distance between two bodies.</b> A sector triple is a cell NAME, and every
     * cell with a primary rides it, so {@code sector * CELL} is where a cell would be if nothing
     * moved. The reading is exact in exactly two cases: <i>within one cell</i>, where both endpoints
     * share a frame and the sector terms cancel; and <i>between two static frames</i> — void cells, or
     * a star, whose frames really are at {@code sector * CELL} forever. For anything else, go through
     * {@link CellFrames#distanceBetween}: the same two names can be a light-second apart at one tick
     * and a system's width apart at another, and that changing distance — hence the cost and the
     * duration of the flight it prices — is a thing the player is meant to feel.</p>
     */
    public double staticFrameDistanceSqTo(GalacticCoord other) {
        double dx = (double) (other.sectorX - sectorX) * CELL + (other.localX - localX);
        double dy = (double) (other.sectorY - sectorY) * CELL + (other.localY - localY);
        double dz = (double) (other.sectorZ - sectorZ) * CELL + (other.localZ - localZ);
        return dx * dx + dy * dy + dz * dz;
    }

    /** Distance to {@code other} over the STATIC grid, in blocks. See
     *  {@link #staticFrameDistanceSqTo} for when that is the distance you want. */
    public double staticFrameDistanceTo(GalacticCoord other) {
        return Math.sqrt(staticFrameDistanceSqTo(other));
    }

    /**
     * Stable key for this coordinate's cell - the sector triple. Equal iff {@link #sameCell}. Used to
     * key the on-disk cell store and to bind a pool slot to a cell.
     */
    public String cellKey() {
        String here = sectorX + "_" + sectorY + "_" + sectorZ;
        return zone == null ? here : zone + ZONE_SEPARATOR + here;
    }

    /**
     * What separates one lattice level from the next inside a cell key.
     *
     * <p>A DOT, and not the obvious slash: a cell key becomes a directory name
     * ({@code cell_<key>} under the pool's store) and is read straight back out of it, so a separator
     * the filesystem treats as structure would turn one cell's store into a tree and break the
     * round-trip. Underscore is already the field separator inside a level.</p>
     */
    public static final char ZONE_SEPARATOR = '.';

    /**
     * The cell-centre coordinate of a {@link #cellKey()} string, or {@code null} if malformed. The
     * inverse of {@code cellKey()}: lets a slot world recover the cell it is bound to (the pool binds
     * slots by key) so world-frame poses can be mapped back to absolute galactic coordinates.
     */
    public static GalacticCoord fromCellKey(String key) {
        if (key == null) {
            return null;
        }
        int lastLevel = key.lastIndexOf(ZONE_SEPARATOR);
        String zonePart = lastLevel < 0 ? null : key.substring(0, lastLevel);
        String[] parts = key.substring(lastLevel + 1).split("_");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new GalacticCoord(zonePart, Long.parseLong(parts[0]), Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]), 0, 0, 0);
        } catch (NumberFormatException bad) {
            return null;
        }
    }

    /** Write this coordinate into {@code nbt} under the {@code "galacticCoord"} sub-tag. */
    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound sub = new NBTTagCompound();
        sub.setLong("sx", sectorX);
        sub.setLong("sy", sectorY);
        sub.setLong("sz", sectorZ);
        sub.setInteger("lx", localX);
        sub.setInteger("ly", localY);
        sub.setInteger("lz", localZ);
        nbt.setTag("galacticCoord", sub);
    }

    /**
     * Read a coordinate written by {@link #writeToNBT(NBTTagCompound)}, or {@link #ORIGIN} when the
     * sub-tag is absent (mirrors the lenient default of the legacy space types).
     */
    public static GalacticCoord readFromNBT(NBTTagCompound nbt) {
        if (!nbt.hasKey("galacticCoord")) {
            return ORIGIN;
        }
        NBTTagCompound sub = nbt.getCompoundTag("galacticCoord");
        return ofSectorLocal(
                sub.getLong("sx"), sub.getLong("sy"), sub.getLong("sz"),
                sub.getInteger("lx"), sub.getInteger("ly"), sub.getInteger("lz"));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GalacticCoord)) {
            return false;
        }
        GalacticCoord other = (GalacticCoord) o;
        return java.util.Objects.equals(zone, other.zone)
                && sectorX == other.sectorX && sectorY == other.sectorY && sectorZ == other.sectorZ
                && localX == other.localX && localY == other.localY && localZ == other.localZ;
    }

    @Override
    public int hashCode() {
        int result = zone == null ? 0 : zone.hashCode();
        result = 31 * result + Long.hashCode(sectorX);
        result = 31 * result + Long.hashCode(sectorY);
        result = 31 * result + Long.hashCode(sectorZ);
        result = 31 * result + localX;
        result = 31 * result + localY;
        result = 31 * result + localZ;
        return result;
    }

    @Override
    public String toString() {
        return "GalacticCoord[" + (zone == null ? "" : "zone=" + zone + ", ")
                + "sector=(" + sectorX + "," + sectorY + "," + sectorZ + "), local=("
                + localX + "," + localY + "," + localZ + ")]";
    }
}
