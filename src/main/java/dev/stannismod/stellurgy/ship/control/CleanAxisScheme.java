package dev.stannismod.stellurgy.ship.control;

import org.joml.Vector3dc;

/**
 * The default scheme: split the wanted motion along the pilot's six axes, deliver each with that
 * axis's cached clean recipe, and scale the whole command down together when the recipes, summed,
 * ask some actuator for more than it has.
 *
 * <p>Three decisions live here and nowhere else:</p>
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

        ActuatorCommand command = new ActuatorCommand(u, capability.wrench(u), saturated);
        momentum.absorb(capability.actuators(), command, dt);
        return command;
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
