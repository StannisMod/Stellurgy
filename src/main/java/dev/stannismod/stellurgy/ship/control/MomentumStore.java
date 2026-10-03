package dev.stannismod.stellurgy.ship.control;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How much angular momentum each stored-momentum device has already handed the hull.
 *
 * <p>A reaction wheel turns the hull by spinning itself the other way, so every newton-metre-second
 * it gives is one it holds, and at its capacity it can give no more in that sense. That is not an
 * engineering limit to tune away; it is conservation. This store is the one place that bookkeeping
 * lives, and the allocation reads it to know how far each device may still push.</p>
 *
 * <p>Written by the physics step and read by whoever saves it, so the map is concurrent. It holds
 * durable state — a wheel that was full when the world was saved is full when it loads — and its
 * owner is the thing that persists it; this class only keeps the numbers.</p>
 */
public final class MomentumStore {

    private final Map<ActuatorId, Double> given = new ConcurrentHashMap<>();

    /** The momentum {@code id} has given the hull along its own axis, N·m·s, signed. */
    public double given(ActuatorId id) {
        Double v = given.get(id);
        return v == null ? 0.0D : v;
    }

    /** Restore a device's state, as loaded from wherever its owner keeps it. */
    public void restore(ActuatorId id, double momentum) {
        if (momentum == 0.0D) {
            given.remove(id);
        } else {
            given.put(id, momentum);
        }
    }

    /**
     * The throttle range each actuator may still use for {@code dt} seconds without passing its
     * capacity; a sustained device keeps its full range.
     *
     * @return {@code [min, max]} per actuator, aligned with the capability's actuators
     */
    double[][] liveRange(List<Actuator> actuators, double dt) {
        double[][] range = new double[actuators.size()][2];
        for (int i = 0; i < actuators.size(); i++) {
            Actuator a = actuators.get(i);
            range[i][0] = a.minThrottle();
            range[i][1] = 1.0D;
            if (a.isSustained()) {
                continue;
            }
            double rate = a.maxTorque().length() * dt; // momentum per unit throttle this step
            if (!(rate > 0.0D)) {
                continue;
            }
            double now = given(a.id());
            double cap = a.momentumCapacity();
            range[i][1] = Math.max(0.0D, Math.min(1.0D, (cap - now) / rate));
            range[i][0] = Math.min(0.0D, Math.max(a.minThrottle(), (-cap - now) / rate));
        }
        return range;
    }

    /** Book what the stored-momentum devices gave during a command held for {@code dt}. */
    void absorb(List<Actuator> actuators, ActuatorCommand command, double dt) {
        for (int i = 0; i < actuators.size(); i++) {
            Actuator a = actuators.get(i);
            double u = command.throttle(i);
            if (a.isSustained() || u == 0.0D) {
                continue;
            }
            double next = given(a.id()) + u * a.maxTorque().length() * dt;
            double cap = a.momentumCapacity();
            restore(a.id(), Math.max(-cap, Math.min(cap, next)));
        }
    }
}
