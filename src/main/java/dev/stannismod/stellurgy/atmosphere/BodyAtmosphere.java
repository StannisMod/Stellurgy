package dev.stannismod.stellurgy.atmosphere;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.stannismod.stellurgy.atmosphere.gas.Gas;
import dev.stannismod.stellurgy.atmosphere.gas.GasRegistry;

/**
 * What a body's air is made of when nobody wrote it down: one rule, from the body's own mass, size and
 * temperature, for every world that is not authored.
 * <p>
 * The TOTAL pressure is not decided here — the body's derivation already states it. What this decides
 * is how that total is SHARED between gases, in four steps:
 * <ol>
 *   <li><b>A starting inventory by body class.</b> A rocky body holds what it outgassed; a giant holds
 *       what it captured from the nebula it formed in.</li>
 *   <li><b>Thermal escape, per gas.</b> A gas stays while the body's gravity beats its molecules'
 *       thermal speed, and that speed falls with molar mass — so the same body can keep carbon dioxide
 *       and lose helium.</li>
 *   <li><b>The cold trap, rocky bodies only.</b> Below its boiling point a gas lies on the ground as
 *       liquid or ice instead of in the air. A giant has no surface to freeze onto and a hot interior
 *       below its cloud decks, so it keeps what it holds.</li>
 *   <li><b>Life.</b> A world whose derivation gave it oxygen breathes Earth's mix: free oxygen at that
 *       level is what a biosphere makes of a carbon dioxide atmosphere, and Earth is the one measured
 *       case.</li>
 * </ol>
 * If every gas is lost or frozen out the answer is a vacuum, whatever total was asked for: the air is
 * the sum of what is in it, and nothing is in it.
 */
public final class BodyAtmosphere {

    /**
     * Earth's mean surface temperature, the normaliser that makes Earth's retention exactly 1 — so the
     * threshold below reads in "Earth-equivalents times grams per mole".
     */
    private static final double EARTH_SURFACE_KELVIN = 288.0D;

    /**
     * Retention times molar mass below which a gas escapes. A calibration of this model onto the solar
     * system, not a knob: it is bracketed by four measured bodies, computed with the same formula as
     * {@link #retention}. Titan keeps its nitrogen (4.8) while Ganymede keeps none (4.4), Mercury keeps
     * no carbon dioxide (4.1) and Earth keeps no helium (4.0). The midpoint of the one gap all four
     * leave open.
     */
    private static final double ESCAPE_THRESHOLD = 4.6D;

    /**
     * Above this a giant's carbon sits as carbon monoxide rather than methane: the two are equally
     * abundant near 1100 K at one bar, which is why Jupiter shows methane and the hot Jupiters do not.
     */
    private static final double METHANE_CEILING_KELVIN = 1100.0D;

    /** A rocky body's outgassed air, as Venus — the rocky world least rearranged since — holds it. */
    private static final Map<Gas, Double> OUTGASSED = inventory(
            new Gas[]{GasRegistry.CARBON_DIOXIDE, GasRegistry.NITROGEN, GasRegistry.WATER},
            new double[]{0.965D, 0.035D, 0.00002D});
    /** A giant's captured air, as Jupiter holds it. */
    private static final Map<Gas, Double> NEBULAR = inventory(
            new Gas[]{GasRegistry.HYDROGEN, GasRegistry.HELIUM, GasRegistry.METHANE},
            new double[]{0.898D, 0.102D, 0.003D});

    private BodyAtmosphere() {
    }

    /** A measured mix as an unmodifiable table: the reading is a constant, so nothing may edit it. */
    private static Map<Gas, Double> inventory(Gas[] gases, double[] fractions) {
        Map<Gas, Double> table = new LinkedHashMap<>();
        for (int i = 0; i < gases.length; i++) {
            table.put(gases[i], fractions[i]);
        }
        return Collections.unmodifiableMap(table);
    }

    /**
     * @param massEarths      the body's mass, in Earth masses
     * @param radiusEarths    the body's radius, in Earth radii
     * @param surfaceKelvin   its mean surface temperature — for a giant, at the one-bar level
     * @param gasGiant        whether it is a giant: which inventory it starts from, and whether it
     *                        has a surface to freeze gas onto
     * @param oxygenated      whether its derivation gave it free oxygen
     * @param totalPressure   the surface pressure its derivation states, in the composition's own
     *                        unit ({@link AirState#ONE_ATM} per atmosphere)
     * @throws IllegalArgumentException for a non-positive mass or radius: a body with neither has no
     *                                  gravity to hold anything with, and no answer can be given
     */
    public static AirState derive(double massEarths, double radiusEarths, int surfaceKelvin,
                                  boolean gasGiant, boolean oxygenated, long totalPressure) {
        if (!(massEarths > 0.0D) || !(radiusEarths > 0.0D)) {
            throw new IllegalArgumentException("a body's air needs its mass and radius, got mass="
                    + massEarths + " radius=" + radiusEarths);
        }
        double kelvin = Math.max(1, surfaceKelvin);
        AirState air = AirState.vacuum();
        if (totalPressure <= 0L) {
            return air;
        }

        Map<Gas, Double> kept = new LinkedHashMap<>();
        if (oxygenated) {
            AirState earth = AirState.earthLike();
            for (Map.Entry<Gas, Long> entry : earth.composition().entrySet()) {
                kept.put(entry.getKey(), (double) entry.getValue());
            }
        } else {
            double retention = retention(massEarths, radiusEarths, kelvin);
            for (Map.Entry<Gas, Double> entry : (gasGiant ? NEBULAR : OUTGASSED).entrySet()) {
                Gas gas = entry.getKey();
                boolean escapes = retention * gas.molarMass() < ESCAPE_THRESHOLD;
                boolean frozen = !gasGiant && kelvin < gas.boilingKelvin();
                boolean burnt = gasGiant && gas == GasRegistry.METHANE && kelvin >= METHANE_CEILING_KELVIN;
                if (!escapes && !frozen && !burnt) {
                    kept.put(gas, entry.getValue());
                }
            }
        }

        double share = 0.0D;
        for (double fraction : kept.values()) {
            share += fraction;
        }
        if (share <= 0.0D) {
            return air;
        }
        for (Map.Entry<Gas, Double> entry : kept.entrySet()) {
            air.add(entry.getKey(), Math.round(totalPressure * (entry.getValue() / share)), kelvin);
        }
        return air;
    }

    /**
     * Escape velocity squared over temperature, with Earth at 1 — the shape of the Jeans parameter
     * without the molecule. Surface temperature stands in for the exosphere's, which no body here
     * states; the threshold is calibrated against the same stand-in, so the substitution is paid for
     * once.
     */
    private static double retention(double massEarths, double radiusEarths, double kelvin) {
        return (massEarths / radiusEarths) / (kelvin / EARTH_SURFACE_KELVIN);
    }
}
