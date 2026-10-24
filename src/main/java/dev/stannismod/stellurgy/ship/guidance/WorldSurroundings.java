package dev.stannismod.stellurgy.ship.guidance;

import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import dev.stannismod.stellurgy.space.AutoTakeoffPlanner;
import dev.stannismod.stellurgy.space.BlockDelta;
import dev.stannismod.stellurgy.space.CellWorldMapper;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.space.ShipEntryController;
import dev.stannismod.stellurgy.space.ShipLedger;
import dev.stannismod.stellurgy.space.SpaceSubsystem;
import dev.stannismod.stellurgy.space.TerrainHeightFinder;
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
 * and answered as a ship in ANOTHER cell's world, whose hull is not measured from here, whether or not
 * that world is loaded: the hull the craft will meet is measured once the craft shares its world. A ship
 * anywhere else — mid-jump in hyperspace, on a planet seen from space, in another world seen from a
 * planet — needs a crossing; one found nowhere is not found.</p>
 *
 * <p><b>The ground</b> is the highest solid or liquid block top in the square asked, read through
 * {@link TerrainHeightFinder#terrainTopOfFootprint}, which loads the chunks it reads.</p>
 *
 * <p><b>The orbit line</b> is the one the craft's own flight computer holds and hands in, so the entry
 * crossing and the climb to it read one line. <b>A body's orbit</b> is placed like a ship in another
 * cell.</p>
 *
 * <p>Server game thread only.</p>
 */
public final class WorldSurroundings implements Surroundings {

    private final WorldServer world;
    private final GalacticCoord cell;
    private final java.util.OptionalInt orbitLine;

    /**
     * @param world     the world the craft is in
     * @param cell      the space cell that world realizes, {@code null} on a planet
     * @param orbitLine the height the entry crossing fires above in that world, as its flight computer
     *                  holds it; empty where the world has none, and in space
     */
    public WorldSurroundings(WorldServer world, GalacticCoord cell, java.util.OptionalInt orbitLine) {
        this.world = world;
        this.cell = cell;
        this.orbitLine = orbitLine;
    }

    @Override
    public Sighting ship(UUID shipId) {
        double[] here = VSIntegration.registeredShipPosition(world, shipId);
        if (here != null) {
            double[] hull = VSIntegration.hullAboutPosition(world, shipId);
            return hull == null ? Sighting.withoutBlocks(here) : Sighting.at(here, hull[0]);
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
            return Sighting.inAnotherCell(there, entry.coord);
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

    @Override
    public double orbitLine() {
        return orbitLine.isPresent() ? orbitLine.getAsInt() : Double.NaN;
    }

    /**
     * The take-off corridor ({@link AutoTakeoffPlanner#corridorClear}), with solid MATERIAL as what blocks.
     * Nothing stands above the world's build height, so the column is read no higher. The craft's own
     * blocks are not in it: a ship's hull lives in its shipyard, far from the pose.
     */
    @Override
    public boolean openAbove(double x, double z, double fromY, double toY) {
        return AutoTakeoffPlanner.corridorClear(x, z, fromY, Math.min(toY, world.getHeight() - 1),
                p -> world.getBlockState(new net.minecraft.util.math.BlockPos(p[0], p[1], p[2]))
                        .getMaterial().isSolid());
    }

    /**
     * The body is the one {@link SpaceSubsystem#launchBody} names for the planet world, placed where it
     * stands at the space clock's tick ({@code SystemBody#absoluteAt} — its position, never its address,
     * which is a name) against the absolute position of this cell's centre at the same tick; the sphere's
     * radius is the body's own entry ring ({@link ShipEntryController#entryRingAround(dev.stannismod.stellurgy.universe.SystemBody)}).
     */
    @Override
    public Orbit orbit(int dimension) {
        if (cell == null) {
            throw new IllegalStateException("an orbit is placed through a space cell's frame, and this craft"
                    + " is in no cell");
        }
        java.util.Optional<dev.stannismod.stellurgy.universe.SystemBody> body = SpaceSubsystem.launchBody(dimension);
        if (!body.isPresent()) {
            return Orbit.unplaced(GuidanceTarget.Unplaced.NOT_FOUND);
        }
        if (!cell.knowsItsLattice()) {
            return Orbit.unplaced(GuidanceTarget.Unplaced.NO_FRAME);
        }
        long clock = SpaceSubsystem.spaceClock();
        // FRAME: where the body IS at this tick — its own position, not its address, which is a name — in the
        // frame of the craft's cell: the body's position in this world. The entry draws its ring about the same.
        BlockDelta fromHere = SpaceSubsystem.frames().positionInCell(cell, body.get().absoluteAt(clock), clock);
        if (fromHere.isSaturated()) {
            return Orbit.unplaced(GuidanceTarget.Unplaced.NO_FRAME);
        }
        double[] origin = CellWorldMapper.poseWorldOf(cell.cellCentre());
        return Orbit.about(new double[]{origin[0] + fromHere.dx(), origin[1] + fromHere.dy(), origin[2] + fromHere.dz()},
                ShipEntryController.entryRingAround(body.get()));
    }
}
