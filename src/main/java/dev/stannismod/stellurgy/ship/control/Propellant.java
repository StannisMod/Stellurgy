package dev.stannismod.stellurgy.ship.control;

import dev.stannismod.stellurgy.api.propulsion.PropellantDrain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What feeds one regime of a reaction drive, by fluid: a single fluid, or a fuel and an oxidizer burnt
 * at a mixture ratio by mass. The pool a drive draws from is kept per FLUID (C10 STAT-22), so a pair is
 * named here by its two fluids and the share of every kilogram ejected that each of them supplies.
 */
public final class Propellant {

    private final String name;
    private final Map<String, Double> shares;

    private Propellant(String name, Map<String, Double> shares) {
        this.name = name;
        this.shares = Collections.unmodifiableMap(shares);
    }

    /** One fluid, burnt alone: every kilogram ejected is that fluid's. */
    public static Propellant of(String fluid) {
        Map<String, Double> shares = new LinkedHashMap<>();
        shares.put(fluid, 1.0D);
        return new Propellant(fluid, shares);
    }

    /**
     * A fuel and an oxidizer burnt at {@code mixtureRatio} oxidizer over fuel, by mass (C10 STAT-32's
     * pair row); named {@code fuel+oxidizer}.
     */
    public static Propellant pair(String fuel, String oxidizer, double mixtureRatio) {
        double[] split = PropellantDrain.pairShares(mixtureRatio);
        Map<String, Double> shares = new LinkedHashMap<>();
        shares.put(fuel, split[0]);
        shares.put(oxidizer, split[1]);
        return new Propellant(fuel + "+" + oxidizer, shares);
    }

    public String name() {
        return name;
    }

    /** Fluid name → the fraction of every kilogram ejected that it supplies; the fractions sum to one. */
    public Map<String, Double> shares() {
        return shares;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Propellant && ((Propellant) o).name.equals(name) && ((Propellant) o).shares.equals(shares);
    }

    @Override
    public int hashCode() {
        return name.hashCode() * 31 + shares.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
