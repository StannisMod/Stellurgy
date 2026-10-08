package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.util.math.BlockPos;

import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.physics.IPhysicsBlockController;
import org.valkyrienskies.mod.common.physics.PhysicsCalculations;
import org.valkyrienskies.mod.common.ships.ship_transform.ShipTransform;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;

import dev.stannismod.stellurgy.test.trace.MotionTrace;
import dev.stannismod.stellurgy.test.trace.SideTrace;
import dev.stannismod.stellurgy.tile.TileAdvancedFlightComputer;

/**
 * One flight-recorder sample per PHYSICS STEP of a flight computer — the clock the ship's velocity
 * actually integrates on, and NOT the game tick: an interval that wanders here is the physics loop
 * failing to hold its rate, which no server-side tick measurement can see.
 *
 * <h2>Where it is taken, and why there</h2>
 *
 * <p>Around the physics loop's own call into each controller, so the flight computer's control
 * law carries no instrumentation of its own. A step is sampled only when the controller APPLIED a
 * force during it — the controller returns early, applying nothing, when it has no command at all
 * — so a craft with nothing to do leaves a hole here exactly as it did when the sample sat inside
 * the controller after that early return.</p>
 *
 * <p>Everything recorded is read after the controller ran and before the solver integrates:
 * {@code addForceAndTorque} only accumulates, so the velocity and the physics pose read here are
 * the ones the controller itself read.</p>
 *
 * <p>Both sides (the physics loop runs where the server does). Test source set.</p>
 */
@Mixin(value = PhysicsCalculations.class, remap = false)
public abstract class MixinPhysicsCalculationsMotionSample {

    /** How many forces this step's controllers have applied so far. Physics thread only. */
    @Unique
    private int stellurgyTest$forcesApplied;

    @Inject(method = "addForceAndTorque", at = @At("HEAD"))
    private void stellurgyTest$noteForce(Vector3dc force, Vector3dc torque, CallbackInfo ci) {
        stellurgyTest$forcesApplied++;
    }

    @Redirect(method = "calculateForces",
            at = @At(value = "INVOKE",
                    target = "Lorg/valkyrienskies/mod/common/physics/IPhysicsBlockController;"
                            + "onPhysicsTick(Lorg/valkyrienskies/mod/common/ships/ship_world/"
                            + "PhysicsObject;Lorg/valkyrienskies/mod/common/physics/"
                            + "PhysicsCalculations;D)V"))
    private void stellurgyTest$sampleFlightComputer(IPhysicsBlockController controller,
                                                    PhysicsObject physo, PhysicsCalculations calc,
                                                    double dt) {
        int before = stellurgyTest$forcesApplied;
        controller.onPhysicsTick(physo, calc, dt);
        if (stellurgyTest$forcesApplied == before
                || !(controller instanceof TileAdvancedFlightComputer)) {
            return;
        }
        TileAdvancedFlightComputer self = (TileAdvancedFlightComputer) controller;
        // The PHYSICS transform, not the game-tick one. They are different objects and only one of
        // them advances on this clock: sampling `getShipTransform()` from a 60 Hz hook gives a pose
        // that only changes 20 times a second, so two thirds of the samples read as "did not move"
        // and the rest as a triple step — a metronomic ship reported as a stuttering one, by the
        // instrument alone. Measured on the first calibration run: median step 0.0, p95 2.0.
        ShipTransform pose = physo.getShipTransformationManager().getCurrentPhysicsTransform();
        Vector3d vNow = calc.getLinearVelocity();
        // Each reference read once, as the controller reads them.
        dev.stannismod.stellurgy.ship.control.FlightCommand command = self.probeCommand;
        if (command == null) {
            command = self.flightCommand;
        }
        double[] vCmd = command == null ? null : command.velocity();
        double cmdSpeed = vCmd == null || vCmd.length < 3 ? 0.0
                : Math.sqrt(vCmd[0] * vCmd[0] + vCmd[1] * vCmd[1] + vCmd[2] * vCmd[2]);
        double mass = calc.getMass();
        BlockPos at = self.getPos();
        SideTrace.of(physo.getWorld()).motion().phys(
                MotionTrace.keyOf(physo.getWorld().provider.getDimension(),
                        at.getX(), at.getY(), at.getZ()),
                // The WORLD tick this physics step ran against, read across the thread boundary as
                // a plain long. This loop runs on the physics thread at its own rate, so several
                // steps share one world tick and the healthy reading is that every tick got at
                // least one — never that each carried exactly one. A counter incremented here
                // would count steps and call them ticks, and could not show a missed tick at all.
                physo.getWorld().getTotalWorldTime(),
                // WHO drove this step, and on WHICH ship. A block's flight computer is one object on
                // one ship, so a window carrying two of either is a state to go and look at rather
                // than a number to average — and the two cases have different causes: two
                // controllers on one ship is a stale tile instance that outlived its replacement in
                // the ship's controller set, two ships is two craft claiming one computer.
                System.identityHashCode(self),
                physo.getUuid() == null ? 0.0 : physo.getUuid().hashCode(),
                dt, pose.getPosX(), pose.getPosY(), pose.getPosZ(),
                Math.sqrt(vNow.x * vNow.x + vNow.y * vNow.y + vNow.z * vNow.z),
                cmdSpeed, mass,
                // "Clamped": the hull delivered less than the flight law asked for, as the controller
                // itself judged it this step. At its authority it is no longer tracking its command,
                // which is the fact worth recording; there is no fixed ceiling to compare against,
                // because the authority is whatever the hull's actuators sum to.
                self.isDeliveringLessThanAsked());
    }
}
