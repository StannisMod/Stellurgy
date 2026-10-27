package dev.stannismod.stellurgy.probe;

import dev.stannismod.stellurgy.api.satellite.SatelliteOrbit;
import dev.stannismod.stellurgy.environment.BodyRotation;

import java.util.SplittableRandom;

/**
 * The first probe as a flying object: the orbit it lived on, and the state it entered its planet's air in.
 * Pure: every input is a number about the body, so the same body and seed give the same probe.
 */
public final class FirstProbe {

    /**
     * The shells the probe's orbit lies between, in body radii. The inner belt spans roughly shell 1.15 to 2
     * and peaks near 1.5 (the trapped-radiation model's own figures), and a circular orbit at radius r crosses
     * every shell at or above r — so an orbit inside this range flies through the belt's heart.
     */
    static final double ORBIT_MIN_RADII = 1.3d;
    static final double ORBIT_MAX_RADII = 1.7d;
    /**
     * The steepest inclination drawn, radians: low enough that the orbit spends its time near the belt's equator.
     * Effectively final, process lifetime: built once at class initialisation.
     */
    static final double INCLINATION_MAX_RAD = Math.toRadians(40d);

    /**
     * How long before the world's clock began the probe was released, ticks: a year of days. Its record runs
     * from then to its death or to the clock's zero, so a probe that would outlive the year is found still
     * working at the start of the world, and its record does not break off.
     */
    public static final long AGE_TICKS = 365L * 24_000L;

    /**
     * Air's scale height over the height its atmosphere ends at: 8.5 km (the U.S. Standard Atmosphere's
     * near-surface scale height) over 100 km (the Kármán line). A body's air is scaled by its own line.
     */
    static final double SCALE_HEIGHT_PER_ORBIT_LINE = 8.5d / 100d;
    /** Standard gravity, m/s² (ISO 80000-3): what a world's gravity multiplier of one stands for. */
    public static final double STANDARD_GRAVITY_MS2 = 9.80665d;
    /** Sea-level air density at one atmosphere, kg/m³ (U.S. Standard Atmosphere, 15 °C). */
    static final double ONE_ATM_DENSITY_KG_M3 = 1.225d;
    /**
     * The probe's mass over its drag area times drag coefficient, kg/m²: a 14 kg, two-metre, 15 cm wide
     * cylinder tumbling — about 0.2 m² of mean cross-section at a drag coefficient near one.
     */
    static final double BALLISTIC_KG_M2 = 70d;

    /**
     * The band it lands in around the player at its entry, blocks: 16 to 64 chunks. The outer edge is the
     * maintainer's (2026-10-26, *"скажем... 64 чанка"*); the inner one keeps it beyond what the player can
     * walk to without the radio.
     */
    public static final double LANDING_MIN_BLOCKS = 16d * 16d;
    public static final double LANDING_MAX_BLOCKS = 64d * 16d;

    /**
     * How far below the horizon the sun must be for it to be night, as a fraction of a day's turn: six
     * degrees, where civil twilight ends and a re-entry's glow stands out against the sky.
     */
    static final double NIGHT_DEPTH_TURNS = 6d / 360d;

    private FirstProbe() {
    }

    /**
     * Whether it is night under a sky at {@code celestialAngle} — the world's own angle, where 0 is noon,
     * 0.25 sunset and 0.75 sunrise: the sun is more than {@link #NIGHT_DEPTH_TURNS} below the horizon.
     */
    public static boolean isNight(float celestialAngle) {
        double a = celestialAngle - Math.floor(celestialAngle);
        return a > 0.25d + NIGHT_DEPTH_TURNS && a < 0.75d - NIGHT_DEPTH_TURNS;
    }

    /**
     * Whether the probe falls now: the first player has logged in, {@code delayTicks} have passed since, and
     * it is night under a sky at {@code celestialAngle}.
     *
     * @param firstLoginTick the world time of the first player's first login; negative while nobody has
     */
    public static boolean due(long firstLoginTick, long now, long delayTicks, float celestialAngle) {
        return firstLoginTick >= 0L && now - firstLoginTick >= delayTicks && isNight(celestialAngle);
    }

    /**
     * Where it lands, relative to the player: a point drawn uniformly over the area of the band between
     * {@link #LANDING_MIN_BLOCKS} and {@link #LANDING_MAX_BLOCKS}, as {x, z} offsets in blocks.
     */
    public static double[] landingOffset(SplittableRandom draw) {
        double inner = LANDING_MIN_BLOCKS * LANDING_MIN_BLOCKS;
        double outer = LANDING_MAX_BLOCKS * LANDING_MAX_BLOCKS;
        double r = Math.sqrt(inner + draw.nextDouble() * (outer - inner));
        double angle = draw.nextDouble() * 2d * Math.PI;
        return new double[]{r * Math.cos(angle), r * Math.sin(angle)};
    }

    /**
     * The probe's orbit about a body of radius {@code bodyRadiusM} and gravitational parameter
     * {@code muM3S2}, turning by {@code rotation}: circular, through the inner belt, its radius and inclination
     * drawn from {@code seed}; released {@link #AGE_TICKS} before the clock's zero.
     */
    public static SatelliteOrbit orbit(long seed, double bodyRadiusM, double muM3S2, BodyRotation rotation) {
        SplittableRandom draw = new SplittableRandom(seed);
        double radii = ORBIT_MIN_RADII + draw.nextDouble() * (ORBIT_MAX_RADII - ORBIT_MIN_RADII);
        double inclination = draw.nextDouble() * INCLINATION_MAX_RAD;
        return SatelliteOrbit.releasedAt(radii * bodyRadiusM, inclination, muM3S2, rotation, -AGE_TICKS);
    }

    /**
     * The state the probe enters its planet's air in, and that air: a body of radius {@code bodyRadiusM} with
     * surface gravity {@code surfaceGravityMS2}, air of {@code atmospherePercent} of one atmosphere at its
     * surface, and an atmosphere line {@code orbitLineAltitudeM} above that surface.
     *
     * <p>It enters at the line, at the line's circular speed — an orbit that has decayed to the top of the
     * air — moving downward at the angle that puts the periapsis of its vacuum orbit as far below the surface
     * as the line lies above it. So it reaches the ground on any body, airless ones included.</p>
     */
    public static EntryTrajectory.Entry entry(double bodyRadiusM, double surfaceGravityMS2, double atmospherePercent,
                                              double orbitLineAltitudeM) {
        double r0 = bodyRadiusM + orbitLineAltitudeM;
        double mu = surfaceGravityMS2 * bodyRadiusM * bodyRadiusM;
        // A circular-speed orbit entered at angle g has eccentricity sin g and periapsis r0 (1 - sin g).
        double descent = StrictMath.asin(Math.min(1d, 2d * orbitLineAltitudeM / r0));
        return new EntryTrajectory.Entry(bodyRadiusM, surfaceGravityMS2,
                ONE_ATM_DENSITY_KG_M3 * Math.max(0d, atmospherePercent) / 100d,
                SCALE_HEIGHT_PER_ORBIT_LINE * orbitLineAltitudeM, BALLISTIC_KG_M2, orbitLineAltitudeM,
                StrictMath.sqrt(mu / r0), descent);
    }
}
