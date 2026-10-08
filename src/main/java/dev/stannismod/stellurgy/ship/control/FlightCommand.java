package dev.stannismod.stellurgy.ship.control;

import java.util.Arrays;

/**
 * What a flight computer tells its craft to do for as long as the command stands: a world-frame
 * velocity to hold, an angular velocity, an attitude. Each part may be absent — no velocity is a coast,
 * no attitude is no hold — and a command with no part at all asks for nothing.
 *
 * <p>The three parts are one value because they are one decision: an attitude target and the rate it
 * is turning at are computed together, and a velocity is computed in the attitude it was asked in.
 * The game thread publishes a command; the physics thread flies it on every step until the next one,
 * so the parts must reach it together or a step flies half of one command and half of another.
 * Immutable for that reason: the arrays are copied in and copied out.</p>
 */
public final class FlightCommand {

    private final double[] velocity;
    private final double[] angularVelocity;
    private final double[] attitude;

    private FlightCommand(double[] velocity, double[] angularVelocity, double[] attitude) {
        this.velocity = velocity;
        this.angularVelocity = angularVelocity;
        this.attitude = attitude;
    }

    /**
     * @param velocity        world-frame velocity to hold, blocks/s, three components; {@code null} to coast
     * @param angularVelocity world-frame angular velocity, rad/s, three components; with an attitude it is
     *                        the rate that attitude is turning at; {@code null} for none
     * @param attitude        body-to-world quaternion {@code w, x, y, z}; {@code null} for no hold
     * @throws IllegalArgumentException if a part present has the wrong number of components
     */
    public static FlightCommand of(double[] velocity, double[] angularVelocity, double[] attitude) {
        return new FlightCommand(copy(velocity, 3, "velocity"), copy(angularVelocity, 3, "angular velocity"),
                copy(attitude, 4, "attitude"));
    }

    /** The velocity to hold, a fresh array, or {@code null} for a coast. */
    public double[] velocity() {
        return velocity == null ? null : velocity.clone();
    }

    /** The angular velocity, a fresh array, or {@code null}. */
    public double[] angularVelocity() {
        return angularVelocity == null ? null : angularVelocity.clone();
    }

    /** The attitude to hold, {@code w, x, y, z}, a fresh array, or {@code null}. */
    public double[] attitude() {
        return attitude == null ? null : attitude.clone();
    }

    /** Whether no part is present: the craft is asked for nothing. */
    public boolean asksNothing() {
        return velocity == null && angularVelocity == null && attitude == null;
    }

    private static double[] copy(double[] part, int components, String name) {
        if (part == null) {
            return null;
        }
        if (part.length != components) {
            throw new IllegalArgumentException(name + " has " + part.length + " components, not " + components);
        }
        return part.clone();
    }

    @Override
    public String toString() {
        return "FlightCommand{velocity=" + Arrays.toString(velocity) + ", angularVelocity="
                + Arrays.toString(angularVelocity) + ", attitude=" + Arrays.toString(attitude) + "}";
    }
}
