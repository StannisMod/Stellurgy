package dev.stannismod.stellurgy;

import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.space.HyperspaceWorld;
import dev.stannismod.stellurgy.space.SpaceSlotPool;
import dev.stannismod.stellurgy.stations.SpaceObjectManager;

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

    /**
     * The space clock, in ticks: advanced once per server tick, restored from the save on server
     * start. Not any world's counter — the overworld's is the only one that advances unconditionally,
     * and none is resolvable around server start and stop.
     */
    private long spaceTick;

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

    /** Withdraw the Forge dimension registrations this server made: its planets, slots and hyperspace. */
    void release() {
        dimensions.unregisterAllDimensions();
        slots.release();
        hyperspace.release();
    }
}
