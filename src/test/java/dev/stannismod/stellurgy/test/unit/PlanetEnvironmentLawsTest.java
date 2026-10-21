package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.environment.LocalEnvironment;
import dev.stannismod.stellurgy.environment.MagnetosphereDerivation;
import dev.stannismod.stellurgy.environment.PlanetEnvironment;
import dev.stannismod.stellurgy.environment.PlanetaryDipole;
import dev.stannismod.stellurgy.environment.Trajectory;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Regions;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The laws of the environment query (`environment`): INV-ENV-01..05.
 *
 * <p>Every body here is built by this test from physical inputs it states — Earth's real eccentric dipole
 * where the law is about Earth, and bodies derived through {@link MagnetosphereDerivation} where it is
 * about derivation. Orbits are circular, positioned by the test's own Kepler arithmetic. What it does NOT
 * see: where production takes a body's bulk and seed from, and who hands it an orbit.</p>
 */
public class PlanetEnvironmentLawsTest {

    /** Earth's gravitational parameter, m³/s² — an input of the experiment. */
    private static final double EARTH_GM = 3.986e14d;
    /** Earth's mean radius, m. */
    private static final double EARTH_RADIUS_M = 6.371e6d;
    /** Seconds per tick, one game day standing for one Earth day — MECH-ENV-04's convention. */
    private static final double SECONDS_PER_TICK = 3.6d;
    /** One game day, ticks — the length of a day-long hold. */
    private static final long DAY_TICKS = 24_000L;
    /** Gy/day per Gy/s. */
    private static final double DAY_SECONDS = 86_400d;
    /** One krad(Si) per year, in Gy/day: 10 Gy over 365.25 days. */
    private static final double KRAD_PER_YEAR_IN_GY_PER_DAY = 10d / 365.25d;

    /**
     * Earth's eccentric dipole, 2020 — the inputs INV-ENV-05 states: the moment points to the geomagnetic
     * SOUTH pole, the centre sits 570 km toward 22°N 141°E; absorbing atmosphere 100 km; solar wind 2 nPa.
     */
    private static PlanetEnvironment earth() {
        return PlanetEnvironment.of(Optional.of(PlanetaryDipole.of(7.72e22d, direction(-80.7d, 107.3d),
                direction(22d, 141d).mul(570e3d), EARTH_RADIUS_M, 100e3d, 2e-9d)), DAY_TICKS, 0d);
    }

