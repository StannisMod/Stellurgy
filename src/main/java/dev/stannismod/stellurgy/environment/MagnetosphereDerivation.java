package dev.stannismod.stellurgy.environment;

import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;
import org.joml.Vector3d;

import java.util.Optional;
import java.util.SplittableRandom;

/**
 * Where a body's magnetic dipole comes from: its STRENGTH from its bulk and spin, its ORIENTATION and
 * OFFSET drawn from its seed.
 *
 * <p><b>Strength</b> follows the empirical magnetic Bode's law — a planet's dipole moment grows with its
 * spin angular momentum {@code J ∝ M R² ω}. Through Earth (7.72e22 A·m²), the exponent 0.86 puts
 * Mercury at 3.6e-4 and Jupiter at 2.0e4 Earth moments, both near their measured values. A slow rotator
 * receives a weak field, and where that field cannot hold off the stellar wind above the surface it
 * traps nothing (Venus).</p>
 *
 * <p><b>Whether a rocky body has a dynamo at all</b> is read from its bulk density, which is how large its
 * metallic core is. Mass cannot decide it: Mercury (0.055 Earth masses) keeps a field and Mars (0.107)
 * does not. Density does: Mercury 5.43 and Earth 5.51 g/cm³ keep theirs; Mars 3.93 and the Moon 3.34 lost
 * theirs. A giant's dynamo runs in metallic hydrogen or ionic water and always has a field.</p>
 *
 * <p><b>Orientation and offset</b> are drawn uniformly within ranges bounded by the planets we have
 * measured — rocky bodies by Mercury and Earth, giants by Saturn, Jupiter, Uranus and Neptune. The
 * bounds are measured; the uniform draw between them is a choice. Directions (the axis's longitude, the
 * offset's direction, the polarity) are uniform: no direction is preferred.</p>
 */
public final class MagnetosphereDerivation {

    /** Earth's dipole moment, A·m² (IGRF, epoch 2020). */
    private static final double EARTH_MOMENT_AM2 = 7.72e22d;
    /** Magnetic Bode's law exponent, fitted through Earth, Mercury and Jupiter (see the class comment). */
    private static final double BODE_EXPONENT = 0.86d;
    /** Earth's mean radius, metres. */
    private static final double EARTH_RADIUS_M = 6.371e6d;
    /** Altitude below which trapped particles are absorbed by an atmosphere — Earth's lower thermosphere. */
    private static final double ABSORBING_ALTITUDE_M = 100e3d;
    /** The stellar wind's dynamic pressure at 1 AU from a Sun-like star, Pa (typical 1–3 nPa); falls as 1/d². */
    private static final double WIND_PRESSURE_AT_1_AU_PA = 2e-9d;

    /** Earth's mean density, g/cm³ — converts a body's mass and radius in Earth units to a density. */
    private static final double EARTH_DENSITY_G_CM3 = 5.51d;
    /**
     * The bulk density below which a rocky body keeps no dynamo, g/cm³: midway between the densest body
     * whose field has died (Mars, 3.93) and the least dense one that keeps it (Mercury, 5.43). The two
     * bounds are measured; the midpoint between them is a choice.
     */
    private static final double DYNAMO_DENSITY_G_CM3 = 4.7d;

    /** Rocky bodies' tilt between the spin and dipole axes, degrees: Mercury under 1°, Earth about 10°. */
    private static final double ROCKY_TILT_MAX_DEG = 10d;
    /** Rocky bodies' offset, body radii: Earth's eccentric dipole sits 0.09 R off centre, Mercury's 0.2 R. */
    private static final double ROCKY_OFFSET_MIN = 0.09d;
    private static final double ROCKY_OFFSET_MAX = 0.2d;
    /** Giants' tilt, degrees: Saturn's is under 0.1°, Uranus's 59°. */
    private static final double GIANT_TILT_MAX_DEG = 59d;
    /** Giants' offset, body radii: Saturn's 0.04 R, Neptune's 0.55 R. */
    private static final double GIANT_OFFSET_MIN = 0.04d;
    private static final double GIANT_OFFSET_MAX = 0.55d;

    private MagnetosphereDerivation() {
    }

    /**
     * The dipole of a body, or empty when it keeps no dynamo (a rocky body below the dynamo density).
     *
     * @param massEarths          mass in Earth masses, positive
     * @param radiusEarths        radius in Earth radii, positive
     * @param rotationPeriodTicks one turn, in ticks, positive
     * @param giant               a gas or ice giant rather than a rocky body
     * @param hasAtmosphere       whether an atmosphere absorbs trapped particles above the surface
     * @param orbitalDistanceAu   distance from its star, AU, positive — where the stellar wind is read
     * @param seed                the body's own seed; equal seeds give equal dipoles
     */
    public static Optional<PlanetaryDipole> derive(double massEarths, double radiusEarths, long rotationPeriodTicks,
                                                   boolean giant, boolean hasAtmosphere, double orbitalDistanceAu,
                                                   long seed) {
        if (!(massEarths > 0d) || !(radiusEarths > 0d) || rotationPeriodTicks <= 0L || !(orbitalDistanceAu > 0d)) {
            throw new IllegalArgumentException("a dipole needs positive mass, radius, rotation period and distance");
        }
        double density = EARTH_DENSITY_G_CM3 * massEarths / (radiusEarths * radiusEarths * radiusEarths);
        if (!giant && density < DYNAMO_DENSITY_G_CM3) {
            return Optional.empty();
        }
        double spin = massEarths * radiusEarths * radiusEarths
                * ((double) AstronomicalBodyHelper.TICKS_PER_DAY / rotationPeriodTicks);
        double moment = EARTH_MOMENT_AM2 * Math.pow(spin, BODE_EXPONENT);

        SplittableRandom draw = new SplittableRandom(seed);
        double tilt = Math.toRadians(draw.nextDouble() * (giant ? GIANT_TILT_MAX_DEG : ROCKY_TILT_MAX_DEG));
        double axisLongitude = draw.nextDouble() * 2d * Math.PI;
        double polarity = draw.nextBoolean() ? 1d : -1d;
        Vector3d axis = new Vector3d(Math.sin(tilt) * Math.cos(axisLongitude),
                Math.sin(tilt) * Math.sin(axisLongitude), Math.cos(tilt)).mul(polarity);

        double offsetFraction = giant
                ? GIANT_OFFSET_MIN + draw.nextDouble() * (GIANT_OFFSET_MAX - GIANT_OFFSET_MIN)
                : ROCKY_OFFSET_MIN + draw.nextDouble() * (ROCKY_OFFSET_MAX - ROCKY_OFFSET_MIN);
        double offsetZ = 2d * draw.nextDouble() - 1d;
        double offsetLongitude = draw.nextDouble() * 2d * Math.PI;
        double ring = Math.sqrt(1d - offsetZ * offsetZ);
        double radiusM = radiusEarths * EARTH_RADIUS_M;
        Vector3d offset = new Vector3d(ring * Math.cos(offsetLongitude), ring * Math.sin(offsetLongitude), offsetZ)
                .mul(offsetFraction * radiusM);

        double wind = WIND_PRESSURE_AT_1_AU_PA / (orbitalDistanceAu * orbitalDistanceAu);
        return Optional.of(PlanetaryDipole.of(moment, axis, offset, radiusM,
                hasAtmosphere ? ABSORBING_ALTITUDE_M : 0d, wind));
    }
}
