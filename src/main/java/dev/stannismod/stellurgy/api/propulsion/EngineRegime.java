package dev.stannismod.stellurgy.api.propulsion;

/**
 * One engine fed one propellant: the parameter set the thrust law reads (C10 STAT-30, STAT-31).
 *
 * <p>Everything is SI: newtons, kilograms per second, metres per second, watts, square metres,
 * pascals. {@code v_e} is the VACUUM effective exhaust velocity, so the bell's pressure term is the
 * only thing the outside air subtracts: {@code F(p) = ṁ·v_e − p·A_exit}.</p>
 */
public final class EngineRegime {

    private final boolean powerLimited;
    private final double exhaustVelocity;
    private final double maxMassFlow;
    private final double maxThrust;
    private final double jetEfficiency;
    private final double exitArea;

    EngineRegime(boolean powerLimited, double exhaustVelocity, double maxMassFlow, double maxThrust,
                 double jetEfficiency, double exitArea) {
        if (!(exhaustVelocity > 0) || !(maxMassFlow >= 0) || !(maxThrust >= 0) || !(exitArea >= 0)
                || (powerLimited && !(jetEfficiency > 0 && jetEfficiency <= 1))) {
            throw new IllegalArgumentException("not a regime: v_e=" + exhaustVelocity + " mdot_max=" + maxMassFlow
                    + " F_max=" + maxThrust + " eta=" + jetEfficiency + " A_exit=" + exitArea);
        }
        this.powerLimited = powerLimited;
        this.exhaustVelocity = exhaustVelocity;
        this.maxMassFlow = maxMassFlow;
        this.maxThrust = maxThrust;
        this.jetEfficiency = jetEfficiency;
        this.exitArea = exitArea;
    }

    /** m/s. */
    public double exhaustVelocity() {
        return exhaustVelocity;
    }

    /** kg/s. */
    public double maxMassFlow() {
        return maxMassFlow;
    }

    /** N, the structural limit. */
    public double maxThrust() {
        return maxThrust;
    }

    /** m², the bell's exit. */
    public double exitArea() {
        return exitArea;
    }

    /** Whether the power term of the law applies. */
    public boolean isPowerLimited() {
        return powerLimited;
    }

    /**
     * The vacuum thrust the regime gives on {@code powerWatts}: {@code min(ṁ_max·v_e, 2ηP/v_e, F_max)}.
     * A chemical regime ignores the power; a power-limited one gives nothing on none.
     */
    public double thrust(double powerWatts) {
        double flowLimited = Math.min(maxMassFlow * exhaustVelocity, maxThrust);
        if (!powerLimited) {
            return flowLimited;
        }
        if (!(powerWatts > 0)) {
            return 0;
        }
        return Math.min(flowLimited, 2 * jetEfficiency * powerWatts / exhaustVelocity);
    }

    /**
     * The force at an outside pressure of {@code ambientPascals}: the vacuum thrust less the pressure
     * on the bell's exit, and never below zero — a nozzle whose flow has separated pushes nothing.
     */
    public double thrustAt(double powerWatts, double ambientPascals) {
        return Math.max(0, thrust(powerWatts) - Math.max(0, ambientPascals) * exitArea);
    }

    /** The mass flow at a vacuum thrust of {@code thrust}, kg/s: {@code F / v_e}. */
    public double massFlow(double thrust) {
        return Math.max(0, thrust) / exhaustVelocity;
    }

    /** The power a vacuum thrust of {@code thrust} draws, W: {@code F·v_e / 2η}; zero for chemistry. */
    public double powerDrawn(double thrust) {
        return powerLimited ? Math.max(0, thrust) * exhaustVelocity / (2 * jetEfficiency) : 0;
    }

    /** The same regime with its thrust and its mass flow both scaled by {@code factor} — a bigger engine, same {@code v_e}. */
    public EngineRegime scaled(double factor) {
        return new EngineRegime(powerLimited, exhaustVelocity, maxMassFlow * factor, maxThrust * factor,
                jetEfficiency, exitArea * factor);
    }

    @Override
    public String toString() {
        return "EngineRegime{v_e=" + exhaustVelocity + ", mdot_max=" + maxMassFlow + ", F_max=" + maxThrust
                + (powerLimited ? ", eta=" + jetEfficiency : "") + ", A_exit=" + exitArea + '}';
    }
}