    private static Vector3d direction(double latDeg, double lonDeg) {
        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);
        return new Vector3d(Math.cos(lat) * Math.cos(lon), Math.cos(lat) * Math.sin(lon), Math.sin(lat));
    }

    /** A circular orbit of radius {@code radiusM} and inclination {@code inclinationDeg} about a body of {@code gm}. */
    private static Trajectory circular(double radiusM, double inclinationDeg, double gm) {
        double inclination = Math.toRadians(inclinationDeg);
        long period = Math.round(2d * Math.PI * Math.sqrt(radiusM * radiusM * radiusM / gm) / SECONDS_PER_TICK);
        return new Trajectory() {
            @Override
            public Vector3dc positionAt(long tick) {
                double u = 2d * Math.PI * tick / period;
                return new Vector3d(Math.cos(u), Math.sin(u) * Math.cos(inclination),
                        Math.sin(u) * Math.sin(inclination)).mul(radiusM);
            }

            @Override
            public long periodTicks() {
                return period;
            }
        };
    }

    private static double rate(PlanetEnvironment env, Vector3dc at, long tick) {
        LocalEnvironment here = env.sample(at, tick);
        if (!here.trappedDoseRate().isPresent()) {
            fail("a field-bearing body reported no trapped dose at " + at);
        }
        return here.trappedDoseRate().getAsDouble();
    }

    /** Mean dose rate along {@code path} over {@code days}, Gy/day. */
    private static double meanGyPerDay(PlanetEnvironment env, Trajectory path, long days) {
        long span = days * DAY_TICKS;
        return env.accruedDose(path, 0L, span) / (span * SECONDS_PER_TICK) * DAY_SECONDS;
    }

    /**
     * The tick-by-tick body-fixed position of the highest dose rate along {@code path} over a day, and the
     * ratio of that rate to the median: {@code [x, y, z, ratio]}.
     */
    private static double[] peakOverADay(PlanetEnvironment env, Trajectory path) {
        double[] rates = new double[(int) DAY_TICKS];
        double best = -1d;
        Vector3d where = new Vector3d();
        for (int t = 0; t < DAY_TICKS; t++) {
            Vector3dc p = path.positionAt(t);
            rates[t] = rate(env, p, t);
            if (rates[t] > best) {
                best = rates[t];
                where.set(p).rotateZ(-2d * Math.PI * t / DAY_TICKS);
            }
        }
        java.util.Arrays.sort(rates);
        double median = rates[rates.length / 2];
        return new double[]{where.x, where.y, where.z, median > 0d ? best / median : Double.POSITIVE_INFINITY};
    }

    /**
     * INV-ENV-01: along a 400 km, 51.6° orbit about Earth's eccentric dipole the dose rate spans at least
     * two orders of magnitude over a day, and its peak lies on the side of the body away from the dipole's
     * offset (offset toward 22°N 141°E; the peak's body-fixed direction must point against it).
     *
     * <p>Fails if production stops measuring a point's field shell from the dipole's own, offset centre —
     * the term that puts the high-dose segment on the weak-field side.</p>
     */
    @Pins("INV-ENV-01")
    @RedWitness(member = "PlanetaryDipole#trappedDoseRate", at = "new Vector3d(bodyFixed).sub(offset)",
            replacedBy = "new Vector3d(bodyFixed).add(offset)", fails = "INV-ENV-01", on = "2026-10-20")
    @Test
    public void aLowOrbitHasItsHighDoseSegmentOnTheWeakFieldSide() {
        double[] peak = peakOverADay(earth(), circular(EARTH_RADIUS_M + 400e3d, 51.6d, EARTH_GM));

        assertTrue("INV-ENV-01: the dose rate along one low orbit spans orders of magnitude; peak/median = "
                + peak[3], peak[3] >= 100d);
        double side = new Vector3d(peak[0], peak[1], peak[2]).normalize().dot(direction(22d, 141d));
        assertTrue("INV-ENV-01: the high-dose segment lies away from the dipole's offset; cos = " + side,
                side < 0d);
    }

    /**
     * INV-ENV-02: equal derivation inputs give equal samples everywhere, and seeds that differ put the
     * high-dose segment of the same low orbit in different places — for a rocky body and for a giant.
     */
    @Pins("INV-ENV-02")
    @Regions(of = "INV-ENV-02", value = {"rocky", "giant"})
    @RedWitness(verdict = "different seeds", member = "MagnetosphereDerivation#derive",
            at = "new SplittableRandom(seed)", replacedBy = "new SplittableRandom(0L)",
            fails = "INV-ENV-02: different seeds", on = "2026-10-20")
    @RedWitness(verdict = "equal seeds", member = "MagnetosphereDerivation#derive",
            at = "new SplittableRandom(seed)", replacedBy = "new SplittableRandom()",
            fails = "INV-ENV-02: equal seeds", on = "2026-10-20")
    @Test
    public void aSeedDecidesWhereAndTheSameSeedDecidesTheSame() {
        int rocky = 0;
        int giant = 0;
        // Rocky: Earth's bulk, spin and distance. Giant: Jupiter's (318 M, 11.2 R, a 0.41-day turn, 5.2 AU).
        for (boolean isGiant : new boolean[]{false, true}) {
            double mass = isGiant ? 318d : 1d;
            double radius = isGiant ? 11.2d : 1d;
            long spin = isGiant ? Math.round(0.41d * DAY_TICKS) : DAY_TICKS;
            double au = isGiant ? 5.2d : 1d;
            double radiusM = radius * EARTH_RADIUS_M;
            Trajectory low = circular(radiusM * 1.15d, 51.6d, EARTH_GM * mass);

            Vector3d previous = null;
            for (long seed = 1L; seed <= SEEDS_PER_KIND; seed++) {
                PlanetEnvironment a = PlanetEnvironment.of(
                        MagnetosphereDerivation.derive(mass, radius, spin, isGiant, true, au, seed), spin, 0d);
                PlanetEnvironment b = PlanetEnvironment.of(
                        MagnetosphereDerivation.derive(mass, radius, spin, isGiant, true, au, seed), spin, 0d);
                for (int t = 0; t < DAY_TICKS; t += 600) {
                    Vector3dc p = low.positionAt(t);
                    assertEquals("INV-ENV-02: equal seeds give equal samples (seed " + seed + ", tick " + t + ")",
                            rate(a, p, t), rate(b, p, t), 0d);
                }
                double[] peak = peakOverADay(a, low);
                Vector3d at = new Vector3d(peak[0], peak[1], peak[2]).normalize();
                if (previous != null) {
                    assertTrue("INV-ENV-02: different seeds put the high-dose segment in different places "
                            + "(seed " + seed + ", giant " + isGiant + ")", at.angle(previous) > Math.toRadians(5d));
                }
                previous = at;
                if (isGiant) {
                    giant++;
                } else {
                    rocky++;
                }
            }
        }
        assertTrue("arrangement: both regions visited (rocky " + rocky + ", giant " + giant + ")",
                rocky >= SEEDS_PER_KIND && giant >= SEEDS_PER_KIND);
    }

    /**
     * Seeds swept per kind. Two consecutive seeds' offsets land within 5° of each other with probability
     * (1 − cos 5°)/2 ≈ 0.002 when drawn uniformly, so over seven comparisons a correct derivation goes red
     * by chance about once in seventy runs of a DIFFERENT seed set — and the set is fixed, so never here.
     */
    private static final int SEEDS_PER_KIND = 8;

    /**
     * INV-ENV-03: a body whose stand-off is under one body radius reports its field and a trapped dose of
     * zero everywhere. The test's oracle is the Chapman–Ferraro stand-off of the dipole it builds: a moment
     * of 1e18 A·m² on a 6000 km body under 2 nPa of wind stands off at 0.23 radii. Liveness twin: Earth's
     * dipole at the same points traps something.
     */
    @Pins("INV-ENV-03")
    @RedWitness(member = "PlanetaryDipole#of",
            at = "Math.pow(2d * surfaceField * surfaceField / (MU0 * windPressurePa), 1d / 6d)",
            replacedBy = "8d", fails = "INV-ENV-03", on = "2026-10-20")
    @Test
    public void noMagnetosphereAboveTheSurfaceTrapsNothing() {
        double radiusM = 6.0e6d;
        PlanetEnvironment weak = PlanetEnvironment.of(Optional.of(PlanetaryDipole.of(1e18d,
                new Vector3d(0d, 0d, 1d), new Vector3d(), radiusM, 0d, 2e-9d)), DAY_TICKS, 0d);
        PlanetEnvironment strong = earth();
        double strongest = 0d;
        for (double altitude = 200e3d; altitude <= 20_000e3d; altitude *= 1.5d) {
            for (int lat = -60; lat <= 60; lat += 15) {
                for (int lon = 0; lon < 360; lon += 30) {
                    Vector3d p = direction(lat, lon).mul(radiusM + altitude);
                    LocalEnvironment here = weak.sample(p, 0L);
                    assertTrue("INV-ENV-03: a body without a magnetosphere still reports its field at " + p,
                            here.magneticField().isPresent() && here.magneticField().get().length() > 0d);
                    assertEquals("INV-ENV-03: no magnetosphere above the surface, no trapped dose at " + p,
                            0d, rate(weak, p, 0L), 0d);
                    strongest = Math.max(strongest, rate(strong, direction(lat, lon).mul(EARTH_RADIUS_M + altitude), 0L));
                }
            }
        }
        assertTrue("INV-ENV-03 liveness: Earth's magnetosphere traps something at the same points",
                strongest > 0d);
    }

    /**
     * INV-ENV-06: a rocky body keeps a dynamo only with a large metallic core, read from its bulk density,
     * and a giant always does. The bodies are the measured ones the clause names, by their real mass and
     * radius in Earth units: Mars (0.107, 0.532) and the Moon (0.0123, 0.273) derive no field and report
     * nothing; Mercury (0.055, 0.383), Earth and Saturn (95.2, 9.45 — the least dense planet) keep one.
     */
    @Pins("INV-ENV-06")
    @RedWitness(verdict = "rocky", member = "MagnetosphereDerivation#derive",
            at = "density < DYNAMO_DENSITY_G_CM3", replacedBy = "density < 0d",
            fails = "INV-ENV-06: a rocky body whose core is too small", on = "2026-10-21")
    @RedWitness(verdict = "giant", member = "MagnetosphereDerivation#derive",
            at = "!giant && density", replacedBy = "density",
            fails = "INV-ENV-06: a body that keeps a dynamo", on = "2026-10-21")
    @Test
    public void onlyACoreLargeEnoughKeepsADynamo() {
        Vector3d above = new Vector3d(1.3d, 0d, 0.2d);
        for (double[] dead : new double[][]{{0.107d, 0.532d}, {0.0123d, 0.273d}}) {
            Optional<PlanetaryDipole> dipole = MagnetosphereDerivation.derive(dead[0], dead[1], DAY_TICKS, false,
                    false, 1d, 7L);
            assertTrue("INV-ENV-06: a rocky body whose core is too small keeps no dynamo (" + dead[0] + " M, "
                    + dead[1] + " R)", !dipole.isPresent());
            PlanetEnvironment env = PlanetEnvironment.of(dipole, DAY_TICKS, 0d);
            LocalEnvironment here = env.sample(new Vector3d(above).mul(dead[1] * EARTH_RADIUS_M), 0L);
            assertTrue("INV-ENV-06: a body without a field reports neither a field nor a trapped dose",
                    !here.magneticField().isPresent() && !here.trappedDoseRate().isPresent());
            assertEquals("INV-ENV-06: nothing is accrued about a body without a field", 0d,
                    env.accruedDose(circular(dead[1] * EARTH_RADIUS_M * 1.3d, 30d, EARTH_GM * dead[0]),
                            0L, DAY_TICKS), 0d);
        }
        for (double[] live : new double[][]{{0.055d, 0.383d, 0d}, {1d, 1d, 0d}, {95.2d, 9.45d, 1d}}) {
            assertTrue("INV-ENV-06: a body that keeps a dynamo derives a field (" + live[0] + " M, " + live[1]
                    + " R)", MagnetosphereDerivation.derive(live[0], live[1], DAY_TICKS, live[2] > 0d, true, 1d,
                    7L).isPresent());
        }
    }

    /**
     * INV-ENV-04: over a day the accrued dose along a low orbit agrees with the dose integrated tick by
     * tick from the sample within 5 % — a fifth of INV-ENV-05's own tolerance, so integration error never
     * decides a calibration verdict — and so does any split of the day into two consecutive intervals.
     */
    @Pins("INV-ENV-04")
    @RedWitness(member = "PlanetEnvironment#accruedDose", at = "* span * SECONDS_PER_TICK", replacedBy = "* span",
            fails = "INV-ENV-04", on = "2026-10-20")
    @Test
    public void accrualAgreesWithTheSample() {
        PlanetEnvironment env = earth();
        Trajectory low = circular(EARTH_RADIUS_M + 400e3d, 51.6d, EARTH_GM);
        double reference = 0d;
        for (long t = 0L; t < DAY_TICKS; t++) {
            reference += rate(env, low.positionAt(t), t) * SECONDS_PER_TICK;
        }
        assertTrue("arrangement: the day's dose is not zero", reference > 0d);

        double whole = env.accruedDose(low, 0L, DAY_TICKS);
        assertEquals("INV-ENV-04: accrued over a day agrees with the sample", 1d, whole / reference, 0.05d);
        for (long split : new long[]{1L, 777L, 12_345L, DAY_TICKS - 1L}) {
            double parts = env.accruedDose(low, 0L, split) + env.accruedDose(low, split, DAY_TICKS);
            assertEquals("INV-ENV-04: a day split at tick " + split + " accrues what the day does",
                    1d, parts / reference, 0.05d);
        }
    }

    /**
     * INV-ENV-05, the anchor: a 400 km, 51.6° orbit about Earth averages R3DE's 426 µGy/day within a quarter
     * (Dachev et al. 2012, behind under 0.4 g/cm²). Thirty days average over the beat of orbit and spin.
     */
    @Pins("INV-ENV-05")
    @RedWitness(member = "RadiationBelts#LOSS_CONE_EXPONENT", at = "3.7d", replacedBy = "0.5d",
            fails = "INV-ENV-05", on = "2026-10-20")
    @Test
    public void theLowOrbitReproducesItsMeasuredDose() {
        double perDay = meanGyPerDay(earth(), circular(EARTH_RADIUS_M + 400e3d, 51.6d, EARTH_GM), 30L);
        assertEquals("INV-ENV-05: the 400 km, 51.6° orbit averages 426 µGy/day; got " + perDay * 1e6,
                1d, perDay / 426e-6d, 0.25d);
    }

    /** INV-ENV-05, unfitted: geostationary orbit lands in the published 5–10 krad(Si)/yr. */
    @Pins("INV-ENV-05")
    @RedWitness(member = "RadiationBelts#OUTER_PEAK_GY_PER_S", at = "1.62d", replacedBy = "16.2d",
            fails = "INV-ENV-05", on = "2026-10-20")
    @Test
    public void geostationaryOrbitLandsInItsPublishedRange() {
        double perDay = meanGyPerDay(earth(), circular(EARTH_RADIUS_M + 35_786e3d, 0d, EARTH_GM), 30L);
        double kradPerYear = perDay / KRAD_PER_YEAR_IN_GY_PER_DAY;
        assertTrue("INV-ENV-05: geostationary orbit takes 5-10 krad/yr; got " + kradPerYear,
                kradPerYear >= 5d && kradPerYear <= 10d);
    }

    /** INV-ENV-05, unfitted: the 20 200 km, 55° navigation orbit lands in the published 20–50 krad(Si)/yr. */
    @Pins("INV-ENV-05")
    @RedWitness(member = "RadiationBelts#OUTER_PEAK_L", at = "4.5d", replacedBy = "3.0d",
            fails = "INV-ENV-05", on = "2026-10-20")
    @Test
    public void theNavigationOrbitLandsInItsPublishedRange() {
        double perDay = meanGyPerDay(earth(), circular(EARTH_RADIUS_M + 20_200e3d, 55d, EARTH_GM), 30L);
        double kradPerYear = perDay / KRAD_PER_YEAR_IN_GY_PER_DAY;
        assertTrue("INV-ENV-05: the navigation orbit takes 20-50 krad/yr; got " + kradPerYear,
                kradPerYear >= 20d && kradPerYear <= 50d);
    }
}
