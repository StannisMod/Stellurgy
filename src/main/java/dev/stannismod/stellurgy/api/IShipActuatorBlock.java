package dev.stannismod.stellurgy.api;

import java.util.List;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import dev.stannismod.stellurgy.ship.control.Actuator;

/**
 * A block that can push or turn the ship it is built into.
 *
 * <p>The block does not decide what the ship can do; it only says what it contributes — a force at
 * its own position, a torque, the range of each — and the ship's flight model solves what the whole
 * hull delivers from every contribution together. That is why placement matters, and why the same
 * engine is strong on one hull and useless on another.</p>
 *
 * <p>The chemical rocket motors implement it; a reaction wheel implements it; a device that does not
 * exist yet implements it without anything in the flight model changing.</p>
 */
public interface IShipActuatorBlock {

    /**
     * Add this block's actuators, as built, to {@code out}, positioned and identified in the frame
     * {@code pos} is in — the caller walks the ship in its own frame and asks each block there.
     *
     * <p>Contributes whether or not the block works right now: what it WOULD give is the design
     * figure, and {@link #isWorking} says whether it gives it today.</p>
     */
    void addActuators(World world, BlockPos pos, IBlockState state, List<Actuator> out);

    /**
     * Whether this block delivers its actuators right now. A broken device is still aboard and still
     * counted in the design; it simply does not push.
     */
    boolean isWorking(World world, BlockPos pos, IBlockState state);
}
