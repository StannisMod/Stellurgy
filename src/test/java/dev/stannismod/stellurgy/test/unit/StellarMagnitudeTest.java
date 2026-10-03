package dev.stannismod.stellurgy.test.unit;

import org.junit.Test;

import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.universe.StellarMagnitude;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The photometry a telescope is bounded by: how bright a star is, and how bright it LOOKS from
 * somewhere else.
 *
 * <p><b>Why this is a unit test.</b> Every decision here is arithmetic over the star's own bulk and a
 * distance — {@link StellarMagnitude} reads no configuration, no galaxy and no registry, so nothing
 * in it needs a running game, and a red here names one class. What a telescope with the SHIPPED
 * aperture then does with these numbers — registers, resolves, misses — reads the running
 * configuration and the galaxy, and is pinned on a real server ({@code TelescopeRegionScanServerTest}).</p>
 *
 * <p>The expected numbers are the physics the class states, computed here from the law and never
 * read back from the class: the Stefan-Boltzmann ratio {@code L = R^2 (T/T_sun)^4}, the definition of
 * absolute magnitude (the brightness at ten parsecs), and the five-magnitudes-per-factor-of-ten of
 * the distance modulus. The production constants a value is hung from — the Sun's temperature in
 * the mod's units, its absolute magnitude, light years per parsec — are referred to by name.</p>
 */
public class StellarMagnitudeTest {

    /**
     * Absolute tolerance (JUnit's delta) for an identity of the law. Every value compared lies in
     * [1/16, 16] and is a handful of double operations away from exact, so its rounding error is near
     * 1e-15; 1e-9 sits six orders above that and seven below the smallest difference a wrong law
     * would make here (a factor of two at 1/16, i.e. 0.0625).
     */
    private static final double EXACT = 1e-9d;

    /** A star of a stated bulk, built the way a pack or the probe builds one. */
    private static StellarBody star(float sizeSuns, int temperatureUnits) {
        StellarBody s = new StellarBody();
        s.setSize(sizeSuns);
        s.setTemperature(temperatureUnits);
        return s;
    }

    /**
     * A star's luminosity is the square of its size times the fourth power of its temperature.
     *
     * <p>Fails if {@code StellarMagnitude#luminositySuns} stops deciding brightness by
     * {@code R^2 (T/T_sun)^4}.</p>
     *
     * <p>red-witnessed: with {@code StellarMagnitude#luminositySuns} at
     * {@code return r * r * t * t * t * t;} doubled, this fails with "the Sun is one Sun expected:<1.0>
     * but was:<2.0>"; with it reading {@code r * t * t * t * t}, "twice the size is four times the
     * light expected:<4.0> but was:<2.0>"; with it reading {@code r * r * t * t * t}, "twice the
     * temperature is sixteen times the light expected:<16.0> but was:<8.0>" — one inversion per run,
     * 2026-10-02.</p>
     */
    @Test
    public void aStarsLightIsTheSquareOfItsSizeTimesTheFourthPowerOfItsTemperature() {
        double sun = StellarMagnitude.SOLAR_TEMPERATURE_UNITS;
        assertEquals("the Sun is one Sun", 1d, StellarMagnitude.luminositySuns(1d, sun), EXACT);
        assertEquals("twice the size is four times the light", 4d,
                StellarMagnitude.luminositySuns(2d, sun), EXACT);
        assertEquals("twice the temperature is sixteen times the light", 16d,
                StellarMagnitude.luminositySuns(1d, 2d * sun), EXACT);
    }

