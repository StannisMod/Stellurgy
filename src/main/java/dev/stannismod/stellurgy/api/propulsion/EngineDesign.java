package dev.stannismod.stellurgy.api.propulsion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What an engine block is BUILT as, before anything feeds it: its family, its structural thrust
 * limit, the fraction of its propellant's exhaust velocity its cycle reaches, its built-in bell and,
 * for a power-limited family, the share of the power that ends up in the jet (C10 STAT-30..32).
 *
 * <p>A chemical or nuclear-thermal design takes its exhaust velocity from the PROPELLANT
 * ({@link #regimeFor}); an electric or divertor design has its exhaust velocities fixed by the build
 * ({@link #builtRegimes}). In both, {@code ṁ_max = F_max / v_e}: an engine's rated thrust is what it
 * gives at full flow, so the propellant decides how much it eats for it.</p>
 */
public final class EngineDesign {

    /** One standard atmosphere, Pa: the pressure a sea-level-to-vacuum thrust ratio was measured at. */
    public static final double STANDARD_ATMOSPHERE_PA = 101_325.0;

    private final EngineFamily family;
    private final double maxThrust;
    private final double cycleEfficiency;
    private final double jetEfficiency;
    private final double exitArea;
    private final List<Double> builtExhaustVelocities;

    private EngineDesign(EngineFamily family, double maxThrust, double cycleEfficiency, double jetEfficiency,
                         double exitArea, List<Double> builtExhaustVelocities) {
        if (!(maxThrust >= 0) || !(cycleEfficiency > 0 && cycleEfficiency <= 1) || !(exitArea >= 0)) {
            throw new IllegalArgumentException("not a design: F_max=" + maxThrust + " cycle=" + cycleEfficiency
                    + " A_exit=" + exitArea);
        }
        this.family = family;
        this.maxThrust = maxThrust;
        this.cycleEfficiency = cycleEfficiency;
        this.jetEfficiency = jetEfficiency;
        this.exitArea = exitArea;
        this.builtExhaustVelocities = Collections.unmodifiableList(new ArrayList<>(builtExhaustVelocities));
    }

    /**
     * The exit area of a built-in bell that gives {@code seaLevelFraction} of its vacuum thrust at one
     * atmosphere: {@code A_exit = F_max·(1 − f_sl)/p₀}.
     */
    public static double bellExitArea(double maxThrust, double seaLevelFraction) {
        if (!(seaLevelFraction > 0 && seaLevelFraction <= 1)) {
            throw new IllegalArgumentException("a sea-level thrust fraction is in (0, 1]: " + seaLevelFraction);
        }
        return maxThrust * (1 - seaLevelFraction) / STANDARD_ATMOSPHERE_PA;
    }

    /** An engine whose energy is in its propellant. */
    public static EngineDesign chemical(double maxThrust, double cycleEfficiency, double seaLevelFraction) {
        return new EngineDesign(EngineFamily.CHEMICAL, maxThrust, cycleEfficiency, 1,
                bellExitArea(maxThrust, seaLevelFraction), Collections.<Double>emptyList());
    }

    /** A nozzle a reactor feeds; its exhaust velocity comes from the core temperature and the working fluid. */
    public static EngineDesign nuclearThermal(double maxThrust, double jetEfficiency, double seaLevelFraction) {
        return new EngineDesign(EngineFamily.NUCLEAR_THERMAL, maxThrust, 1, jetEfficiency,
                bellExitArea(maxThrust, seaLevelFraction), Collections.<Double>emptyList());
    }

    /** An electric thruster: one exhaust velocity, set by its accelerating voltage. */
    public static EngineDesign electric(double maxThrust, double jetEfficiency, double exhaustVelocity,
                                        double exitArea) {
        return new EngineDesign(EngineFamily.ELECTRIC, maxThrust, 1, jetEfficiency, exitArea,
                Collections.singletonList(exhaustVelocity));
    }

    /** A divertor: two regimes, at the two ends of its exhaust-velocity range (cruise high, boost low). */
    public static EngineDesign divertor(double maxThrust, double jetEfficiency, double cruiseExhaustVelocity,
                                        double boostExhaustVelocity, double exitArea) {
        List<Double> ends = new ArrayList<>();
        ends.add(cruiseExhaustVelocity);
        ends.add(boostExhaustVelocity);
        return new EngineDesign(EngineFamily.DIVERTOR, maxThrust, 1, jetEfficiency, exitArea, ends);
    }

    public EngineFamily family() {
        return family;
    }

    /** N. */
    public double maxThrust() {
        return maxThrust;
    }

    /** The fraction of the propellant's exhaust velocity this cycle reaches. */
    public double cycleEfficiency() {
        return cycleEfficiency;
    }

    /** m². */
    public double exitArea() {
        return exitArea;
    }

    /** The jet's share of the power, for a power-limited family. */
    public double jetEfficiency() {
        return jetEfficiency;
    }

    /**
     * This design fed a propellant whose exhaust-velocity ceiling is {@code propellantExhaustVelocity}.
     * Refused for a design whose exhaust velocity the build fixes.
     */
    public EngineRegime regimeFor(double propellantExhaustVelocity) {
        if (!builtExhaustVelocities.isEmpty()) {
            throw new IllegalStateException(family + " takes its exhaust velocity from its build, not its propellant");
        }
        double ve = propellantExhaustVelocity * cycleEfficiency;
        return new EngineRegime(family.isPowerLimited(), ve, maxThrust / ve, maxThrust, jetEfficiency, exitArea);
    }

    /** The regimes the build fixes; empty for a design that takes its exhaust velocity from its propellant. */
    public List<EngineRegime> builtRegimes() {
        List<EngineRegime> out = new ArrayList<>();
        for (double ve : builtExhaustVelocities) {
            out.add(new EngineRegime(family.isPowerLimited(), ve, maxThrust / ve, maxThrust, jetEfficiency, exitArea));
        }
        return out;
    }
}
