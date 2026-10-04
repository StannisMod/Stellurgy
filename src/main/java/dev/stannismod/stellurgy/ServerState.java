package dev.stannismod.stellurgy;

import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;

import net.minecraft.world.World;

import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.event.PlanetEventHandler;
import dev.stannismod.stellurgy.integration.vs.DeckHold;
import dev.stannismod.stellurgy.integration.vs.DeckMovementBound;
import dev.stannismod.stellurgy.integration.vs.ShipLocalMoveControl;
import dev.stannismod.stellurgy.space.HyperspaceWorld;
import dev.stannismod.stellurgy.space.SpaceSlotPool;
import dev.stannismod.stellurgy.stations.SpaceObjectManager;
import dev.stannismod.stellurgy.unit.IngameTestOrchestrator;
import dev.stannismod.stellurgy.util.AtmosphereBlob;
import dev.stannismod.stellurgy.util.DelayedActionBar;
import dev.stannismod.stellurgy.util.OreGenTable;
import dev.stannismod.stellurgy.util.RocketGuiNavigation;
import dev.stannismod.stellurgy.util.RocketInventoryHelper;
import dev.stannismod.stellurgy.wirelessdata.HandlerDataNetwork;
import dev.stannismod.stellurgy.wirelessdata.WirelessNetworkSavedData;

/**
 * What one running server owns and nothing more specific does: its galaxy, its stations, its space
 * clock, its hyperspace world and its space-slot pool. Built when a server is about to start and
 * dropped when it has stopped; everything inside is this object's own state, so nothing of one server
 * is reachable from the next.
 */
public final class ServerState {

    public final DimensionManager dimensions;
    public final SpaceObjectManager spaceObjects;
    public final HyperspaceWorld hyperspace = new HyperspaceWorld();
    public final SpaceSlotPool slots = new SpaceSlotPool();
    /** The ore palette per climate cell, filled from {@code oreConfig.xml} when the server starts. */
    public final OreGenTable oreTable = new OreGenTable();
    /** Action-bar lines queued a few ticks out. */
    public final DelayedActionBar actionBar = new DelayedActionBar();
    /** Players whose rocket GUI may stay open past the vanilla interaction range. */
    public final RocketInventoryHelper rocketInventory = new RocketInventoryHelper();
    /** Where each player stepped from a rocket's GUI into one of its tiles' GUIs. */
    public final RocketGuiNavigation rocketGuiReturns = new RocketGuiNavigation();
    /** The deck holds pinning returning and carried crew to their ships. */
    public final DeckHold.Holds deckHolds = new DeckHold.Holds();
    /** The {@code Entity.move} takeover experiment, armed by the test probe for one entity. */
    public final ShipLocalMoveControl shipLocalMove = new ShipLocalMoveControl();
    /** When each player's deck movement was last judged. */
    public final DeckMovementBound deckMovement = new DeckMovementBound();
    /** The planet event handler's tick count and owed delayed transitions. */
    public final PlanetEventHandler.ServerPart planetEvents = new PlanetEventHandler.ServerPart();
    /** Login seatings, held slot cells and ship-lost notices queued for this server's players. */
    public final dev.stannismod.stellurgy.space.SpaceEventHandler.ServerPart spaceEvents =
            new dev.stannismod.stellurgy.space.SpaceEventHandler.ServerPart();
    /** How long each player has been adrift in this server's hyperspace. */
    public final dev.stannismod.stellurgy.space.HyperspaceVoid.ServerPart hyperspaceVoid =
            new dev.stannismod.stellurgy.space.HyperspaceVoid.ServerPart();
    /** The pending steps of the developer command {@code runtests}. */
    public final IngameTestOrchestrator ingameTests = new IngameTestOrchestrator();
    /** The executor the threaded atmosphere fill runs on; shut down by {@link #release()}. */
    public final ThreadPoolExecutor atmosphereFillPool = AtmosphereBlob.newFillPool();

    /**
     * The space clock, in ticks: advanced once per server tick, restored from the save on server
     * start. Not any world's counter — the overworld's is the only one that advances unconditionally,
     * and none is resolvable around server start and stop.
     */
    private long spaceTick;

    /** The wireless data networks, over the overworld's saved data; built when the overworld loads. */
    private HandlerDataNetwork wirelessNetworks;

    ServerState(int minDimension) {
        this.dimensions = new DimensionManager(minDimension);
        this.spaceObjects = new SpaceObjectManager();
    }

    public long spaceTick() {
        return spaceTick;
    }

    /**
     * One server tick of the space clock. Called from exactly one place, the space subsystem's server
     * tick handler — two writers on the same event would run the clock at twice the tick rate and
     * nothing would report it.
     */
    public void advanceSpaceClock() {
        spaceTick++;
    }

    /**
     * Put the space clock at {@code tick}: the restore on server start, and a test aging the universe
     * by arithmetic instead of by waiting — which, unlike a world's counter, moves no world, so a
     * shared server's day cycle and every {@code totalTime % N} gate stay where they were.
     */
    public void setSpaceClock(long tick) {
        spaceTick = tick;
    }

    /**
     * This server's wireless data networks. {@code world} is any of its server worlds: the networks
     * live on the overworld's saved data whichever world asks first.
     */
    public HandlerDataNetwork wirelessNetworks(World world) {
        if (wirelessNetworks == null) {
            wirelessNetworks = new HandlerDataNetwork(WirelessNetworkSavedData.get(world));
        }
        return wirelessNetworks;
    }

    /** One server tick of the wireless networks, once the overworld has brought them up. */
    public void tickWirelessNetworks() {
        if (wirelessNetworks != null) {
            wirelessNetworks.tickAllNetworks();
        }
    }

    /** The player has left this server: drop what it keeps keyed by him. */
    void playerLoggedOut(UUID playerId) {
        rocketGuiReturns.forget(playerId);
        deckMovement.forget(playerId);
    }

    /** Withdraw the Forge dimension registrations this server made: its planets, slots and hyperspace. */
    void release() {
        atmosphereFillPool.shutdownNow();
        dimensions.unregisterAllDimensions();
        slots.release();
        hyperspace.release();
    }
}
