package dev.stannismod.stellurgy.test.trace;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * The payload of a {@code periodic_gate_opened} record, one shape for every tile that gates on a clock: the tile's
 * kind ({@code tile}), its place ({@code dim}, {@code x}, {@code y}, {@code z}) and both clocks of its world at
 * the opening — {@code totalTime} and {@code timeOfDay} ({@code getWorldTime}). Outside the mixin package, which a
 * mixin may not reference directly.
 */
public final class PeriodicGateState {

    private PeriodicGateState() {
    }

    /** {@code tile}'s kind, place and both clocks of its world now, without braces. */
    public static String fields(String kind, TileEntity tile) {
        World world = tile.getWorld();
        return "\"tile\":\"" + kind + "\""
                + ",\"dim\":" + world.provider.getDimension()
                + ",\"x\":" + tile.getPos().getX() + ",\"y\":" + tile.getPos().getY() + ",\"z\":" + tile.getPos().getZ()
                + ",\"totalTime\":" + world.getTotalWorldTime()
                + ",\"timeOfDay\":" + world.getWorldTime();
    }
}
