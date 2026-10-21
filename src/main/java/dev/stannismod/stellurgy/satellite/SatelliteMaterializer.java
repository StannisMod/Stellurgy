package dev.stannismod.stellurgy.satellite;

import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.entity.EntitySatellite;
import dev.stannismod.stellurgy.space.CellWorldMapper;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.space.SpaceManager;
import dev.stannismod.stellurgy.space.SpaceSubsystem;
import dev.stannismod.stellurgy.space.WorldProviderSpaceSlot;
import dev.stannismod.stellurgy.universe.UniverseRegistry;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Puts a satellite's entity where it stands, while that place is live. A satellite is a record in its
 * body's list; it is present in the world only where somebody can see it — in a loaded chunk of its live
 * cell — and costs nothing anywhere else. Nothing here holds state: each placement is derived from the
 * records at the moment a chunk loads or a satellite is released.
 */
public final class SatelliteMaterializer {

    private SatelliteMaterializer() {
    }

    /** A chunk of a live cell has loaded: stand up every satellite whose point falls in it. */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        World world = event.getWorld();
        if (world.isRemote || !(world.provider instanceof WorldProviderSpaceSlot)) {
            return;
        }
        Optional<GalacticCoord> cell = cellOf(world.provider.getDimension());
        if (!cell.isPresent()) {
            return;
        }
        UniverseRegistry reg = UniverseRegistry.get(world);
        long tick = SpaceSubsystem.spaceClock();
        Chunk chunk = event.getChunk();
        for (SatelliteBase satellite : satellitesWithOrbits()) {
            Optional<GalacticCoord> point = SatellitePlacement.standingPoint(reg, satellite, tick);
            if (!point.isPresent() || !point.get().sameCell(cell.get())) {
                continue;
            }
            double[] pos = CellWorldMapper.poseWorldOf(point.get());
            BlockPos block = new BlockPos(pos[0], pos[1], pos[2]);
            if ((block.getX() >> 4) == chunk.x && (block.getZ() >> 4) == chunk.z) {
                standUp(world, satellite, pos);
            }
        }
    }

    /**
     * A satellite has just been released: if its point is in a loaded chunk of a live cell, it appears
     * there now rather than at the next load.
     */
    public static void materializeIfLive(World anyServerWorld, SatelliteBase satellite) {
        SpaceSubsystem stack = Stellurgy.spaceSubsystem();
        if (stack == null) {
            return;
        }
        Optional<GalacticCoord> point = SatellitePlacement.standingPoint(UniverseRegistry.get(anyServerWorld),
                satellite, SpaceSubsystem.spaceClock());
        if (!point.isPresent()) {
            return;
        }
        int slotDim = stack.manager.slotDimOf(point.get());
        if (slotDim == SpaceManager.UNBOUND_SLOT) {
            return;
        }
        World world = net.minecraftforge.common.DimensionManager.getWorld(slotDim);
        double[] pos = CellWorldMapper.poseWorldOf(point.get());
        if (world != null && world.isBlockLoaded(new BlockPos(pos[0], pos[1], pos[2]))) {
            standUp(world, satellite, pos);
        }
    }

    private static void standUp(World world, SatelliteBase satellite, double[] pos) {
        AxisAlignedBB around = new AxisAlignedBB(pos[0] - 1d, pos[1] - 1d, pos[2] - 1d, pos[0] + 1d, pos[1] + 1d, pos[2] + 1d);
        for (EntitySatellite present : world.getEntitiesWithinAABB(EntitySatellite.class, around)) {
            if (present.getSatelliteId() == satellite.getId()) {
                return;
            }
        }
        world.spawnEntity(new EntitySatellite(world, satellite.getId(), pos[0], pos[1], pos[2]));
    }

    /** The cell a slot world is bound to, or empty for one bound to none. */
    private static Optional<GalacticCoord> cellOf(int slotDim) {
        SpaceSubsystem stack = Stellurgy.spaceSubsystem();
        if (stack == null) {
            return Optional.empty();
        }
        for (Map.Entry<GalacticCoord, Integer> bound : stack.manager.loadedCells().entrySet()) {
            if (bound.getValue() == slotDim) {
                return Optional.of(bound.getKey());
            }
        }
        return Optional.empty();
    }

    private static List<SatelliteBase> satellitesWithOrbits() {
        DimensionManager dims = DimensionManager.getInstance();
        Map<DimensionProperties, Boolean> seen = new IdentityHashMap<>();
        List<SatelliteBase> found = new ArrayList<>();
        List<DimensionProperties> all = new ArrayList<>();
        all.add(dims.getDimensionPropertiesOrNull(0));
        for (Integer id : dims.getRegisteredDimensions()) {
            all.add(dims.getDimensionPropertiesOrNull(id));
        }
        for (DimensionProperties props : all) {
            if (props == null || seen.put(props, Boolean.TRUE) != null) {
                continue;
            }
            for (SatelliteBase satellite : props.getAllSatellites()) {
                if (satellite.getOrbit().isPresent()) {
                    found.add(satellite);
                }
            }
        }
        return found;
    }
}