    /**
     * Five magnitudes more aperture reach ten times farther, and an instrument whose limit IS a
     * star's absolute magnitude reaches it at exactly ten parsecs.
     *
     * <p>Fails if {@code StellarMagnitude#detectionRangeLightYears} stops deciding reach by inverting
     * the distance modulus — the rule that makes a better aperture a farther-seeing one — or
     * {@code StellarMagnitude#apparentMagnitude} stops dimming a star by five magnitudes per factor of
     * ten in distance.</p>
     *
     * <p>red-witnessed: with {@code StellarMagnitude#detectionRangeLightYears} at
     * {@code (limitMagnitude - absolute) / 5d + 1d} reading {@code / 2.5d}, this fails with "five
     * magnitudes of aperture is ten times the reach expected:<10.0> but was:<100.0>"; reading
     * {@code + 0d}, with "… sees it out to ten parsecs expected:<32.6156> but was:<3.26156>"; with
     * {@code StellarMagnitude#apparentMagnitude} at {@code 5d * Math.log10(parsecs / 10d)} reading
     * {@code 2.5d}, with "and the same star ten times farther away looks five magnitudes fainter
     * expected:<5.0> but was:<2.5>" — one inversion per run, 2026-10-02.</p>
     */
    @Test
    public void fiveMagnitudesOfApertureReachTenTimesFarther() {
        double sunLight = StellarMagnitude.luminositySuns(1d, StellarMagnitude.SOLAR_TEMPERATURE_UNITS);
        double at6 = StellarMagnitude.detectionRangeLightYears(sunLight, 6d);
        double at11 = StellarMagnitude.detectionRangeLightYears(sunLight, 11d);
        assertEquals("five magnitudes of aperture is ten times the reach", 10d, at11 / at6, EXACT);

        double tenParsecs = 10d * StellarMagnitude.LIGHT_YEARS_PER_PARSEC;
        assertEquals("an instrument whose limit is a star's absolute magnitude sees it out to ten"
                        + " parsecs", tenParsecs,
                StellarMagnitude.detectionRangeLightYears(sunLight,
                        StellarMagnitude.SOLAR_ABSOLUTE_MAGNITUDE), tenParsecs * EXACT);

        double here = StellarMagnitude.apparentMagnitude(StellarMagnitude.SOLAR_ABSOLUTE_MAGNITUDE,
                40d, 0d);
        double tenTimesFarther = StellarMagnitude.apparentMagnitude(
                StellarMagnitude.SOLAR_ABSOLUTE_MAGNITUDE, 400d, 0d);
        assertEquals("and the same star ten times farther away looks five magnitudes fainter", 5d,
                tenTimesFarther - here, EXACT);
    }

    /**
     * Dust between the instrument and a star is added to its magnitude, so a cloud costs reach in
     * exactly the currency distance does — and no cloud ever makes a star brighter.
     *
     * <p>Fails if {@code StellarMagnitude#apparentMagnitude} stops deciding that extinction adds to
     * the apparent magnitude, or lets a negative extinction brighten a star.</p>
     *
     * <p>red-witnessed: with {@code StellarMagnitude#apparentMagnitude} at
     * {@code return absolute + modulus + Math.max(0d, extinctionMagnitudes);} dropping the extinction
     * term, this fails with "two and a half magnitudes of dust dim the star by two and a half
     * magnitudes expected:<2.5> but was:<0.0>"; with its {@code 5d * Math.log10(parsecs / 10d)} reading
     * {@code 2.5d}, with "five magnitudes of dust look exactly like ten times the distance"; with the
     * {@code Math.max(0d, …)} removed, with "a negative column brightens nothing expected:<5.757…> but
     * was:<2.757…>" — one inversion per run, 2026-10-02.</p>
     */
    @Test
    public void dustDimsAStarInTheSameCurrencyAsDistance() {
        double absolute = StellarMagnitude.SOLAR_ABSOLUTE_MAGNITUDE;
        double clear = StellarMagnitude.apparentMagnitude(absolute, 50d, 0d);
        assertEquals("two and a half magnitudes of dust dim the star by two and a half magnitudes",
                2.5d, StellarMagnitude.apparentMagnitude(absolute, 50d, 2.5d) - clear, EXACT);
        assertEquals("five magnitudes of dust look exactly like ten times the distance",
                StellarMagnitude.apparentMagnitude(absolute, 500d, 0d),
                StellarMagnitude.apparentMagnitude(absolute, 50d, 5d), EXACT);
        assertEquals("a negative column brightens nothing", clear,
                StellarMagnitude.apparentMagnitude(absolute, 50d, -3d), EXACT);
    }

