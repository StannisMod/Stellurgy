package dev.stannismod.stellurgy.block;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.joml.Vector3d;

import dev.stannismod.stellurgy.api.IShipActuatorBlock;
import dev.stannismod.stellurgy.ship.control.Actuator;
import dev.stannismod.stellurgy.ship.control.ActuatorId;
import dev.stannismod.stellurgy.tile.TileReactionWheel;

/**
 * A reaction wheel: turns the ship it is built into about any axis, pushes it nowhere, and cannot
 * hold a turn forever.
 *
 * <p>Three torque devices in one block, one per axis of the ship's frame, each taking the momentum it
 * gives the hull into its own spin until it is full. Where on the hull it sits does not matter — a
 * pure torque has no lever arm — which is exactly why it is the device that lets a hull turn whose
 * engines cannot balance a rotation.</p>
 */
public class BlockReactionWheel extends Block implements IShipActuatorBlock {

    /**
     * Torque per axis at full throttle, N·m. `tunable`. The relation it was chosen by: one wheel
     * gives the reference deck ship (the pilot-deck test craft, actuated) the angular authority every
     * craft used to be granted by a constant, 4 rad/s² in the engine's units, i.e. 1.23 rad/s², about
     * its heaviest axis. Measured 2026-09-30 through the craft's flight model: yaw inertia 1.54e6
     * kg·m² (roll 1.23e6, pitch 1.21e6) at 281 t; 1.54e6 × 1.23 = 1.9e6. The first figure, 6.5e5,
     * was set from an estimate three times too low and is superseded.
     */
    public static final double TORQUE = 1_900_000.0D;

    /**
     * Momentum each axis can store, N·m·s. `tunable`. Same relation: the rate the old attitude law
     * capped every craft at, 2 rad/s in the engine's units (1.11 rad/s), on that same measured yaw
     * inertia: 1.54e6 × 1.11 = 1.7e6.
     */
    public static final double MOMENTUM_CAPACITY = 1_700_000.0D;

    public BlockReactionWheel(Material material) {
        super(material);
    }

    @Override
    public void addActuators(World world, BlockPos pos, IBlockState state, List<Actuator> out) {
        out.add(Actuator.pureTorque(new ActuatorId(pos.getX(), pos.getY(), pos.getZ(), 0),
                new Vector3d(TORQUE, 0.0D, 0.0D), MOMENTUM_CAPACITY));
        out.add(Actuator.pureTorque(new ActuatorId(pos.getX(), pos.getY(), pos.getZ(), 1),
                new Vector3d(0.0D, TORQUE, 0.0D), MOMENTUM_CAPACITY));
        out.add(Actuator.pureTorque(new ActuatorId(pos.getX(), pos.getY(), pos.getZ(), 2),
                new Vector3d(0.0D, 0.0D, TORQUE), MOMENTUM_CAPACITY));
    }

    /** Nothing wears a wheel out yet; it works while it is aboard. */
    @Override
    public boolean isWorking(World world, BlockPos pos, IBlockState state) {
        return true;
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileReactionWheel();
    }
}
