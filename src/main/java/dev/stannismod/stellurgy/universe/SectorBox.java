package dev.stannismod.stellurgy.universe;

import dev.stannismod.stellurgy.space.GalacticCoord;

/**
 * An inclusive box of galactic cells, counted in sectors on each axis — the shape a system's
 * NEIGHBOURHOOD has: every cell whose attribution names that system.
 *
 * <p>Two systems' neighbourhoods may not overlap, and this is the type that question is
 * asked in. It names cells and nothing finer: the in-cell position plays no part in which system a
 * cell belongs to.</p>
 */
public final class SectorBox {

    private final long lowX;
    private final long lowY;
    private final long lowZ;
    private final long highX;
    private final long highY;
    private final long highZ;

    private SectorBox(long lowX, long lowY, long lowZ, long highX, long highY, long highZ) {
        if (highX < lowX || highY < lowY || highZ < lowZ) {
            throw new IllegalArgumentException("an inclusive box needs low <= high on every axis: ["
                    + lowX + "," + lowY + "," + lowZ + "].." + "[" + highX + "," + highY + "," + highZ + "]");
        }
        this.lowX = lowX;
        this.lowY = lowY;
        this.lowZ = lowZ;
        this.highX = highX;
        this.highY = highY;
        this.highZ = highZ;
    }

    /** The cells from {@code low} to {@code high}, both included, on each axis. */
    public static SectorBox between(long lowX, long lowY, long lowZ, long highX, long highY, long highZ) {
        return new SectorBox(lowX, lowY, lowZ, highX, highY, highZ);
    }

    /** The cells at most {@code reachSectors} from {@code centre}'s cell on every axis. */
    public static SectorBox around(GalacticCoord centre, long reachSectors) {
        if (reachSectors < 0L) {
            throw new IllegalArgumentException("a reach is a distance and cannot be negative: " + reachSectors);
        }
        GalacticCoord c = centre.galacticCell();
        return new SectorBox(c.sectorX() - reachSectors, c.sectorY() - reachSectors, c.sectorZ() - reachSectors,
                c.sectorX() + reachSectors, c.sectorY() + reachSectors, c.sectorZ() + reachSectors);
    }

    /** Whether the two boxes share at least one cell. */
    public boolean intersects(SectorBox other) {
        return lowX <= other.highX && other.lowX <= highX
                && lowY <= other.highY && other.lowY <= highY
                && lowZ <= other.highZ && other.lowZ <= highZ;
    }

    @Override
    public String toString() {
        return "[" + lowX + "_" + lowY + "_" + lowZ + " .. " + highX + "_" + highY + "_" + highZ + "]";
    }
}
