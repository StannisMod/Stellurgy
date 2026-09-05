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
     * <p>A sector triple means nothing on its own: a zone's cell size is a property of the ZONE, so the
     * same triple denotes different places in different lattices. Two coordinates in
     * different zones are therefore never the same cell, however their numbers compare — which is why
     * this participates in {@link #equals} and {@link #sameCell} rather than being decoration on the
     * key.</p>
     *
     * <p>The galactic lattice is the null path, so every coordinate written before zones existed
     * keeps its exact meaning.</p>
     */
    private final String zone;

    /**
     * The edge length, in blocks, of one cell of the lattice this coordinate's sector triple is
     * counted in — {@link #CELL} for the galactic lattice, and the zone's own cell size for a zoned
     * one. {@link #WIDTH_UNKNOWN} when a coordinate was recovered from a KEY, which does not carry it.
     *
     * <p><b>Not part of the name, and deliberately absent from {@link #equals} and {@link #cellKey}.</b>
     * The name is the zone plus the triple; the width is the SCALE that triple is counted at, which is
     * a property of the zone and the same for every coordinate in it. Two coordinates in one zone
     * therefore agree on it by construction, and a coordinate read back from a key must compare equal
     * to the one that wrote it or every store folder, ledger row and slot binding stops round-tripping.</p>
     *
     * <p>Where it is unknown, the arithmetic that needs it REFUSES rather than substituting
     * {@link #CELL}: a carry at the wrong width does not fail, it renames the cell — a 1.5M-block
     * offset inside a 7 000-block zone cell would normalise against 32M and land in a cell nobody
     * materialized. Re-attach a known width with {@link #inLattice(long)}.</p>
     */
    private final long cellBlocks;

    /** {@link #cellBlocks()} for a coordinate recovered from a key, which does not carry the width. */
    public static final long WIDTH_UNKNOWN = 0L;

    private GalacticCoord(long sectorX, long sectorY, long sectorZ, int localX, int localY, int localZ) {
        this(null, CELL, sectorX, sectorY, sectorZ, localX, localY, localZ);
    }

    private GalacticCoord(String zone, long cellBlocks, long sectorX, long sectorY, long sectorZ,
                          int localX, int localY, int localZ) {
        this.zone = zone;
        this.cellBlocks = cellBlocks;
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
        return normalised(null, CELL, sectorX, sectorY, sectorZ, localX, localY, localZ);
    }

    /**
     * The same, in the lattice of {@code zone} — the parent's cell key — whose cells are
     * {@code cellBlocks} blocks across.
     *
     * <p><b>The width is stated, never defaulted.</b> A zone's cell size is a property of the zone, and
     * it is nothing like the galactic one: Earth's zone cell is ~7 200 blocks against
     * a galactic cell of 32 000 000. A construction that carried at {@link #CELL} would put every
     * in-cell offset a zone can hold into sector 0 — which is "a moon shares its parent's name" back
     * again, one level down and silent. There is no width-less overload for that reason; a caller that cannot name the width
     * does not have a zoned cell, it has a key ({@link #fromCellKey}).</p>
     *
     * @throws IllegalArgumentException if {@code zone} is given without a positive width
     */
    public static GalacticCoord inZone(String zone, long cellBlocks, long sectorX, long sectorY,
                                       long sectorZ, long localX, long localY, long localZ) {
        if (zone != null && cellBlocks <= 0L) {
            throw new IllegalArgumentException("zone '" + zone + "' needs its cell width to name a "
                    + "cell in it; got " + cellBlocks);
        }
        return normalised(zone, zone == null ? CELL : cellBlocks,
                sectorX, sectorY, sectorZ, localX, localY, localZ);
    }

    /** Carry the local offsets into the sectors at {@code width}. The one place the carry is decided. */
    private static GalacticCoord normalised(String zone, long width, long sectorX, long sectorY,
                                            long sectorZ, long localX, long localY, long localZ) {
        long half = width / 2L;
        long carryX = Math.floorDiv(localX + half, width);
        long carryY = Math.floorDiv(localY + half, width);
        long carryZ = Math.floorDiv(localZ + half, width);
        return new GalacticCoord(zone, width,
                sectorX + carryX, sectorY + carryY, sectorZ + carryZ,
                (int) (localX - carryX * width),
                (int) (localY - carryY * width),
                (int) (localZ - carryZ * width));
    }

    /** The zone this coordinate's lattice belongs to, or {@code null} for the galactic one. */
    public String zone() {
        return zone;
    }

    /**
     * The edge length of one cell of this coordinate's lattice, in blocks, or {@link #WIDTH_UNKNOWN}
     * when it was recovered from a key and nobody has re-attached it. See the field's own note.
     */
    public long cellBlocks() {
        return cellBlocks;
    }

    /**
     * This coordinate's cell in the GALACTIC lattice — itself when it is already galactic, otherwise
     * the outermost level of its key, walked out through the zone chain.
     *
     * <p>Every question about WHERE a zoned cell sits in the galaxy — which system's neighbourhood
     * attributes it, whether it is inside an anchor's box — is a question about this cell and not
     * about the zone-local triple, whose numbers are counted in a lattice thousands of times finer.
     * Answering such a question from a zoned triple does not fail, it points somewhere else.</p>
     */
    public GalacticCoord galacticCell() {
        GalacticCoord here = this;
        while (here != null && here.zone != null) {
            here = fromCellKey(here.zone);
        }
        return here == null ? ORIGIN : here;
    }

    /**
     * This coordinate re-stated in a lattice {@code cellBlocks} blocks across — how a caller that
     * knows the zone's width gives it back to a coordinate read out of a key.
     *
     * <p>The sector triple and the local offset are kept as they are: this states the SCALE they were
     * always counted at, it does not move anything. Passing a different width than the one the
     * coordinate was named in is therefore a way to say something false, and no check here can catch
     * it — the width belongs to the zone, so take it from the zone's body.</p>
     */
    public GalacticCoord inLattice(long cellBlocks) {
        if (cellBlocks <= 0L) {
            throw new IllegalArgumentException("a lattice width must be positive; got " + cellBlocks);
        }
        return cellBlocks == this.cellBlocks ? this
                : new GalacticCoord(zone, cellBlocks, sectorX, sectorY, sectorZ, localX, localY, localZ);
    }

    /**
     * This coordinate's own width, or a refusal naming what could not be answered. Called by
     * everything that has to multiply a sector index by a cell size.
     */
    private long requireWidth(String operation) {
        if (cellBlocks <= 0L) {
            throw new IllegalStateException(operation + " needs the cell width of zone '" + zone
                    + "', which a cell key does not carry. Re-attach it with inLattice(long) from the "
                    + "zone's own body before doing arithmetic on this coordinate.");
        }
        return cellBlocks;
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
        return new GalacticCoord(zone, cellBlocks, sectorX, sectorY, sectorZ, 0, 0, 0);
    }

    /**
     * This cell with the given offset, exactly as given — <b>no carry and no saturation</b>.
     *
     * <p>Both of those are answers to questions this one is not. A carry is for INTEGRATING a path,
     * where crossing into the next sector is the point; saturation is for REPORTING where a ship is,
     * where a name must keep pointing at the world the ship is in. This is for ASKING how far past
     * something a pose has gone — where an offset larger than the cell is the whole subject and has
     * to survive being looked at. The lattice and its width are kept.</p>
     */
    public GalacticCoord withLocal(long dx, long dy, long dz) {
        return new GalacticCoord(zone, cellBlocks, sectorX, sectorY, sectorZ,
                (int) dx, (int) dy, (int) dz);
    }

    /**
     * This coordinate shifted by a local block delta, renormalised. The unit step of transit
     * integration: repeatedly adding a per-tick velocity vector never drifts (exact integer carry).
     */
    public GalacticCoord plusLocal(long dx, long dy, long dz) {
        if (dx == 0L && dy == 0L && dz == 0L) {
            return this; // no carry to decide, so no width is needed to decide it
        }
        return normalised(zone, requireWidth("plusLocal"), sectorX, sectorY, sectorZ,
                localX + dx, localY + dy, localZ + dz);
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
        if (dx == 0L && dy == 0L && dz == 0L) {
            return this;
        }
        long half = requireWidth("plusLocalSaturating") / 2L;
        return new GalacticCoord(zone, cellBlocks, sectorX, sectorY, sectorZ,
                (int) saturateLocal(localX + dx, half),
                (int) saturateLocal(localY + dy, half),
                (int) saturateLocal(localZ + dz, half));
    }

    /**
     * {@code true} iff {@code local} is a canonical offset inside a GALACTIC cell, i.e. one that would
     * not carry. Stated for the galactic lattice because that is the only one a caller with a bare
     * {@code long} and no coordinate can be talking about; inside a zone the same question is
     * {@link #localWithinCell(long, long)} against that zone's own width.
     */
    public static boolean localWithinCell(long local) {
        return localWithinCell(local, CELL);
    }

    /** {@code true} iff {@code local} is a canonical in-cell offset in a lattice {@code width} across. */
    public static boolean localWithinCell(long local, long width) {
        long half = width / 2L;
        return local >= -half && local < half;
    }

    /** {@code local} held inside {@code [-half, half)} — the range that does not carry. */
    private static long saturateLocal(long local, long half) {
        if (local >= half) {
            return half - 1L;
        }
        return local < -half ? -half : local;
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
     *
     * <p><b>Both coordinates must be in the SAME lattice, and it refuses when they are not.</b> A
     * sector index is a count of that lattice's cells, and the two lattices in play differ by four
     * orders of magnitude — subtracting a zone-local index from a galactic one and scaling the
     * difference by either width produces a well-formed number that describes nothing. There is no
     * distance between two names in different lattices without a tick and both frames, which is what
     * {@link AbsolutePos} is for.</p>
     *
     * @throws IllegalArgumentException if the two coordinates are in different zones
     */
    public double staticFrameDistanceSqTo(GalacticCoord other) {
        if (!java.util.Objects.equals(zone, other.zone)) {
            throw new IllegalArgumentException("no static-frame distance between cells of different "
                    + "lattices: '" + cellKey() + "' is in zone " + zone + ", '" + other.cellKey()
                    + "' in zone " + other.zone + ". Ask AbsolutePos at a tick instead.");
        }
        long width = requireWidth("staticFrameDistanceSqTo");
        double dx = (double) (other.sectorX - sectorX) * width + (other.localX - localX);
        double dy = (double) (other.sectorY - sectorY) * width + (other.localY - localY);
        double dz = (double) (other.sectorZ - sectorZ) * width + (other.localZ - localZ);
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
     *
     * <p>A ZONED key comes back with {@link #WIDTH_UNKNOWN}: a key names a cell, and the width of the
     * lattice it is counted in is a property of the zone's BODY, which a string cannot carry. Such a
     * coordinate compares, keys and round-trips exactly like the one that wrote it, and refuses the
     * arithmetic that would need the width — see {@link #inLattice(long)}. Putting the width into the
     * key instead was rejected: it is derived at the naming tick and would sit inside the address a
     * player writes down, where a later re-derivation of it reads as a different place.</p>
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
            return new GalacticCoord(zonePart, zonePart == null ? CELL : WIDTH_UNKNOWN,
                    Long.parseLong(parts[0]), Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]), 0, 0, 0);
        } catch (NumberFormatException bad) {
            return null;
        }
    }

    /**
     * Write this coordinate into {@code nbt} under the {@code "galacticCoord"} sub-tag.
     *
     * <p>The zone and its cell width are written too, and unlike the key this CAN carry them: NBT is
     * a record, not an address anyone reads out loud. Both tags are omitted for a galactic
     * coordinate, so what a pre-zone save wrote and what a galactic cell writes today are the same
     * bytes.</p>
     */
    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound sub = new NBTTagCompound();
        sub.setLong("sx", sectorX);
        sub.setLong("sy", sectorY);
        sub.setLong("sz", sectorZ);
        sub.setInteger("lx", localX);
        sub.setInteger("ly", localY);
        sub.setInteger("lz", localZ);
        if (zone != null) {
            sub.setString("zone", zone);
            sub.setLong("cw", cellBlocks);
        }
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
        String zone = sub.hasKey("zone") ? sub.getString("zone") : null;
        if (zone == null) {
            return ofSectorLocal(
                    sub.getLong("sx"), sub.getLong("sy"), sub.getLong("sz"),
                    sub.getInteger("lx"), sub.getInteger("ly"), sub.getInteger("lz"));
        }
        // A zoned coordinate whose width did not survive is a coordinate whose arithmetic must
        // refuse, not one that quietly counts its sectors at the galactic width.
        return new GalacticCoord(zone, sub.hasKey("cw") ? sub.getLong("cw") : WIDTH_UNKNOWN,
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
        return "GalacticCoord[" + (zone == null ? "" : "zone=" + zone + "@"
                + (cellBlocks > 0L ? String.valueOf(cellBlocks) : "width?") + ", ")
                + "sector=(" + sectorX + "," + sectorY + "," + sectorZ + "), local=("
                + localX + "," + localY + "," + localZ + ")]";
    }
}
