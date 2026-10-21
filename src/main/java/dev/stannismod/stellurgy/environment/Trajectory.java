package dev.stannismod.stellurgy.environment;

import org.joml.Vector3dc;

/**
 * A path about a body over time, in the body-centred frame whose axes do not turn with the body: what
 * {@link PlanetEnvironment#accruedDose} integrates along.
 */
public interface Trajectory {

    /** Position at {@code tick}, metres from the body's centre. */
    Vector3dc positionAt(long tick);

    /** The time the path takes to repeat about the body, in ticks; the integration resolves each period. */
    long periodTicks();
}
