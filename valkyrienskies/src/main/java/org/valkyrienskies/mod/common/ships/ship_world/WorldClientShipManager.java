package org.valkyrienskies.mod.common.ships.ship_world;

import com.google.common.collect.ImmutableList;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.valkyrienskies.mod.common.config.VSConfig;
import org.valkyrienskies.mod.common.ships.QueryableShipData;
import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.util.multithreaded.CalledFromWrongThreadException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

public class WorldClientShipManager implements IPhysObjectWorld {

    private final World world;
    private final Map<UUID, PhysicsObject> loadedShips;
    /**
     * The server's LATEST load/unload instruction per ship since the last tick: true = loaded,
     * false = unloaded. One map and not a load queue beside an unload queue, because the server sends
     * an unload and the later reload of the same ship in two messages on different ticks, and a client
     * that falls behind drains both before its next tick. Two queues drained loads-first then turned
     * "unloaded, then loaded again" into "unloaded": the load was skipped as already loaded, the
     * unload then dropped the ship, and the server, which still counts this client as watching it,
     * never sends another load.
     */
    private final LinkedHashMap<UUID, Boolean> pendingLoadState;
    private ImmutableList<PhysicsObject> threadSafeLoadedShips;
    /** Effectively final, process lifetime: built once at class initialisation. */
    private static final Logger logger = LogManager.getLogger();

    public WorldClientShipManager(World world) {
        this.world = world;
        this.loadedShips = new HashMap<>();
        this.pendingLoadState = new LinkedHashMap<>();
        this.threadSafeLoadedShips = ImmutableList.of();
    }

    private void enforceGameThread() throws CalledFromWrongThreadException {
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread()) {
            throw new CalledFromWrongThreadException("Wrong thread calling code: " + Thread.currentThread());
        }
    }

    @Override
    public void tick() {
        loadAndUnloadShips();

        for (PhysicsObject physicsObject : getAllLoadedPhysObj()) {
            physicsObject.onTick();
        }

        // Update the thread safe ship list.
        this.threadSafeLoadedShips = ImmutableList.copyOf(loadedShips.values());
    }

    private void loadAndUnloadShips() {
        QueryableShipData queryableShipData = QueryableShipData.get(world);
        for (final Map.Entry<UUID, Boolean> pending : pendingLoadState.entrySet()) {
            if (pending.getValue()) {
                loadShip(queryableShipData, pending.getKey());
            } else {
                unloadShip(pending.getKey());
            }
        }
        pendingLoadState.clear();
    }

    private void loadShip(QueryableShipData queryableShipData, UUID toLoadID) {
        // Already loaded is the state asked for. It is reached when an unload and the reload after it
        // arrive within one tick: the unload is superseded, and the object kept is current, because the
        // reloaded ship's chunks reached it through updateChunk while it was still loaded. Unloading
        // and rebuilding it instead would discard exactly those chunks — the client unload drops every
        // claimed chunk from the provider, and the server does not send them again.
        if (loadedShips.containsKey(toLoadID)) {
            return;
        }
        Optional<ShipData> toLoadOptional = queryableShipData.getShip(toLoadID);
        if (!toLoadOptional.isPresent()) {
            logger.error("No ship found for UUID:\n" + toLoadID);
            return;
        }
        ShipData shipData = toLoadOptional.get();

        PhysicsObject physicsObject = new PhysicsObject(world, shipData);

        for (final Chunk chunk : physicsObject.getClaimedChunkCache()) {
            chunk.loaded = true;
        }

        loadedShips.put(toLoadID, physicsObject);
        if (VSConfig.showAnnoyingDebugOutput) {
            System.out.println("Successfully loaded " + shipData);
        }
    }

    private void unloadShip(UUID toUnloadID) {
        // Not loaded is the state asked for: the mirror of loadShip, reached when a load and the
        // unload after it arrive within one tick and the load was superseded before it ran.
        PhysicsObject removedShip = loadedShips.remove(toUnloadID);
        if (removedShip == null) {
            return;
        }
        removedShip.unload();
        if (VSConfig.showAnnoyingDebugOutput) {
            System.out.println("Successfully unloaded " + removedShip.getShipData());
        }
    }

    @Override
    public void onWorldUnload() {
        loadedShips.clear();
    }

    @Nullable
    @Override
    public PhysicsObject getPhysObjectFromUUID(@Nonnull UUID shipID) throws CalledFromWrongThreadException {
        enforceGameThread();
        return loadedShips.get(shipID);
    }

    @Nonnull
    @Override
    public List<PhysicsObject> getPhysObjectsInAABB(@Nonnull AxisAlignedBB toCheck) throws CalledFromWrongThreadException {
        enforceGameThread();
        List<PhysicsObject> nearby = new ArrayList<>();
        for (PhysicsObject physicsObject : getAllLoadedPhysObj()) {
            if (toCheck.intersects(physicsObject.getShipBB())) {
                nearby.add(physicsObject);
            }
        }
        return nearby;
    }

    @Nonnull
    @Override
    public Iterable<PhysicsObject> getAllLoadedPhysObj() throws CalledFromWrongThreadException {
        enforceGameThread();
        return loadedShips.values();
    }

    @Nonnull
    @Override
    public ImmutableList<PhysicsObject> getAllLoadedThreadSafe() {
        return threadSafeLoadedShips;
    }

    @Override
    public void queueShipLoad(@Nonnull UUID shipID) {
        enforceGameThread();
        pendingLoadState.put(shipID, true);
    }

    @Override
    public void queueShipUnload(@Nonnull UUID shipID) {
        enforceGameThread();
        pendingLoadState.put(shipID, false);
    }

    @Nonnull
    @Override
    public World getWorld() {
        return world;
    }
}
