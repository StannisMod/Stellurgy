package dev.stannismod.stellurgy.ship.control;

/**
 * What a reaction actuator takes at full throttle in one regime it can run (C10 STAT-37): its
 * propellant's mass flow and, for a power-limited drive, the power it draws. Both are linear in
 * throttle — the throat is choked, so the outside pressure moves the force, never the flow.
 */
public final class PropellantDemand {

    private final String propellant;
    private final double massFlow;
    private final double power;

    /**
     * @param propellant what feeds this regime, by fluid name ({@code fuel+oxidizer} for a pair)
     * @param massFlow   kg/s at full throttle
     * @param power      W at full throttle; 0 when the energy is in the propellant
     */
    public PropellantDemand(String propellant, double massFlow, double power) {
        if (!(massFlow >= 0) || !(power >= 0)) {
            throw new IllegalArgumentException("a demand is never negative: " + massFlow + " kg/s, " + power + " W");
        }
        this.propellant = propellant;
        this.massFlow = massFlow;
        this.power = power;
    }

    public String propellant() {
        return propellant;
    }

    /** kg/s at {@code throttle} in [0, 1]. */
    public double massFlow(double throttle) {
        return massFlow * throttle;
    }

    /** W at {@code throttle} in [0, 1]. */
    public double power(double throttle) {
        return power * throttle;
    }

    @Override
    public String toString() {
        return propellant + "{" + massFlow + " kg/s, " + power + " W}";
    }
}
