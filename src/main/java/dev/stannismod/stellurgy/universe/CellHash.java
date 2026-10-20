package dev.stannismod.stellurgy.universe;

import dev.stannismod.stellurgy.space.GalacticCoord;

/**
 * The one mixer every procedural answer about a cell is drawn from.
 *
 * <p>A splitmix-style mix of the world seed, an integer coordinate triple and a per-field salt, uniform
 * over 64 bits. Distinct salts are what keep the independent draws — blob mask, occupancy, star type,
 * body count, a planet's radius — from correlating with each other.</p>
 *
 * <p><b>This arithmetic is a save-compatibility surface for the LIFE of a world, not an implementation
 * detail.</b> Every unpinned procedural system is re-derived from it on every query, so changing a
 * constant here silently moves stars and reshapes planets in every existing save that has not been
 * touched. It lives in one place for exactly that reason: two copies of a mixer are two things to
 * forget about.</p>
 *
 * <p><b>Public because it belongs to every SCHEMA, not to one generator.</b> A released world model is
 * kept reproducible forever, and a later version that changes what it draws still has to draw it out of
 * the same mixer — a second copy of this arithmetic in another package would be a second thing to keep
 * in step, which is exactly what the paragraph above forbids.</p>
 */
public final class CellHash {

    private CellHash() {
    }

    /** Mix {@code seed}, the triple {@code (a,b,c)} and {@code salt} into a uniform 64-bit value. */
    public static long of(long seed, long a, long b, long c, long salt) {
        long h = seed + salt * 0x9E3779B97F4A7C15L;
        h ^= a;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h ^= b;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        h ^= c;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return h;
    }

    /**
     * Folds a cell's ZONE into the seed — {@code seed} itself for a galactic cell.
     *
     * <p>A zoned cell's sector triple is counted in its parent's zone lattice, so the same small triple
     * names a cell in every zone of the galaxy. Hashing the triple alone gave every moon at zone cell
     * {@code (1,0,0)} the same draw, wherever its planet was. Galactic cells are untouched, so no
     * planet's draw moves.</p>
     *
     * @throws IllegalArgumentException when the zone is not a readable cell key
     */
    private static long seedIn(long seed, GalacticCoord cell) {
        String zone = cell.zone();
        if (zone == null) {
            return seed;
        }
        GalacticCoord parent = GalacticCoord.fromCellKey(zone);
        if (parent == null) {
            throw new IllegalArgumentException("cell " + cell.cellKey() + " names a zone '" + zone
                    + "' that is not a cell key, so no draw can be told apart from another zone's");
        }
        return of(seedIn(seed, parent), parent.sectorX(), parent.sectorY(), parent.sectorZ(), SALT_ZONE);
    }

    /** The salt of {@link #seedIn}; disjoint from every field salt, which all sit below {@code 0x1000}. */
    private static final long SALT_ZONE = 0x5A0E_0001L;

    /** Mix a cell's own field draw. */
    public static long ofCell(long seed, GalacticCoord cell, long salt) {
        return of(seedIn(seed, cell), cell.sectorX(), cell.sectorY(), cell.sectorZ(), salt);
    }

    /**
     * Mix a per-BODY field draw inside a cell's system.
     *
     * <p>The body index is XORed into the seed through a different multiplier than {@link #of} uses for
     * the salt, so the two cannot merge into {@code (i + salt) * G} and correlate neighbouring bodies'
     * draws — which would make body {@code i}'s radius a near-copy of body {@code i+1}'s.</p>
     */
    public static long ofBody(long seed, GalacticCoord cell, int index, long salt) {
        return of(seedIn(seed, cell) ^ (index * 0xD1B54A32D192ED03L), cell.sectorX(), cell.sectorY(),
                cell.sectorZ(), salt);
    }

    /** Map a 64-bit hash to a double in {@code [0, 1)}. */
    public static double norm(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}