    /**
     * A star described by its size alone is read at the Sun's temperature; a stated temperature is
     * honoured.
     *
     * <p>Fails if {@code StellarMagnitude#luminositySuns(StellarBody)} stops deciding that an
     * unstated (zero) temperature means the Sun's — the decision that keeps a pack's size-only star
     * from being written as an invisible one.</p>
     *
     * <p>red-witnessed: with {@code StellarMagnitude#luminositySuns} at
     * {@code temperature > 0 ? temperature : SOLAR_TEMPERATURE_UNITS} reading {@code temperature}, this
     * fails with "a star of two Suns' size and no stated temperature shines like … expected:<4.0> but
     * was:<0.0>"; reading {@code SOLAR_TEMPERATURE_UNITS}, with "and a stated temperature is the one
     * used … expected:<0.0625> but was:<1.0>" — one inversion per run, 2026-10-02.</p>
     */
    @Test
    public void aStarDescribedOnlyByItsSizeShinesAtTheSunsTemperature() {
        double sun = StellarMagnitude.SOLAR_TEMPERATURE_UNITS;
        assertEquals("a star of two Suns' size and no stated temperature shines like a two-Sun star at"
                        + " the Sun's temperature", StellarMagnitude.luminositySuns(2d, sun),
                StellarMagnitude.luminositySuns(star(2f, 0)), EXACT);
        assertEquals("and a stated temperature is the one used: half the Sun's is a sixteenth of the"
                        + " light", 1d / 16d,
                StellarMagnitude.luminositySuns(star(1f, (int) (sun / 2d))), EXACT);
    }

    /**
     * A black hole emits nothing an instrument in the visible could catch, at any distance.
     *
     * <p>Fails if {@code StellarMagnitude#luminositySuns(StellarBody)} stops deciding that a black
     * hole is dark, or {@code StellarMagnitude#absoluteMagnitude} stops answering infinity for a
     * star that emits nothing.</p>
     *
     * <p>red-witnessed: with {@code StellarMagnitude#luminositySuns} at {@code if (star.isBlackHole())}
     * reading {@code if (false)}, this fails with "a black hole is dark expected:<0.0> but was:<1.0>";
     * with {@code StellarMagnitude#absoluteMagnitude} at {@code return Double.POSITIVE_INFINITY;}
     * answering {@code 99d}, with "so it looks infinitely faint even from a tenth of a light year" — one
     * inversion per run, 2026-10-02.</p>
     */
    @Test
    public void aBlackHoleIsDarkAtAnyDistance() {
        StellarBody hole = star(1f, (int) StellarMagnitude.SOLAR_TEMPERATURE_UNITS);
        assertTrue("arrangement: the same bulk without the flag is a visible star",
                Double.isFinite(StellarMagnitude.apparentMagnitudeOf(hole, 10d, 0d)));

        hole.setBlackHole(true);
        assertEquals("a black hole is dark", 0d, StellarMagnitude.luminositySuns(hole), 0d);
        assertTrue("so it looks infinitely faint even from a tenth of a light year",
                StellarMagnitude.apparentMagnitudeOf(hole, 0.1d, 0d) == Double.POSITIVE_INFINITY);
    }

    /**
     * A system whose primary is not a star — a rogue world — emits nothing, so no aperture registers
     * it: it is found by going there.
     *
     * <p>Fails if {@code StellarMagnitude#luminositySuns(StellarBody)} stops deciding that a missing
     * star is dark (the detection stage hands it the empty answer of {@code starAt} for a starless
     * system).</p>
     *
     * <p>red-witnessed: with {@code StellarMagnitude#luminositySuns} at {@code if (star == null)}
     * returning {@code 1d}, this fails with "a world with no star looks infinitely faint at any
     * distance"; with {@code StellarMagnitude#detectionRangeLightYears} at
     * {@code if (Double.isInfinite(absolute))} returning {@code Double.MAX_VALUE}, with "and no
     * aperture, however good, reaches anything that emits nothing expected:<0.0> but
     * was:<1.7976931348623157E308>" — one inversion per run, 2026-10-02.</p>
     */
    @Test
    public void aWorldWithNoStarIsDarkAtAnyDistance() {
        assertTrue("a world with no star looks infinitely faint at any distance",
                StellarMagnitude.apparentMagnitudeOf(null, 0.1d, 0d) == Double.POSITIVE_INFINITY);
        assertEquals("and no aperture, however good, reaches anything that emits nothing", 0d,
                StellarMagnitude.detectionRangeLightYears(StellarMagnitude.luminositySuns(null),
                        Double.MAX_VALUE), 0d);
    }
}
