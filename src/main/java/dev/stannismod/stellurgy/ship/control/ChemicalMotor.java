package dev.stannismod.stellurgy.ship.control;

import dev.stannismod.stellurgy.api.propulsion.EngineRegime;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A rocket motor as the physics system sees it: one force at the centre of its block, pushing away
 * from its nozzle. The name is historical; nuclear-thermal nozzles are built here too, because the
 * law is the same and only the power term differs (C10 STAT-30).
 */
public final class ChemicalMotor {

    private ChemicalMotor() {}

    /**
     * The motor standing at block {@code (x, y, z)} whose nozzle points along the unit block direction
     * {@code (nozzleX, nozzleY, nozzleZ)}, at {@code thrustNewtons}, declaring nothing it takes.
     */
    public static Actuator at(int x, int y, int z, int nozzleX, int nozzleY, int nozzleZ,
                              double thrustNewtons) {
        return Actuator.pointForce(new ActuatorId(x, y, z, 0),
                new Vector3d(x + 0.5D, y + 0.5D, z + 0.5D),
                new Vector3d(-nozzleX * thrustNewtons, -nozzleY * thrustNewtons, -nozzleZ * thrustNewtons));
    }

    /**
     * The motor at {@code (x, y, z)} that can run each of {@code regimes} (propellant name → regime),
     * fed {@code powerWatts} (ignored by a chemical regime), in air at {@code ambientPascals}
     * (C10 STAT-30, STAT-31, STAT-37).
     *
     * <p>It declares, per regime, the mass flow and power of its full-throttle vacuum thrust. Its force
     * is the LEAST any of its regimes gives at that pressure: until something aboard decides which
     * propellant feeds it, the authority it offers is one it has whichever it is fed. A motor with no
     * regime has no propellant it can burn and pushes nothing.</p>
     */
    public static Actuator fromRegimes(int x, int y, int z, int nozzleX, int nozzleY, int nozzleZ,
                                       Map<String, EngineRegime> regimes, double powerWatts,
                                       double ambientPascals) {
        double force = regimes.isEmpty() ? 0 : Double.POSITIVE_INFINITY;
        List<PropellantDemand> demands = new ArrayList<>();
        for (Map.Entry<String, EngineRegime> e : regimes.entrySet()) {
            EngineRegime regime = e.getValue();
            double vacuum = regime.thrust(powerWatts);
            force = Math.min(force, regime.thrustAt(powerWatts, ambientPascals));
            demands.add(new PropellantDemand(e.getKey(), regime.massFlow(vacuum), regime.powerDrawn(vacuum)));
        }
        return Actuator.reactionForce(new ActuatorId(x, y, z, 0),
                new Vector3d(x + 0.5D, y + 0.5D, z + 0.5D),
                new Vector3d(-nozzleX * force, -nozzleY * force, -nozzleZ * force), demands);
    }
}
