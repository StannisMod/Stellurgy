package dev.stannismod.stellurgy.ship.control;

import org.joml.Vector3dc;

/**
 * Turns what the flight law wants into actuator throttles.
 *
 * <p>The seam where control schemes differ — the default composes cached clean recipes, a coupled
 * allocator would solve over the actuators every tick — and nothing else differs: every scheme reads
 * the same {@link ShipCapability}, so none of them owns a mass model, a hull scan or an actuator
 * list of its own. A scheme that needed one would be the sign the boundary is in the wrong place.</p>
 */
public interface ControlScheme {

    /** The default: each axis by its cached clean recipe, composed, then scaled down together. */
    static ControlScheme cleanAxes() {
        return new CleanAxisScheme();
    }

    /**
     * @param acceleration        the linear acceleration wanted, m/s², in the ship frame
     * @param angularAcceleration the angular acceleration wanted, rad/s², in the ship frame
     * @param momentum            the stored-momentum devices' state, which bounds how far each may
     *                            still turn the hull; updated by the command this returns
     * @param dt                  the seconds this command will be held
     */
    ActuatorCommand allocate(ShipCapability capability, Vector3dc acceleration,
                             Vector3dc angularAcceleration, MomentumStore momentum, double dt);
}
