package dev.stannismod.stellurgy.ship.control;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What one physics step may eject, per fluid: STAT-18's {@code budget_q} for the propellant pool
 * (C10 STAT-22, STAT-39). A METERED budget is the pool aboard net of what earlier steps already ejected;
 * an UNMETERED one is consumption switched off — nothing is limited and nothing is owed.
 *
 * <p>Immutable: one step reads one budget.</p>
 */
public final class PropellantBudget {

    /** kg per fluid; {@code null} when unmetered. */
    private final Map<String, Double> availableKg;

    private PropellantBudget(Map<String, Double> availableKg) {
        this.availableKg = availableKg;
    }

    /** Consumption off: every fluid unlimited, nothing booked (the {@code rocketRequireFuel} flag off). */
    public static PropellantBudget unmetered() {
        return new PropellantBudget(null);
    }

    /** {@code availableKg} per fluid name; a fluid not named has none. */
    public static PropellantBudget of(Map<String, Double> availableKg) {
        return new PropellantBudget(Collections.unmodifiableMap(new HashMap<>(availableKg)));
    }

    public boolean isMetered() {
        return availableKg != null;
    }

    /** kg of {@code fluid} this step may eject; infinite when unmetered, never negative. */
    public double available(String fluid) {
        if (availableKg == null) {
            return Double.POSITIVE_INFINITY;
        }
        Double kg = availableKg.get(fluid);
        return kg == null ? 0.0D : Math.max(0.0D, kg);
    }

    /**
     * The mass {@code throttles} eject in {@code seconds}, kg per fluid: each drive's one fed regime's
     * flow at its throttle, split by that regime's mass shares. A drive that is not fed (more than one
     * regime declared) is counted at its HIGHEST-flow regime, so it can never eject for free; a LIVE
     * drive of a metered craft always has one regime.
     */
    static Map<String, Double> ejected(List<Actuator> actuators, double[] throttles, double seconds) {
        Map<String, Double> kg = new TreeMap<>();
        for (int i = 0; i < actuators.size(); i++) {
            double u = throttles[i];
            if (!(u > 0.0D)) {
                continue;
            }
            PropellantDemand burnt = actuators.get(i).feed();
            if (burnt == null) {
                for (PropellantDemand d : actuators.get(i).demands()) {
                    if (burnt == null || d.massFlow(1) > burnt.massFlow(1)) {
                        burnt = d;
                    }
                }
            }
            if (burnt == null) {
                continue;
            }
            double mass = burnt.massFlow(u) * seconds;
            for (Map.Entry<String, Double> share : burnt.shares().entrySet()) {
                kg.merge(share.getKey(), mass * share.getValue(), Double::sum);
            }
        }
        return kg;
    }

    /**
     * The one factor that brings what {@code throttles} would eject inside this budget:
     * {@code min(1, min_q available_q / requested_q)} (STAT-18, STAT-39); 1 when unmetered.
     */
    double scale(List<Actuator> actuators, double[] throttles, double seconds) {
        if (availableKg == null) {
            return 1.0D;
        }
        double lambda = 1.0D;
        for (Map.Entry<String, Double> requested : ejected(actuators, throttles, seconds).entrySet()) {
            if (requested.getValue() > 0.0D) {
                lambda = Math.min(lambda, available(requested.getKey()) / requested.getValue());
            }
        }
        return lambda;
    }

    @Override
    public String toString() {
        return availableKg == null ? "PropellantBudget{unmetered}" : "PropellantBudget" + new TreeMap<>(availableKg);
    }
}
