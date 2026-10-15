package dev.stannismod.stellurgy.ship.control;

/**
 * The canonical way a hull delivers one signed direction at full authority: a throttle for every
 * actuator, and what that achieves. Cached so a tick composes recipes instead of solving anything,
 * and kept so the choice can be explained ("+surge used #12 at 100 %, #15 at 74 %").
 */
final class Recipe {

    /** Throttle per actuator, aligned with {@link ShipCapability#actuators()}. */
    final double[] throttles;
    /** The authority: newtons for a translation, rad/s² for a rotation. */
    final double authority;
    /** Seconds the recipe can be held before a stored-momentum device in it saturates; infinite if none. */
    final double seconds;

    Recipe(double[] throttles, double authority, double seconds) {
        this.throttles = throttles;
        this.authority = authority;
        this.seconds = seconds;
    }

    static Recipe none(int actuators) {
        return new Recipe(new double[actuators], 0.0D, Double.POSITIVE_INFINITY);
    }
}
