package dev.stannismod.stellurgy.ship.guidance;

import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import dev.stannismod.stellurgy.space.AutoTakeoffPlanner;
import dev.stannismod.stellurgy.space.BlockDelta;
import dev.stannismod.stellurgy.space.CellWorldMapper;
import dev.stannismod.stellurgy.space.DescentShell;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.space.HyperspaceWorld;
import dev.stannismod.stellurgy.space.ShipEntryController;
import dev.stannismod.stellurgy.space.ShipLedger;
import dev.stannismod.stellurgy.space.SpaceSlotPool;
import dev.stannismod.stellurgy.space.SpaceSubsystem;
import dev.stannismod.stellurgy.space.TerrainHeightFinder;
import dev.stannismod.stellurgy.space.WorldProviderSpaceSlot;
import dev.stannismod.stellurgy.universe.SystemBody;
import dev.stannismod.stellurgy.universe.UniverseRegistry;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import java.util.Optional;
import java.util.UUID;

/**
 * The {@link Surroundings} of a craft in a running game: what the world around it answers on one tick.
 *
 * <p><b>A ship</b> is looked for first in the craft's own world's ship registry, where its position and
 * hull are read exactly. Then the ship ledger, which keeps every ship SETTLED in a cell at its absolute
 * address: seen from a craft in space that address is placed in the craft's world through both cells'
 * frames at the space clock's tick — the vector from the craft cell's centre (its world's origin) to the
 * ship — and answered as a ship in ANOTHER cell's world, whose hull is not measured from here, whether or
 * not that world is loaded: the hull the craft will meet is measured once the craft shares its world. Seen
 * from a planet it is answered as a ship in space. A ship the ledger holds in transit is in hyperspace.
 * Otherwise every loaded world is asked: a ship in a space cell's world is placed as a settled one is, from
 * its cell and its pose there; a ship in a planet world is answered as on that planet. One found nowhere is
 * not found.</p>
 *
 * <p><b>The system.</b> Anything of another system than the craft's — a ship, a body — is answered as
 * needing a jump. A place's system is the anchor of the system whose neighbourhood holds its cell
 * ({@link UniverseRegistry#anchorForCell}): the craft cell's, in space; on a planet, that of the address
 * its body is entered from — the address the entry crossing puts the craft beside, the origin when it has
 * none, as the entry does. Two places with no system — both in the void — are of one system, the void's:
 * flying between them crosses cells and no jump.</p>
 *
 * <p><b>The ground</b> is the highest solid or liquid block top in the square asked, read through
 * {@link TerrainHeightFinder#terrainTopOfFootprint}, which loads the chunks it reads.</p>
 *
 * <p><b>The orbit line</b> is the one the craft's own flight computer holds and hands in, so the entry
 * crossing and the climb to it read one line. <b>A body's orbit and its descent shell</b> are placed like a
 * ship in another cell.</p>
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
        if (entry != null) {
            if (entry.state != ShipLedger.State.SETTLED || entry.coord == null) {
                return Sighting.unplaced(GuidanceTarget.Unplaced.IN_HYPERSPACE);
            }
            return inSpaceAt(entry.coord);
        }
        for (WorldServer other : DimensionManager.getWorlds()) {
            if (other == world || VSIntegration.registeredShipUuid(other, shipId) == null) {
                continue;
            }
            if (HyperspaceWorld.isHyperspace(other)) {
                return Sighting.unplaced(GuidanceTarget.Unplaced.IN_HYPERSPACE);
            }
            int dim = other.provider.getDimension();
            if (other.provider instanceof WorldProviderSpaceSlot) {
                GalacticCoord itsCell = SpaceSlotPool.cellCoordFor(dim);
                double[] pose = VSIntegration.registeredShipPosition(other, shipId);
                if (itsCell == null || pose == null) {
                    return Sighting.unplaced(GuidanceTarget.Unplaced.NO_FRAME);
                }
                return inSpaceAt(CellWorldMapper.coordOfPoseWithin(itsCell, pose[0], pose[1], pose[2]));
            }
            GuidanceTarget.Unplaced why = unreachable(dim);
            return why != null ? Sighting.unplaced(why) : Sighting.onPlanet(dim);
        }
        return Sighting.unplaced(GuidanceTarget.Unplaced.NOT_FOUND);
    }

    /** A ship settled in space at {@code at}, as seen from the craft. */
    private Sighting inSpaceAt(GalacticCoord at) {
        if (!systemOf(at).equals(systemHere())) {
            return Sighting.unplaced(GuidanceTarget.Unplaced.NEEDS_A_JUMP);
        }
        if (cell == null) {
            return Sighting.inSpace();
        }
        if (!cell.knowsItsLattice() || !at.knowsItsLattice()) {
            return Sighting.unplaced(GuidanceTarget.Unplaced.NO_FRAME);
        }
        // FRAME: the vector from the craft cell's centre — its world's origin, on the cell's axes — to
        // the ship's absolute address, both read at one tick, IS the ship's position in this world.
        BlockDelta fromHere = SpaceSubsystem.frames().deltaBetween(cell.cellCentre(), at,
                SpaceSubsystem.spaceClock());
        if (fromHere.isSaturated()) {
            return Sighting.unplaced(GuidanceTarget.Unplaced.NO_FRAME);
        }
        double[] origin = CellWorldMapper.poseWorldOf(cell.cellCentre());
        double[] there = {origin[0] + fromHere.dx(), origin[1] + fromHere.dy(), origin[2] + fromHere.dz()};
        return Sighting.inAnotherCell(there, at);
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
     * radius is the body's own entry ring ({@link ShipEntryController#entryRingAround(SystemBody)}).
     */
    @Override
    public Sphere orbit(int dimension) {
        return sphereAbout(dimension, false);
    }

    /** Placed as {@link #orbit} is; the radius is the body's descent shell ({@link DescentShell#radiusAround}). */
    @Override
    public Sphere shell(int dimension) {
        return sphereAbout(dimension, true);
    }

    @Override
    public GuidanceTarget.Unplaced unreachable(int dimension) {
        Optional<SystemBody> body = SpaceSubsystem.launchBody(dimension);
        if (!body.isPresent()) {
            return GuidanceTarget.Unplaced.NOT_FOUND;
        }
        return systemOf(body.get().name()).equals(systemHere()) ? null : GuidanceTarget.Unplaced.NEEDS_A_JUMP;
    }

    private Sphere sphereAbout(int dimension, boolean shell) {
        if (cell == null) {
            throw new IllegalStateException("a sphere about a body is placed through a space cell's frame, and"
                    + " this craft is in no cell");
        }
        Optional<SystemBody> body = SpaceSubsystem.launchBody(dimension);
        if (!body.isPresent()) {
            return Sphere.unplaced(GuidanceTarget.Unplaced.NOT_FOUND);
        }
        if (!systemOf(body.get().name()).equals(systemHere())) {
            return Sphere.unplaced(GuidanceTarget.Unplaced.NEEDS_A_JUMP);
        }
        if (!cell.knowsItsLattice()) {
            return Sphere.unplaced(GuidanceTarget.Unplaced.NO_FRAME);
        }
        long clock = SpaceSubsystem.spaceClock();
        // FRAME: where the body IS at this tick — its own position, not its address, which is a name — in the
        // frame of the craft's cell: the body's position in this world. The entry draws its ring, and the
        // descent condition measures its shell, about the same.
        BlockDelta fromHere = SpaceSubsystem.frames().positionInCell(cell, body.get().absoluteAt(clock), clock);
        if (fromHere.isSaturated()) {
            return Sphere.unplaced(GuidanceTarget.Unplaced.NO_FRAME);
        }
        double[] origin = CellWorldMapper.poseWorldOf(cell.cellCentre());
        return Sphere.about(new double[]{origin[0] + fromHere.dx(), origin[1] + fromHere.dy(), origin[2] + fromHere.dz()},
                shell ? DescentShell.radiusAround(body.get()) : ShipEntryController.entryRingAround(body.get()));
    }

    /** The system of the craft's own world: its cell's in space; on a planet, its body's entry address's. */
    private Optional<GalacticCoord> systemHere() {
        if (cell != null) {
            return systemOf(cell);
        }
        GalacticCoord address = SpaceSubsystem.launchBodyAddress(world.provider.getDimension());
        return systemOf(address == null ? GalacticCoord.ORIGIN : address);
    }

    /** The anchor of the system whose neighbourhood holds {@code at}'s cell; empty in the void. */
    private Optional<GalacticCoord> systemOf(GalacticCoord at) {
        UniverseRegistry reg = UniverseRegistry.get(world.getMinecraftServer());
        return reg == null ? Optional.empty() : reg.anchorForCell(at);
    }
}
