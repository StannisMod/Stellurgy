package zmaster587.advancedRocketry.atmosphere.gas;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import zmaster587.advancedRocketry.atmosphere.AirState;

/**
 * One substance an atmosphere can be made of: what it is called, what it does, and what it is worth
 * extracting.
 * <p>
 * <b>The fluid is not decoration.</b> A gas present in an atmosphere is a gas that can be taken out of
 * it, so the thing you would put in a tank IS the key the composition is stored under. That is why a
 * gas carries a fluid name rather than a display string: the model and the resource map are one
 * object, and a gas that could be measured but not collected would be two.
 * <p>
 * The fluid is resolved LAZILY. Gases are declared at class-load, long before Forge's fluid registry
 * is populated, so holding a {@link Fluid} reference here would capture null forever and silently make
 * every gas unharvestable.
 * <p>
 * <b>Thresholds live here, not in the predicates.</b> A poison is dangerous at its own concentration
 * and a solvent matters at its own; putting those numbers on the substance is what lets a predicate
 * ask "is anything here past its limit" without a table of special cases.
 */
public final class Gas {

    private final String name;
    private final String fluidName;
    private final Set<GasRole> roles;
    private final long hazardThreshold;
    private final double molarMass;

    /**
     * @param hazardThresholdPpm the limit in parts per million of an atmosphere, which is the unit
     *                           real exposure limits are quoted in, converted here to the
     *                           composition's own finer unit. Authoring in ppm is what keeps the rows
     *                           in the registry readable as the numbers they were taken from.
     */
    Gas(String name, String fluidName, double molarMass, int hazardThresholdPpm, GasRole... roles) {
        this.name = name;
        this.fluidName = fluidName;
        this.molarMass = molarMass;
        this.hazardThreshold = Math.max(0, hazardThresholdPpm) * AirState.PER_PPM;
        this.roles = roles.length == 0
                ? Collections.<GasRole>emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(java.util.Arrays.asList(roles)));
    }

    /** The registry name. Stable: it is written into save data and cited by config. */
    public String name() {
        return name;
    }

    /** What this gas is for. Never empty — a gas with no role has no reason to be in the model. */
    public Set<GasRole> roles() {
        return roles;
    }

    public boolean is(GasRole role) {
        return roles.contains(role);
    }

    /**
     * The partial pressure past which this gas is a problem, in the composition's own units. Zero for
     * a gas that is harmless at any concentration a player will meet.
     * <p>
     * For a poison this is the concentration that hurts; for a corrosive it is where attack begins.
     * It is a property of the SUBSTANCE, so a predicate never needs to know which gas it is looking at.
     */
    public long hazardThreshold() {
        return hazardThreshold;
    }

    /** Grams per mole. Not read yet; it is what a thermal-escape derivation will ask for. */
    public double molarMass() {
        return molarMass;
    }

    /**
     * The fluid this gas becomes when it is extracted, or null when nothing has registered one.
     * <p>
     * A null is not an error: it means this substance can be measured and not yet bottled, which is a
     * true statement about a pack that has not added the fluid. Resolved on every call rather than
     * cached, because a fluid can be registered after this class loads.
     */
    public Fluid fluid() {
        return fluidName == null ? null : FluidRegistry.getFluid(fluidName);
    }

    @Override
    public String toString() {
        return "Gas[" + name + " " + roles + "]";
    }
}
