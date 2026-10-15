package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import dev.stannismod.stellurgy.subsystem.ejection.EjectionPort;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * An ejection port RELEASING a stack: the item entity as production made it, at the moment it is
 * handed to the world, beside the motion of the hull the port stands on.
 *
 * <p>{@code ejection_released} carries the item's {@code motionX/Y/Z} and position {@code x/y/z},
 * the port's own {@code portX/Y/Z}, and — when the port is aboard a ship — that ship's {@code ship}
 * id and the substrate's OWN account of its motion at that instant: {@code hullVelX/Y/Z} (linear,
 * as the substrate holds it, which is blocks per SECOND), {@code hullOmegaX/Y/Z} (rad/s) and the
 * ship's centre {@code hullX/Y/Z}. The hull's motion is read here from the physics state rather
 * than from the carry reader the port itself calls, so a reader that converts wrongly cannot also be
 * the instrument that judges it. {@code ship} is {@code null} for a port on no ship, and the hull
 * fields are then absent. Read by
 * {@code VSShipMotionServerTest#anItemEjectedFromAMovingHullCarriesTheHullsMotionPerTick}.</p>
 *
 * <p>A redirect rather than an inject because the item IS the fact and the redirect is handed it as
 * production passes it; the spawn is performed exactly once, here, as production would have, and
 * the world's answer travels as {@code spawned}.</p>
 */
@Mixin(value = EjectionPort.class, remap = false)
public abstract class MixinEjectionPortEvents {

    private static final String INSTRUMENT = "ejection_port_events";

    @Redirect(method = "eject",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/World;spawnEntity(Lnet/minecraft/entity/Entity;)Z",
                    remap = false),
            require = 1, remap = false)
    private static boolean stellurgyTest$released(World spawnWorld, Entity item,
                                                  World world, BlockPos pos, EnumFacing facing,
                                                  ItemStack stack) {
        TestTrace.instrument(world, INSTRUMENT);
        // Described before the spawn, so the record holds the stack as the port threw it. The result is the
        // only local of this frame after the description: it keeps a slot of its own.
        String described = stellurgyTest$describe(item, world, pos);
        boolean spawned = spawnWorld.spawnEntity(item);
        TestTrace.record(world, "ejection_released", described + ",\"spawned\":" + spawned);
        return spawned;
    }

    private static String stellurgyTest$describe(Entity item, World world, BlockPos pos) {
        String shipId = VSIntegration.registeredShipIdManagingBlock(world, pos);
        StringBuilder payload = new StringBuilder()
                .append("\"portX\":").append(pos.getX())
                .append(",\"portY\":").append(pos.getY())
                .append(",\"portZ\":").append(pos.getZ())
                .append(",\"x\":").append(item.posX)
                .append(",\"y\":").append(item.posY)
                .append(",\"z\":").append(item.posZ)
                .append(",\"motionX\":").append(item.motionX)
                .append(",\"motionY\":").append(item.motionY)
                .append(",\"motionZ\":").append(item.motionZ);
        if (shipId == null) {
            payload.append(",\"ship\":null");
        } else {
            payload.append(",\"ship\":\"").append(TestTrace.json(shipId)).append('"');
            double[] state = VSIntegration.shipStateById(world, shipId);
            double[] omega = VSIntegration.shipAngularVelocityById(world, shipId);
            if (state != null && omega != null) {
                payload.append(",\"hullX\":").append(state[0])
                        .append(",\"hullY\":").append(state[1])
                        .append(",\"hullZ\":").append(state[2])
                        .append(",\"hullVelX\":").append(state[7])
                        .append(",\"hullVelY\":").append(state[8])
                        .append(",\"hullVelZ\":").append(state[9])
                        .append(",\"hullOmegaX\":").append(omega[0])
                        .append(",\"hullOmegaY\":").append(omega[1])
                        .append(",\"hullOmegaZ\":").append(omega[2]);
            }
        }
        return payload.toString();
    }
}
