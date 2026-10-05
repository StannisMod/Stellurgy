package dev.stannismod.stellurgy.ship.control;

import java.util.List;

import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The default scheme: split the wanted motion along the pilot's six axes, deliver each with that
 * axis's cached clean recipe, and scale the whole command down together when the recipes, summed,
 * ask some actuator for more than it has.
 *
 * <p>Four decisions live here and nowhere else:</p>
 * <ul>
 *   <li><b>Per axis, the wanted amount becomes a fraction of that axis's authority</b> and is clipped
 *       at one. The flight law asks in physical units; the recipes are per unit of authority.</li>
 *   <li><b>Sustained first.</b> An axis whose demand the indefinitely-holdable devices can meet is
 *       met by them alone, so a stored-momentum device is wound up only when the pilot asks for more
 *       than the hull can hold — never as a side effect of ordinary flight.</li>
 *   <li><b>One global factor.</b> Recipes summed may drive an actuator past its range; the command is
 *       scaled by the single factor that brings the worst one back inside, which keeps the requested
 *       proportions. Priority between axes (attitude before translation, say) would be a different
 *       scheme, not a change to this one.</li>
 *   <li><b>Idle wheels are given back.</b> A wheel the command does not use is run toward empty while
 *       the sustained devices cancel its moment, in whatever room the command left — see
 *       {@link #desaturate}.</li>
 * </ul>
 */
final class CleanAxisScheme implements ControlScheme {

    @Override
    public ActuatorCommand allocate(ShipCapability capability, Vector3dc acceleration,
                                    Vector3dc angularAcceleration, MomentumStore momentum,
                                    double dt) {
        int n = capability.actuators().size();
        double[] u = new double[n];
        boolean saturated = false;
        double mass = capability.mass().getTotalMass();
        for (ControlAxis axis : ControlAxis.values()) {
            Vector3dc e = capability.frame().axis(axis);
            double want = axis.isRotation() ? angularAcceleration.dot(e) : mass * acceleration.dot(e);
            if (want == 0.0D || Double.isNaN(want)) {
                continue;
            }
            ControlDirection direction = ControlDirection.of(axis, want > 0.0D);
            double amount = Math.abs(want);
            Recipe recipe = capability.recipe(direction, Endurance.SUSTAINED);
            if (amount > recipe.authority) {
                recipe = burstIfItCanDeliver(capability, direction, recipe, momentum, dt);
            }
            if (!(recipe.authority > 0.0D)) {
                saturated = true;
                continue;
            }
            double fraction = amount / recipe.authority;
            if (fraction > 1.0D) {
                fraction = 1.0D;
                saturated = true;
            }
            for (int i = 0; i < n; i++) {
                u[i] += fraction * recipe.throttles[i];
            }
        }

        double lambda = 1.0D;
        for (int i = 0; i < n; i++) {
            double min = capability.actuators().get(i).minThrottle();
            if (u[i] > 1.0D) {
                lambda = Math.min(lambda, 1.0D / u[i]);
            } else if (u[i] < min) {
                lambda = Math.min(lambda, min / u[i]);
            }
        }
        if (lambda < 1.0D) {
            saturated = true;
            for (int i = 0; i < n; i++) {
                u[i] *= lambda;
            }
        }

        double[][] live = momentum.liveRange(capability.actuators(), dt);
        for (int i = 0; i < n; i++) {
            if (u[i] > live[i][1]) {
                u[i] = live[i][1];
                saturated = true;
            } else if (u[i] < live[i][0]) {
                u[i] = live[i][0];
                saturated = true;
            }
        }

        desaturate(capability, u, momentum, dt);

        ActuatorCommand command = new ActuatorCommand(u, capability.wrench(u), saturated);
        momentum.absorb(capability.actuators(), command, dt);
        return command;
    }

    /**
     * Give back the momentum the wheels hold, with the sustained devices holding the hull still while
     * they do.
     *
     * <p>A wheel buys authority for a while and then is full; what empties it is an EXTERNAL torque,
     * and on a hull that is a thruster pair. So each wheel the command left idle is run back toward
     * empty — never past it — and the torque that would put on the hull is cancelled by the clean
     * rotation recipes of the devices that can run forever. The net wrench of the two is zero: the
     * pilot feels nothing, and the wheel is ready again.</p>
     *
     * <p>Three things it does not do. It never touches a wheel the command is using, so a burst is
     * not fought by its own unloading. It takes only the room the command left in every throttle, by
     * one common factor, so the pilot's command is never scaled for it. And a wheel whose moment
     * nothing aboard can cancel keeps what it holds: unloading it would turn the hull.</p>
     */
    private static void desaturate(ShipCapability capability, double[] u, MomentumStore momentum,
                                   double dt) {
        List<Actuator> actuators = capability.actuators();
        int n = actuators.size();
        Matrix3d inverseInertia = new Matrix3d(capability.mass().getInertia()).invert();
        if (!inverseInertia.isFinite()) {
            return;
        }
        double[] delta = new double[n];
        boolean any = false;
        for (int i = 0; i < n; i++) {
            Actuator wheel = actuators.get(i);
            if (wheel.isSustained() || u[i] != 0.0D) {
                continue;
            }
            double held = momentum.given(wheel.id());
            double rate = wheel.maxTorque().length() * dt;
            if (held == 0.0D || !(rate > 0.0D)) {
                continue;
            }
            double throttle = Math.max(wheel.minThrottle(), Math.min(1.0D, -held / rate));
            Vector3d cancel = new Vector3d(wheel.maxTorque()).mul(-throttle);
            double[] holding = sustainedTorque(capability, inverseInertia, cancel);
            if (holding == null) {
                continue;
            }
            delta[i] += throttle;
            for (int k = 0; k < n; k++) {
                delta[k] += holding[k];
            }
            any = true;
        }
        if (!any) {
            return;
        }
        double scale = 1.0D;
        for (int i = 0; i < n; i++) {
            if (delta[i] > 0.0D) {
                scale = Math.min(scale, (1.0D - u[i]) / delta[i]);
            } else if (delta[i] < 0.0D) {
                scale = Math.min(scale, (actuators.get(i).minThrottle() - u[i]) / delta[i]);
            }
        }
        if (!(scale > 0.0D)) {
            return;
        }
        for (int i = 0; i < n; i++) {
            u[i] += scale * delta[i];
        }
    }

    /**
     * The sustained throttles that put exactly {@code torque} (N·m, ship frame) on the hull and push
     * it nowhere, composed from the clean rotation recipes; {@code null} when a rotation it needs has
     * no sustained authority. May exceed the throttle range — the caller scales.
     */
    private static double[] sustainedTorque(ShipCapability capability, Matrix3d inverseInertia,
                                            Vector3dc torque) {
        // A rotation recipe delivers torque I·axis per rad/s² of authority, so the angular
        // acceleration this torque makes, split along the frame's axes, is how much of each to use.
        Vector3d alpha = inverseInertia.transform(new Vector3d(torque));
        double[] throttles = new double[capability.actuators().size()];
        for (ControlAxis axis : ControlAxis.values()) {
            if (!axis.isRotation()) {
                continue;
            }
            double want = alpha.dot(capability.frame().axis(axis));
            if (Math.abs(want) <= AllocationTolerances.FORCE_RESIDUAL * alpha.length()) {
                continue;
            }
            Recipe recipe = capability.recipe(ControlDirection.of(axis, want > 0.0D), Endurance.SUSTAINED);
            if (!(recipe.authority > 0.0D)) {
                return null;
            }
            double fraction = Math.abs(want) / recipe.authority;
            for (int k = 0; k < throttles.length; k++) {
                throttles[k] += fraction * recipe.throttles[k];
            }
        }
        return throttles;
    }

    /**
     * The burst recipe in {@code direction} — unless a stored-momentum device it leans on is already
     * full in the sense the recipe needs, in which case the sustained recipe.
     *
     * <p>A burst recipe is balanced only as a whole: its engines fire off-centre BECAUSE a wheel nulls
     * their moment. Once that wheel is full it is clipped to nothing, the engines keep firing, and the
     * moment they make is no longer nulled by anything — the craft tumbles under a command that asked
     * for a straight push. The sustained recipe is clean by construction and delivers less, which is
     * the honest reading of a spent wheel: authority for N seconds, then the holdable figure.</p>
     */
    private static Recipe burstIfItCanDeliver(ShipCapability capability, ControlDirection direction,
                                              Recipe sustained, MomentumStore momentum, double dt) {
        Recipe burst = capability.recipe(direction, Endurance.BURST);
        double[][] live = momentum.liveRange(capability.actuators(), dt);
        for (int i = 0; i < burst.throttles.length; i++) {
            if (capability.actuators().get(i).isSustained()) {
                continue;
            }
            double t = burst.throttles[i];
            if ((t > 0.0D && !(live[i][1] > 0.0D)) || (t < 0.0D && !(live[i][0] < 0.0D))) {
                return sustained;
            }
        }
        return burst;
    }
}
