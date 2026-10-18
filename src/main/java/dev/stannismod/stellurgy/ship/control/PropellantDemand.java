package dev.stannismod.stellurgy.ship.control;

import java.util.Map;

/**
 * One regime a reaction actuator can run (C10 STAT-37): what feeds it, its push, and what it takes at
 * full throttle — its propellant's mass flow and, for a power-limited drive, the power it draws. The
 * flow and the power are linear in throttle, and the throat is choked, so the outside pressure moves
 * the force and never the flow: the force at a pressure is the vacuum thrust less the pressure on the
 * bell's exit, never below zero (STAT-31).
 */
public final class PropellantDemand {

    private final Propellant propellant;
    private final double vacuumForce;
    private final double exitArea;
    private final double massFlow;
    private final double power;

    /**
     * @param propellant  what feeds this regime
     * @param vacuumForce N at full throttle in vacuum, on the power the drive is fed
     * @param exitArea    m², the bell's exit the outside pressure pushes on
     * @param massFlow    kg/s at full throttle
     * @param power       W at full throttle; 0 when the energy is in the propellant
     */
    public PropellantDemand(Propellant propellant, double vacuumForce, double exitArea, double massFlow,
                            double power) {
        if (!(massFlow >= 0) || !(power >= 0) || !(vacuumForce >= 0) || !(exitArea >= 0)) {
            throw new IllegalArgumentException("a demand is never negative: " + vacuumForce + " N, " + exitArea
                    + " m2, " + massFlow + " kg/s, " + power + " W");
        }
        this.propellant = propellant;
        this.vacuumForce = vacuumForce;
        this.exitArea = exitArea;
        this.massFlow = massFlow;
        this.power = power;
    }

    /** The propellant's name: the fluid's, or {@code fuel+oxidizer} for a pair. */
    public String propellant() {
        return propellant.name();
    }

    /** Fluid name → the fraction of every kilogram this regime ejects that it supplies. */
    public Map<String, Double> shares() {
        return propellant.shares();
    }

    /** kg/s at {@code throttle} in [0, 1]. */
    public double massFlow(double throttle) {
        return massFlow * throttle;
    }

    /** W at {@code throttle} in [0, 1]. */
    public double power(double throttle) {
        return power * throttle;
    }

    /** N at full throttle with {@code ambientPascals} outside the bell: affine in the pressure until it reaches zero. */
    public double forceAt(double ambientPascals) {
        return Math.max(0, vacuumForce - Math.max(0, ambientPascals) * exitArea);
    }

    /** m², the area the outside pressure pushes on: the slope of {@link #forceAt} while it is positive. */
    public double exitArea() {
        return exitArea;
    }

    /** The pressure at and above which this regime pushes nothing, Pa; infinite for a bell with no exit area. */
    public double separationPascals() {
        return exitArea > 0 ? vacuumForce / exitArea : Double.POSITIVE_INFINITY;
    }

    @Override
    public String toString() {
        return propellant + "{" + massFlow + " kg/s, " + power + " W}";
    }
}
