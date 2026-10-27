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
import dev.stannismod.stellurgy.space.SpaceManager;
import dev.stannismod.stellurgy.space.SpaceSlotPool;
import dev.stannismod.stellurgy.space.SpaceSubsystem;
import dev.stannismod.stellurgy.space.TerrainHeightFinder;
import dev.stannismod.stellurgy.space.WorldProviderSpaceSlot;
import dev.stannismod.stellurgy.universe.SystemBody;
import dev.stannismod.stellurgy.universe.UniverseRegistry;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
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
 * from a planet it is answered as a ship in space. A ship the ledger holds in transit is on its way, and is
 * waited for. Otherwise every loaded world is asked: a ship in a space cell's world is placed as a settled
 * one is, from its cell and its pose there; a ship in the hyperspace world is on its way; a ship in a planet
 * world is answered as on that planet. One found nowhere is not found.</p>
 *
 * <p><b>How far.</b> A ship in space whose cell is farther from the craft's than the next one
 * ({@link GuidanceTarget#flownBetween}) is answered far off, with its address and the hull it last measured
 * itself — the ledger's, written by its own flight computer in its own world, so it is known whether or not
 * that world is loaded now; a body there, with the jump to it, aimed at the body. Which system either cell
 * is of, the void included, does not enter into it.</p>
 *
 * <p><b>The ground</b> is the highest solid or liquid block top in the square asked, read through
 * {@link TerrainHeightFinder#terrainTopOfFootprint}, which loads the chunks it reads.</p>
 *
 * <p><b>The jump</b> is asked of the craft's own flight computer, which holds the drive's wind-up and is
 * the one thing aboard that arms and commits a jump.</p>
 *
 * <p><b>The orbit line</b> is the one the craft's own flight computer holds and hands in, so the entry
 * crossing and the climb to it read one line. <b>A body's orbit and its descent shell</b> are placed like a
 * ship in another cell; <b>the shells</b> are those of the bodies the descent condition can take a craft in
 * this cell onto ({@link UniverseRegistry#skyBodiesAt}, those a craft can descend to), each placed so.</p>
 *
 * <p>Server game thread only.</p>
 */
public final class WorldSurroundings implements Surroundings {

    private final WorldServer world;
    private final GalacticCoord cell;
    private final java.util.OptionalInt orbitLine;
    private final java.util.function.Function<JumpDestination, JumpAnswer> drive;

    /**
     * @param world     the world the craft is in
     * @param cell      the space cell that world realizes, {@code null} on a planet
     * @param orbitLine the height the entry crossing fires above in that world, as its flight computer
     *                  holds it; empty where the world has none, and in space
     * @param drive     the craft's flight computer asking its own jump drive, as {@link #jump} is asked
     */
    public WorldSurroundings(WorldServer world, GalacticCoord cell, java.util.OptionalInt orbitLine,
                             java.util.function.Function<JumpDestination, JumpAnswer> drive) {
        this.world = world;
        this.cell = cell;
        this.orbitLine = orbitLine;
        this.drive = drive;
    }

    @Override
    public JumpAnswer jump(JumpDestination destination) {
        return drive.apply(destination);
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
                return Sighting.arriving();
            }
            return inSpaceAt(entry.coord, entry.hullRadius);
        }
        for (WorldServer other : DimensionManager.getWorlds()) {
            if (other == world || VSIntegration.registeredShipUuid(other, shipId) == null) {
                continue;
            }
            if (HyperspaceWorld.isHyperspace(other)) {
                return Sighting.arriving();
            }
            int dim = other.provider.getDimension();
            if (other.provider instanceof WorldProviderSpaceSlot) {
                GalacticCoord itsCell = SpaceSlotPool.cellCoordFor(dim);
                double[] pose = VSIntegration.registeredShipPosition(other, shipId);
                if (itsCell == null || pose == null) {
                    return Sighting.unplaced(GuidanceTarget.Unplaced.NO_FRAME);
                }
                // A ship no ledger row holds is measured where it is, in this world that is loaded now.
                double[] hull = VSIntegration.hullAboutPosition(other, shipId);
                return inSpaceAt(CellWorldMapper.coordOfPoseWithin(itsCell, pose[0], pose[1], pose[2]),
                        hull == null ? Double.NaN : hull[0]);
            }
            GuidanceTarget.Unplaced why = unreachable(dim);
            return why != null ? Sighting.unplaced(why) : Sighting.onPlanet(dim);
        }
        return Sighting.unplaced(GuidanceTarget.Unplaced.NOT_FOUND);
    }

    /**
     * A ship settled in space at {@code at}, whose hull last measured {@code hullRadius} (NaN when it has
     * not measured itself there yet), as seen from the craft.
     */
    private Sighting inSpaceAt(GalacticCoord at, double hullRadius) {
        if (cell == null) {
            return Sighting.inSpace();
        }
        if (!GuidanceTarget.flownBetween(cell, at)) {
            // Its own size is what the jump comes out beside. A ship that has not measured itself yet has
            // just come into space — settled by an entry or a jump's arrival, its own flight computer's
            // first report still to come on its next tick in its cell — and is waited for, not guessed at.
            return Double.isNaN(hullRadius) ? Sighting.arriving() : Sighting.farOff(at, hullRadius);
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
     * radius is the body's own entry ring ({@link ShipEntryController#entryRingAround(SystemBody)}). A body
     * of another system is jumped to at the body: its planet world, and its address at that tick.
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
    public List<Sphere> shellsBut(OptionalInt entering) {
        if (cell == null) {
            return Collections.emptyList();
        }
        UniverseRegistry reg = UniverseRegistry.get(world.getMinecraftServer());
        if (reg == null || !cell.knowsItsLattice()) {
            return Collections.emptyList();
        }
        long clock = SpaceSubsystem.spaceClock();
        double[] origin = CellWorldMapper.poseWorldOf(cell.cellCentre());
        List<Sphere> shells = new ArrayList<>();
        for (SystemBody body : reg.skyBodiesAt(cell)) {
            if (!body.kind().canDescend() || (entering.isPresent() && body.dimId() == entering.getAsInt())) {
                continue;
            }
            BlockDelta fromHere = SpaceSubsystem.frames().positionInCell(cell, body.absoluteAt(clock), clock);
            if (fromHere.isSaturated()) {
                continue;
            }
            shells.add(Sphere.about(new double[]{origin[0] + fromHere.dx(), origin[1] + fromHere.dy(),
                    origin[2] + fromHere.dz()}, DescentShell.radiusAround(body)));
        }
        return shells;
    }

    @Override
    public GuidanceTarget.Unplaced unreachable(int dimension) {
        return SpaceSubsystem.launchBody(dimension).isPresent() ? null : GuidanceTarget.Unplaced.NOT_FOUND;
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
        long clock = SpaceSubsystem.spaceClock();
        if (!GuidanceTarget.flownBetween(cell, body.get().name())) {
            return Sphere.farOff(JumpDestination.toBody(dimension, body.get().addressAt(clock)));
        }
        if (!cell.knowsItsLattice()) {
            return Sphere.unplaced(GuidanceTarget.Unplaced.NO_FRAME);
        }
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
}
