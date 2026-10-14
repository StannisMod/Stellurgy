package dev.stannismod.stellurgy.ship.control;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.joml.Vector3d;

/**
 * A reaction wheel as the physics system sees it: three pure torques, one per axis of the ship's
 * frame, each taking the momentum it gives the hull into its own spin until it is full.
 *
 * <p>Where on the hull it sits does not matter — a pure torque has no lever arm — which is exactly
 * why it is the device that lets a hull turn whose engines cannot balance a rotation. The position is
 * only its identity.</p>
 */
public final class ReactionWheel {

    /**
     * Torque per axis at full throttle, N·m. `tunable`. The relation it was chosen by: one wheel
     * gives the reference deck ship (the pilot-deck test craft, actuated) the angular authority every
     * craft used to be granted by a constant, 4 rad/s² in the engine's units, i.e. 1.23 rad/s², about
     * its heaviest axis. Measured 2026-09-30 through the craft's flight model: yaw inertia 1.54e6
     * kg·m² (roll 1.23e6, pitch 1.21e6) at 281 t; 1.54e6 × 1.23 = 1.9e6. The first figure, 6.5e5,
     * was set from an estimate three times too low and is superseded.
     */
    public static final double TORQUE = 1_900_000.0D;

    /**
     * Momentum each axis can store, N·m·s. `tunable`. Same relation: the rate the old attitude law
     * capped every craft at, 2 rad/s in the engine's units (1.11 rad/s), on that same measured yaw
     * inertia: 1.54e6 × 1.11 = 1.7e6.
     */
    public static final double MOMENTUM_CAPACITY = 1_700_000.0D;

    private ReactionWheel() {}

    /** The wheel standing at block {@code (x, y, z)}: its three torque devices, indices 0-2 for X, Y, Z. */
    public static List<Actuator> at(int x, int y, int z) {
        List<Actuator> out = new ArrayList<>(3);
        out.add(Actuator.pureTorque(new ActuatorId(x, y, z, 0),
                new Vector3d(TORQUE, 0.0D, 0.0D), MOMENTUM_CAPACITY));
        out.add(Actuator.pureTorque(new ActuatorId(x, y, z, 1),
                new Vector3d(0.0D, TORQUE, 0.0D), MOMENTUM_CAPACITY));
        out.add(Actuator.pureTorque(new ActuatorId(x, y, z, 2),
                new Vector3d(0.0D, 0.0D, TORQUE), MOMENTUM_CAPACITY));
        return Collections.unmodifiableList(out);
    }
}
