package dev.stannismod.stellurgy.block;

import java.util.List;

import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import org.joml.Vector3d;

import dev.stannismod.stellurgy.api.capability.CapabilityWear;
import dev.stannismod.stellurgy.api.capability.IPartWear;
import dev.stannismod.stellurgy.libvulpes.block.BlockFullyRotatable;
import dev.stannismod.stellurgy.ship.control.Actuator;
import dev.stannismod.stellurgy.ship.control.ActuatorId;

/**
 * What a chemical rocket motor is to a ship: one force at the centre of its block, pushing away from
 * its nozzle.
 *
 * <p>The nozzle is the facing the motor is DRAWN with — its actual state, which follows the tank it
 * is fed from — so the push a pilot gets is the push the model on screen shows. Two motor families
 * share this and do not share a superclass, which is the only reason it is a helper and not an
 * inherited method.</p>
 */
final class ChemicalMotorActuators {

    private ChemicalMotorActuators() {}

    static void add(World world, BlockPos pos, IBlockState state, double thrustNewtons,
                    List<Actuator> out) {
        EnumFacing nozzle = state.getBlock().getActualState(state, world, pos)
                .getValue(BlockFullyRotatable.FACING);
        Vec3i d = nozzle.getDirectionVec();
        out.add(Actuator.pointForce(new ActuatorId(pos.getX(), pos.getY(), pos.getZ(), 0),
                new Vector3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D),
                new Vector3d(-d.getX() * thrustNewtons, -d.getY() * thrustNewtons,
                        -d.getZ() * thrustNewtons)));
    }

    /** A motor worn to its last stage is broken and does not fire; anything short of that does. */
    static boolean working(World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        IPartWear wear = CapabilityWear.get(te);
        return wear == null || wear.getStage() < wear.getMaxStage();
    }
}
