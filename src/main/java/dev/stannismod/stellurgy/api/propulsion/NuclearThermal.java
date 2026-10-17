package dev.stannismod.stellurgy.api.propulsion;

/**
 * The exhaust velocity a nuclear-thermal core gives a working fluid: {@code v_e ∝ sqrt(T/M)}, the
 * ideal-gas jet of a fluid heated to {@code T} (C10 STAT-32).
 */
public final class NuclearThermal {

    /**
     * The anchor: hydrogen from a NERVA-class graphite core at about 2 700 K leaves at about 8.5 km/s
     * (I_sp ≈ 870 s). Calibration of the proportionality, not a knob.
     */
    public static final double ANCHOR_EXHAUST_VELOCITY = 8_500.0;
    /** K. */
    public static final double ANCHOR_TEMPERATURE = 2_700.0;
    /** g/mol, H₂. */
    public static final double ANCHOR_MOLAR_MASS = 2.016;

    private NuclearThermal() {
    }

    /** m/s, for a core at {@code temperatureKelvin} heating a fluid of {@code molarMassGramsPerMole}. */
    public static double exhaustVelocity(double temperatureKelvin, double molarMassGramsPerMole) {
        if (!(temperatureKelvin > 0) || !(molarMassGramsPerMole > 0)) {
            throw new IllegalArgumentException("T=" + temperatureKelvin + " K, M=" + molarMassGramsPerMole + " g/mol");
        }
        return ANCHOR_EXHAUST_VELOCITY * Math.sqrt((temperatureKelvin / ANCHOR_TEMPERATURE)
                * (ANCHOR_MOLAR_MASS / molarMassGramsPerMole));
    }
}
