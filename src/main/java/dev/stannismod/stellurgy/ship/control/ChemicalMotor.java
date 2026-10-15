package dev.stannismod.stellurgy.ship.control;

import org.joml.Vector3d;

/**
 * A chemical rocket motor as the physics system sees it: one force at the centre of its block,
 * pushing away from its nozzle.
 */
public final class ChemicalMotor {

    private ChemicalMotor() {}

    /**
     * The motor standing at block {@code (x, y, z)} whose nozzle points along the unit block direction
     * {@code (nozzleX, nozzleY, nozzleZ)}, at {@code thrustNewtons}.
     */
    public static Actuator at(int x, int y, int z, int nozzleX, int nozzleY, int nozzleZ,
                              double thrustNewtons) {
        return Actuator.pointForce(new ActuatorId(x, y, z, 0),
                new Vector3d(x + 0.5D, y + 0.5D, z + 0.5D),
                new Vector3d(-nozzleX * thrustNewtons, -nozzleY * thrustNewtons, -nozzleZ * thrustNewtons));
    }
}
