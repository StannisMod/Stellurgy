package dev.stannismod.stellurgy.satellite;

/**
 * What a trapped-particle dose does to a satellite's parts: how far a shield thins it, how much of it
 * electronics survive, how it darkens a solar panel, and how often it destroys a buffered data point.
 * Pure laws over doses in Gy(Si) behind the reference shield — the quantity the environment gives.
 *
 * <p>Every figure is a reading, with what it was read from beside it.</p>
 */
public final class RadiationWear {

    /**
     * The dose at which electronics stop, Gy: 5 krad(Si). NASA's SmallSat radiation note: "most
     * commercial components are radiation tolerant to 5 krad". Commercial parts span 1–30 krad; the
     * figure is the typical one, for parts nobody has screened.
     */
    public static final double ELECTRONICS_BUDGET_GY = 50d;

    /**
     * A shield part thins the inner belt's protons by this factor and the outer belt's electrons by the
     * next. Read from Shields-1 (NASA, polar orbit at 500 km, 85°): SHIELDOSE-2 with AP8/AE8 for an
     * aluminium sphere gives 117 rad/yr of protons and 1266 of electrons behind 0.535 g/cm² — about the
     * reference shield — and 64.7 and 1.5 behind 3.00 g/cm². A shield part is that step: 2.47 g/cm² of
     * aluminium, 9.1 mm. The measured totals behind slabs (90.9, 73.6, 70.0 rad/yr at 1.26, 3.00 and
     * 6.00 g/cm²) agree with the modelled proton floor.
     *
     * <p>The electron factor is a low orbit's spectrum. The outer belt's electrons are harder, so in
     * geostationary orbit the real factor is smaller; no dose–depth reading for that orbit was in hand.</p>
     */
    public static final double SHIELD_PROTON_FACTOR = 117d / 64.7d;
    public static final double SHIELD_ELECTRON_FACTOR = 1266d / 1.5d;
    /** The shield's areal density, g/cm²: the step between the two readings above. */
    public static final double SHIELD_AREAL_DENSITY_G_CM2 = 3.00d - 0.535d;

    /**
     * A solar panel's remaining power follows {@code P/P0 = 1 − C·log10(1 + D/Dx)}, the form a
     * triple-junction cell's datasheet degradation fits. SolAero ZTJ+ after 1 MeV electrons: 0.91, 0.87
     * and 0.73 at 5e14, 1e15 and 5e15 e/cm²; C = 0.2 with Dx at 2.88e14 e/cm² reproduces them to 0.02.
     * The fluence is carried onto our dose by the industry's design point — a geostationary array is
     * qualified to 1e15 e/cm² for 15 years — against the 15 years of our geostationary dose (0.2 Gy/day):
     * 1e15 e/cm² is 1096 Gy, so Dx is 316 Gy. A CALIBRATION: the model holds no dose at an array's open
     * face, so the panel is read against the dose behind the reference shield.
     */
    static final double PANEL_DECADE_LOSS = 0.2d;
    static final double PANEL_DOSE_SCALE_GY = 1096d / (1e15d / 2.88e14d);

    /**
     * Memory upsets a 1 MB data point takes per Gy of protons. A commercial 65 nm SRAM's proton
     * cross-section is 1.5e-14 cm² per bit (EPJ Plus, 2025); one Gy of the inner belt's ~100 MeV protons
     * in silicon is 1.08e9 protons/cm² (stopping power 5.8 MeV·cm²/g). A data point is 1 MB — one
     * 1-megapixel frame: a CHOICE, there is no physical size of a data point. One upset destroys it.
     */
    static final double UPSETS_PER_POINT_PER_GY = 1.5e-14d * (6.2415e9d / 5.8d) * (8d * 1024d * 1024d);

    private RadiationWear() {
    }

    /** The proton dose that reaches what is inside the chassis, Gy, with or without a shield part. */
    public static double insideProtons(double protonGy, boolean shielded) {
        return shielded ? protonGy / SHIELD_PROTON_FACTOR : protonGy;
    }

    /** The electron dose that reaches what is inside the chassis, Gy, with or without a shield part. */
    public static double insideElectrons(double electronGy, boolean shielded) {
        return shielded ? electronGy / SHIELD_ELECTRON_FACTOR : electronGy;
    }

    /** Whether electronics that have taken {@code doseGy} still work. */
    public static boolean electronicsWork(double doseGy) {
        return doseGy < ELECTRONICS_BUDGET_GY;
    }

    /** The fraction of its new power a solar panel still gives after {@code doseGy}; zero once the curve reaches zero. */
    public static double panelPowerFraction(double doseGy) {
        return Math.max(0d, 1d - PANEL_DECADE_LOSS * Math.log10(1d + doseGy / PANEL_DOSE_SCALE_GY));
    }

    /** The chance that one buffered data point is destroyed by {@code protonGy} of protons reaching the memory. */
    public static double pointLossProbability(double protonGy) {
        return protonGy > 0d ? -Math.expm1(-UPSETS_PER_POINT_PER_GY * protonGy) : 0d;
    }
}
