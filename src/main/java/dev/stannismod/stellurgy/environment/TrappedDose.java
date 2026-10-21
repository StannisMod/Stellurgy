package dev.stannismod.stellurgy.environment;

/**
 * A trapped-particle dose rate split by belt, Gy/s in silicon behind the reference shield: the inner belt's
 * protons and the outer belt's electrons. Kept apart because matter treats them differently — a few
 * millimetres of aluminium stop the belts' electrons almost entirely and barely thin their protons — and
 * because only the protons upset memory. Immutable.
 */
public final class TrappedDose {

    /**
     * Nothing trapped: a reading of zero, not an absence. Effectively final, process lifetime: an immutable
     * value, written only by the class initialiser.
     */
    public static final TrappedDose ZERO = new TrappedDose(0d, 0d);

    private final double protonGyPerS;
    private final double electronGyPerS;

    private TrappedDose(double protonGyPerS, double electronGyPerS) {
        this.protonGyPerS = protonGyPerS;
        this.electronGyPerS = electronGyPerS;
    }

    static TrappedDose of(double protonGyPerS, double electronGyPerS) {
        return protonGyPerS == 0d && electronGyPerS == 0d ? ZERO : new TrappedDose(protonGyPerS, electronGyPerS);
    }

    /** The inner belt's trapped protons, Gy/s. */
    public double protonGyPerS() {
        return protonGyPerS;
    }

    /** The outer belt's trapped electrons, Gy/s. */
    public double electronGyPerS() {
        return electronGyPerS;
    }

    /** Both belts together, Gy/s. */
    public double totalGyPerS() {
        return protonGyPerS + electronGyPerS;
    }
}
