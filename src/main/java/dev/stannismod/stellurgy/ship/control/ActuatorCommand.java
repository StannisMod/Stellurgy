package dev.stannismod.stellurgy.ship.control;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * One tick's worth of actuator throttles, and the wrench they add up to.
 *
 * <p>The wrench is what gets applied to the body; the throttles are what the devices were told, and
 * are kept because a stored-momentum device's state follows from its own throttle, not from the
 * hull's total.</p>
 */
public final class ActuatorCommand {

    private final double[] throttles;
    private final Vector3dc force;
    private final Vector3dc torque;
    private final boolean saturated;
    private final java.util.Map<String, Double> ejectedKg;

    ActuatorCommand(double[] throttles, double[] wrench, boolean saturated, java.util.Map<String, Double> ejectedKg) {
        this.throttles = throttles;
        this.force = new Vector3d(wrench[0], wrench[1], wrench[2]);
        this.torque = new Vector3d(wrench[3], wrench[4], wrench[5]);
        this.saturated = saturated;
        this.ejectedKg = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(ejectedKg));
    }

    /**
     * The mass this command ejects over the step it was allocated for, kg per fluid (C10 STAT-39): what
     * the drain must take out of the pool when consumption is metered.
     */
    public java.util.Map<String, Double> ejectedKg() {
        return ejectedKg;
    }

    /** The throttle of the {@code index}-th actuator of the capability this command was allocated over. */
    public double throttle(int index) {
        return throttles[index];
    }

    /** The net force, in newtons, in the ship frame. */
    public Vector3dc force() {
        return force;
    }

    /** The net torque about the centre of mass, in N·m, in the ship frame. */
    public Vector3dc torque() {
        return torque;
    }

    /**
     * Whether the hull delivered less than was asked: an axis asked for more than its authority, the
     * composed command had to be scaled down, or a stored-momentum device was full.
     */
    public boolean isSaturated() {
        return saturated;
    }
}
