package dev.stannismod.stellurgy.environment;

/**
 * The trapped-particle belts' dose rate at a point, from the field line it lies on and how near the
 * point sits to that line's loss cone.
 *
 * <p>Two belts, each a Gaussian in the McIlwain shell parameter {@code L} (the field line's equatorial
 * distance in body radii): an inner proton belt and an outer electron belt with the slot between them.
 * Along a field line the dose falls toward the point where the line meets the absorbing atmosphere,
 * because particles mirroring below it are lost; the fall is steep, as real pitch-angle distributions
 * are depleted near the loss cone. Every figure is a dose rate in silicon behind the reference shield —
 * under 0.4 g/cm².</p>
 *
 * <p>The figures are Earth's, and three are CALIBRATED — they map the model onto measured units and are
 * not knobs. With them, about Earth's eccentric dipole, a 400 km, 51.6° orbit takes 436 µGy/day from the
 * inner belt against R3DE's 426 (Dachev et al. 2012, the anchor), most of it in the quarter of the orbit
 * over the weak-field side; and 3.2 µGy/day from the outer belt against the 8.6 R3DE measured from
 * outer-belt electrons — a check the fit was not made to pass, within a factor of three (measured
 * 2026-10-20).</p>
 *
 * <p>A body with a different field keeps the same profile in {@code L}; only where its magnetosphere
 * ends and its own geometry change. There is no derivation of how a belt's intensity scales with the
 * field, and this class does not pretend to one.</p>
 */
final class RadiationBelts {

    /** Gy/s per Gy/day. */
    private static final double PER_DAY = 1d / 86_400d;

    /** Inner (proton) belt: peak shell — Earth's inner belt peaks near L = 1.5. */
    private static final double INNER_PEAK_L = 1.5d;
    /** Inner belt half-width in L — the belt spans roughly L = 1.15 to 2. */
    private static final double INNER_WIDTH_L = 0.35d;
    /**
     * Inner belt peak dose rate, 10 Gy/day ≈ 365 krad(Si)/yr: inside the published 100 krad – 1 Mrad per
     * year for orbits at 1000–4000 km within the belts. CALIBRATED.
     */
    private static final double INNER_PEAK_GY_PER_S = 10d * PER_DAY;

    /** Outer (electron) belt: peak shell — Earth's outer belt peaks near L = 4.5. */
    private static final double OUTER_PEAK_L = 4.5d;
    /** Outer belt half-width in L — it spreads from about L = 3 to beyond geostationary orbit. */
    private static final double OUTER_WIDTH_L = 1.5d;
    /**
     * Outer belt peak dose rate, chosen so geostationary orbit (L = 6.6) takes 0.2 Gy/day ≈ 7 krad(Si)/yr,
     * inside the published 5–10 krad/yr for GEO behind thin aluminium. CALIBRATED.
     */
    private static final double OUTER_PEAK_GY_PER_S = 1.62d * PER_DAY;

    /**
     * How steeply the dose falls toward the loss cone: the dose scales as {@code (1 − B/B_cut)^n}, where
     * {@code B_cut} is the field at the shell's lowest mirror point anywhere on its drift. An isotropic
     * population would give n = 0.5; real belts are depleted near the loss cone. CALIBRATED: fitted so
     * the 400 km, 51.6° orbit reproduces R3DE's 426 µGy/day with the inner peak above.
     */
    private static final double LOSS_CONE_EXPONENT = 3.7d;

    private RadiationBelts() {
    }

    /**
     * The dose rate on field line {@code shell} at a point where the field is the fraction
     * {@code fieldOverCut} of the field at that line's absorbing foot; zero at or past the foot.
     */
    static double doseRate(double shell, double fieldOverCut) {
        if (fieldOverCut >= 1d) {
            return 0d;
        }
        double inner = (shell - INNER_PEAK_L) / INNER_WIDTH_L;
        double outer = (shell - OUTER_PEAK_L) / OUTER_WIDTH_L;
        double atShell = INNER_PEAK_GY_PER_S * Math.exp(-inner * inner)
                + OUTER_PEAK_GY_PER_S * Math.exp(-outer * outer);
        return atShell * Math.pow(1d - fieldOverCut, LOSS_CONE_EXPONENT);
    }
}
