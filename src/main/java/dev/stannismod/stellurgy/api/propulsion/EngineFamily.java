package dev.stannismod.stellurgy.api.propulsion;

/**
 * Where an engine's energy comes from, which decides whether its thrust has a power term at all.
 * A family is a SHAPE of the one parameter set ({@link EngineDesign}), not a type of its own.
 */
public enum EngineFamily {
    /** The energy is in the propellant: the power term is infinite. */
    CHEMICAL(false),
    /** A reactor heats a working fluid: power from the core stack behind the nozzle. */
    NUCLEAR_THERMAL(true),
    /** Electricity accelerates the propellant, at an exhaust velocity fixed by the build. */
    ELECTRIC(true),
    /** A plasma drive fed by a reactor, between the two ends of its exhaust-velocity range. */
    DIVERTOR(true);

    /** Effectively final, process lifetime: set once when the object is built. */
    private final boolean powerLimited;

    EngineFamily(boolean powerLimited) {
        this.powerLimited = powerLimited;
    }

    /** Whether the energy comes from outside the propellant, so the thrust law's power term applies. */
    public boolean isPowerLimited() {
        return powerLimited;
    }
}
