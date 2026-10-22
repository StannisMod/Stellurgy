package dev.stannismod.stellurgy.ship.guidance;

import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import dev.stannismod.stellurgy.space.BlockDelta;
import dev.stannismod.stellurgy.space.CellWorldMapper;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.space.ShipLedger;
import dev.stannismod.stellurgy.space.SpaceSlotPool;
import dev.stannismod.stellurgy.space.SpaceSubsystem;
import dev.stannismod.stellurgy.space.TerrainHeightFinder;
import dev.stannismod.stellurgy.space.WorldProviderSpaceSlot;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import java.util.UUID;

/**
 * The {@link Surroundings} of a craft in a running game: what the world around it answers on one tick.
 *
 * <p><b>A ship</b> is looked for first in the craft's own world's ship registry, where its position and
 * hull are read exactly. A craft in space then asks the ship ledger, which keeps every ship SETTLED in a
 * cell at its absolute address: that address is placed in the craft's world through both cells' frames
 * at the space clock's tick — the vector from the craft cell's centre (its world's origin) to the ship —
 * and the hull is measured in the world of the ship's own cell, if that world is loaded. A ship anywhere
 * else — mid-jump in hyperspace, on a planet seen from space, in another world seen from a planet — needs
 * a crossing; one found nowhere is not found.</p>
 *
 * <p><b>The ground</b> is the highest solid or liquid block top in the square asked, read through
 * {@link TerrainHeightFinder#terrainTopOfFootprint}, which loads the chunks it reads.</p>
 *
 * <p>Server game thread only.</p>
 */
public final class WorldSurroundings implements Surroundings {

    private final WorldServer world;
    private final GalacticCoord cell;

    /**
     * @param world the world the craft is in
     * @param cell  the space cell that world realizes, {@code null} on a planet
     */
    public WorldSurroundings(WorldServer world, GalacticCoord cell) {
        this.world = world;
        this.cell = cell;
    }

    @Override
    public Sighting ship(UUID shipId) {
        double[] here = VSIntegration.registeredShipPosition(world, shipId);
        if (here != null) {
            return measured(here, VSIntegration.hullAboutPosition(world, shipId));
        }
        SpaceSubsystem space = dev.stannismod.stellurgy.Stellurgy.spaceSubsystem();
        ShipLedger.Entry entry = space == null ? null : space.ledger.get(shipId);
        if (cell != null && entry != null) {
            if (entry.state != ShipLedger.State.SETTLED || entry.coord == null) {
                return Sighting.unplaced(GuidanceTarget.Unplaced.NEEDS_A_CROSSING);
            }
            if (!cell.knowsItsLattice() || !entry.coord.knowsItsLattice()) {
                return Sighting.unplaced(GuidanceTarget.Unplaced.NO_FRAME);
            }
            // FRAME: the vector from the craft cell's centre — its world's origin, on the cell's axes — to
            // the ship's absolute address, both read at one tick, IS the ship's position in this world.
            BlockDelta fromHere = SpaceSubsystem.frames().deltaBetween(cell.cellCentre(), entry.coord,
                    SpaceSubsystem.spaceClock());
            if (fromHere.isSaturated()) {
                return Sighting.unplaced(GuidanceTarget.Unplaced.NO_FRAME);
            }
            double[] origin = CellWorldMapper.poseWorldOf(cell.cellCentre());
            double[] there = {origin[0] + fromHere.dx(), origin[1] + fromHere.dy(), origin[2] + fromHere.dz()};
            WorldServer its = worldOfCell(entry.coord.cellKey());
            return measured(there, its == null ? null : VSIntegration.hullAboutPosition(its, shipId));
        }
        if (entry != null) {
            return Sighting.unplaced(GuidanceTarget.Unplaced.NEEDS_A_CROSSING);
        }
        for (WorldServer other : DimensionManager.getWorlds()) {
            if (other != world && VSIntegration.registeredShipUuid(other, shipId) != null) {
                return Sighting.unplaced(GuidanceTarget.Unplaced.NEEDS_A_CROSSING);
            }
        }
        return Sighting.unplaced(GuidanceTarget.Unplaced.NOT_FOUND);
    }

    @Override
    public double groundTop(double x, double z, double radius) {
        int minX = (int) Math.floor(x - radius);
        int minZ = (int) Math.floor(z - radius);
        int width = (int) Math.floor(x + radius) - minX + 1;
        int depth = (int) Math.floor(z + radius) - minZ + 1;
        return TerrainHeightFinder.terrainTopOfFootprint(world, minX, minZ, width, depth);
    }

    private static Sighting measured(double[] position, double[] hull) {
        if (hull == null) {
            return Sighting.unplaced(GuidanceTarget.Unplaced.NOT_MEASURED);
        }
        return Sighting.at(position, hull[0]);
    }

    /** The loaded slot world bound to the cell named {@code cellKey}, or {@code null}. */
    private static WorldServer worldOfCell(String cellKey) {
        for (WorldServer w : DimensionManager.getWorlds()) {
            if (w.provider instanceof WorldProviderSpaceSlot
                    && cellKey.equals(SpaceSlotPool.cellKeyFor(w.provider.getDimension()))) {
                return w;
            }
        }
        return null;
    }
}
