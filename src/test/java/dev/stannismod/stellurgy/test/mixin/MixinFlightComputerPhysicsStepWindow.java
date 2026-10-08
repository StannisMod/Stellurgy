package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.physics.PhysicsCalculations;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;

import dev.stannismod.stellurgy.test.trace.PhysicsStepWindow;
import dev.stannismod.stellurgy.test.trace.TestTrace;
import dev.stannismod.stellurgy.tile.TileAdvancedFlightComputer;

/**
 * Feeds {@link PhysicsStepWindow} from the one place a tier-2 craft's motion is decided: the flight
 * computer's controller, which the physics solver hands every physics step of the ship it sits on.
 *
 * <p>Entry reads the solver's own state for the step it is about to run — the velocity it carries
 * in, the angular velocity, the mass and the step length — so the window measures the CRAFT, not the
 * command. Return reads the controller's own verdict, whether it delivered less than was asked.</p>
 *
 * <p>Both points announce themselves before anything is filtered, so an empty window can be told
 * from a controller that never ran.</p>
 */
@Mixin(value = TileAdvancedFlightComputer.class, remap = false)
public abstract class MixinFlightComputerPhysicsStepWindow {

    @Shadow
    private volatile boolean lastSaturated;

    @Inject(method = "onPhysicsTick", at = @At("HEAD"), remap = false, require = 1)
    private void stellurgyTest$physicsStepWindowEnter(PhysicsObject physo, PhysicsCalculations calc,
                                                      double dt, CallbackInfo ci) {
        TileEntity self = (TileEntity) (Object) this;
        TestTrace.instrument(self.getWorld(), "server_flight_controller_physics_step");
        if (self.getWorld() == null || self.getWorld().isRemote) {
            return;
        }
        BlockPos p = self.getPos();
        Vector3d v = calc.getLinearVelocity();
        Vector3d w = calc.getAngularVelocity();
        PhysicsStepWindow.enter(self.getWorld(), physo, p.getX(), p.getY(), p.getZ(), dt,
                v.x, v.y, v.z, w.x, w.y, w.z, calc.getMass());
    }

    @Inject(method = "onPhysicsTick", at = @At("RETURN"), remap = false, require = 1)
    private void stellurgyTest$physicsStepWindowReturn(PhysicsObject physo, PhysicsCalculations calc,
                                                       double dt, CallbackInfo ci) {
        TileEntity self = (TileEntity) (Object) this;
        if (self.getWorld() == null || self.getWorld().isRemote) {
            return;
        }
        BlockPos p = self.getPos();
        PhysicsStepWindow.returned(self.getWorld(), p.getX(), p.getY(), p.getZ(), lastSaturated);
    }
}
