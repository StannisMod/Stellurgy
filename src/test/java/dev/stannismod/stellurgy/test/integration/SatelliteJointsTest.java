package dev.stannismod.stellurgy.test.integration;

import dev.stannismod.stellurgy.api.satellite.SatelliteOrbit;
import dev.stannismod.stellurgy.environment.BodyRotation;
import dev.stannismod.stellurgy.environment.MagnetosphereDerivation;
import dev.stannismod.stellurgy.environment.PlanetEnvironment;
import dev.stannismod.stellurgy.satellite.DoseReadings;
import dev.stannismod.stellurgy.satellite.FailureJournal;
import dev.stannismod.stellurgy.satellite.SatelliteAccrual;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Regions;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Where a satellite's dose meets the environment it is taken from (`satellite` ↔ `environment`):
 * INV-SAT-22 (failures follow the place) and INV-SAT-26 (the dosimeter reads the place), each the
 * satellite's accrual on one side and {@link PlanetEnvironment#sample} on the other, both production.
 *
 * <p>The body is one the test states — an Earth mass and radius, a day's spin, rocky, with air, at 1 AU —
 * and its field is production's derivation from those inputs. What this does NOT see: where production
 * takes a real body's inputs from, and that the accrual is called at all — the harness holds both.</p>
 */
public class SatelliteJointsTest {

    /** Earth's gravitational parameter, m³/s² — an input of the experiment. */
    private static final double EARTH_GM = 3.986004418e14d;
    /** One Earth radius, m — an input of the experiment. */
    private static final double BODY_RADIUS_M = 6.371e6d;
    /** A 400 km orbit, m — an input of the experiment: low enough that most of it is below the belts. */
    private static final double LOW_ORBIT_RADIUS_M = BODY_RADIUS_M + 400e3d;
    /** One turn of the body, ticks: an Earth day. */
    private static final long DAY_TICKS = 24_000L;
    /** The field's seed — an input; any seed offsets the dipole and so makes an anomaly. */
    private static final long FIELD_SEED = 20_261_021L;
    /** Points each satellite holds: a buffer large enough that a pass over the anomaly loses some. */
    private static final int BUFFER_POINTS = 20_000;
    /** Segments a window spans: about a tenth of the orbit, the time a low orbit spends over the anomaly. */
    private static final int WINDOW_SEGMENTS = 6;

    private static PlanetEnvironment field() {
        return PlanetEnvironment.of(MagnetosphereDerivation.derive(1d, 1d, DAY_TICKS, false, true, 1d, FIELD_SEED),
                DAY_TICKS, 0d);
    }

    private static SatelliteOrbit lowOrbitReleasedAt(long tick) {
        return SatelliteOrbit.releasedAt(LOW_ORBIT_RADIUS_M, Math.toRadians(51.6d), EARTH_GM,
                BodyRotation.of(DAY_TICKS, 0d), tick);
    }

    private static SatelliteAccrual.Body about(SatelliteOrbit orbit, PlanetEnvironment environment) {
        return new SatelliteAccrual.Body(orbit, Optional.of(environment), tick -> Optional.of(new Vector3d(1d, 0d, 0d)),
                BODY_RADIUS_M);
    }

    /** A data satellite with a buffer of {@link #BUFFER_POINTS} and nothing to shield it. */
    private static List<SatelliteAccrual.Part> collector() {
        List<SatelliteAccrual.Part> parts = new ArrayList<>();
        parts.add(new SatelliteAccrual.Part(0, SatelliteAccrual.PartKind.ELECTRONICS, true, 0d, 0, 0d));
        parts.add(new SatelliteAccrual.Part(1, SatelliteAccrual.PartKind.POWER_GENERATION, false, 40d, 0, 0d));
        parts.add(new SatelliteAccrual.Part(4, SatelliteAccrual.PartKind.ELECTRONICS, false, 0d, BUFFER_POINTS, 0d));
        return parts;
    }

    private static double rateAt(PlanetEnvironment environment, SatelliteOrbit orbit, long tick) {
        OptionalDouble rate = environment.sample(orbit.positionAt(tick), tick).trappedDoseRate();
        assertTrue("arrangement: the field-bearing body gave no dose at tick " + tick, rate.isPresent());
        return rate.getAsDouble();
    }

    private static int lost(FailureJournal journal) {
        int sum = 0;
        for (FailureJournal.Entry e : journal.entries()) {
            if (e.kind == FailureJournal.Kind.DATA_LOST) {
                sum += e.count;
            }
        }
        return sum;
    }

    /**
     * INV-SAT-22. Two satellites released one body-turn apart share an orbit's plane (the node is under
     * meridian 0 at release) at different phases. The window is the first one in which, at every
     * segment's middle, the first takes a non-zero dose and the second none — the environment's own answer
     * for each point. Over it the first journals losses and the second, which met no dose, none. Over a day
     * of the first alone, the losses weighted by their count sit at {@code Σr²/Σr} of the environment's
     * rates over the day's segments — where losses in proportion to the dose put them — within a quarter.
     */
    @Pins("INV-SAT-22")
    @RedWitness(verdict = "crossing", member = "RadiationWear#pointLossProbability",
            at = "return protonGy > 0d ? -Math.expm1(-UPSETS_PER_POINT_PER_GY * protonGy) : 0d;", replacedBy = "return 0d;",
            fails = "INV-SAT-22: the satellite crossing the high-dose segment journalled no loss", on = "2026-10-21")
    @RedWitness(verdict = "outside", member = "SatelliteAccrual#advance", at = "if (state.data > 0L && memoryProtons > 0d) {",
            replacedBy = "if (state.data > 0L) { memoryProtons += 1e-5d;",
            fails = "INV-SAT-22: the satellite outside it journalled a loss", on = "2026-10-21")
    @RedWitness(verdict = "correlation", member = "RadiationWear#pointLossProbability",
            at = "return protonGy > 0d ? -Math.expm1(-UPSETS_PER_POINT_PER_GY * protonGy) : 0d;",
            replacedBy = "return protonGy > 0d ? 2e-4d : 0d;",
            fails = "INV-SAT-22: the losses sit at a dose rate the dose does not predict", on = "2026-10-21")
    @Test
    public void failuresFollowThePlace() {
        PlanetEnvironment environment = field();
        SatelliteOrbit first = lowOrbitReleasedAt(0L);
        SatelliteOrbit second = lowOrbitReleasedAt(DAY_TICKS);
        long step = SatelliteAccrual.segmentTicks(first);
        assertEquals("arrangement: the two share a segment grid", 0L, DAY_TICKS % step);

        long windowStart = -1L;
        for (long start = DAY_TICKS; start < 3L * DAY_TICKS && windowStart < 0L; start += step) {
            boolean firstExposed = true;
            boolean secondClear = true;
            for (int k = 0; k < WINDOW_SEGMENTS; k++) {
                long mid = start + k * step + step / 2L;
                firstExposed &= rateAt(environment, first, mid) > 0d;
                secondClear &= rateAt(environment, second, mid) == 0d;
            }
            if (firstExposed && secondClear) {
                windowStart = start;
            }
        }
        assertTrue("arrangement: a window where only the first meets the field", windowStart >= 0L);

        SatelliteAccrual.State[] states = new SatelliteAccrual.State[2];
        SatelliteOrbit[] orbits = {first, second};
        for (int i = 0; i < 2; i++) {
            long skipped = (windowStart - orbits[i].epochTick()) / step;
            states[i] = new SatelliteAccrual.State(skipped, 0d, 0d, BUFFER_POINTS, 0d, new FailureJournal(), null);
            SatelliteAccrual.advance(about(orbits[i], environment), collector(),
                    new SatelliteAccrual.Payload(true, false, 100d), states[i], new SatelliteAccrual.Rules(true, 1d),
                    100L + i, windowStart + WINDOW_SEGMENTS * step);
            assertEquals("arrangement: satellite " + i + " took exactly the window",
                    skipped + WINDOW_SEGMENTS, states[i].segmentsDone());
        }
        assertTrue("INV-SAT-22: the satellite crossing the high-dose segment journalled no loss",
                lost(states[0].journal()) > 0);
        assertEquals("INV-SAT-22: the satellite outside it journalled a loss", 0, lost(states[1].journal()));

        SatelliteAccrual.State day = new SatelliteAccrual.State(0L, 0d, 0d, BUFFER_POINTS, 0d, new FailureJournal(), null);
        SatelliteAccrual.advance(about(first, environment), collector(), new SatelliteAccrual.Payload(true, false, 100d),
                day, new SatelliteAccrual.Rules(true, 1d), 102L, DAY_TICKS);
        double sumRate = 0d;
        double sumSquare = 0d;
        int dosed = 0;
        for (long t = step / 2L; t < DAY_TICKS; t += step) {
            double rate = rateAt(environment, first, t);
            sumRate += rate;
            sumSquare += rate * rate;
            dosed += rate > 0d ? 1 : 0;
        }
        double predicted = sumSquare / sumRate;
        double weighted = 0d;
        int count = 0;
        for (FailureJournal.Entry e : day.journal().entries()) {
            if (e.kind == FailureJournal.Kind.DATA_LOST) {
                weighted += rateAt(environment, first, e.tick) * e.count;
                count += e.count;
            }
        }
        // The weighted rate is a mean over `count` Poisson-drawn losses, so its relative noise is about the
        // spread of the rate over √count: with 100 or more losses and a spread under one, under a tenth.
        // Losses that ignored the dose would sit at the plain mean over the dosed segments, which differs
        // from the prediction by the factor 1 + CV² — so CV² of a half or more makes a quarter a test.
        double meanOverDosed = sumRate / dosed;
        double spreadSquared = predicted / meanOverDosed - 1d;
        assertTrue("arrangement: a day on the anomaly's orbit lost 100 points or more: " + count, count >= 100);
        assertTrue("arrangement: the dose varies enough over the region to tell a proportional loss from a flat one: CV² "
                + spreadSquared, spreadSquared >= 0.5d);
        assertEquals("INV-SAT-22: the losses sit at a dose rate the dose does not predict", predicted,
                weighted / count, 0.25d * predicted);
    }

    /**
     * INV-SAT-26. A dosimeter's every reading over a day on the low orbit equals what the environment gives
     * at the satellite's point at that reading's tick; about a body with no field, every reading is "absent
     * here", and there is one for each segment.
     */
    @Pins("INV-SAT-26")
    @Regions(of = "INV-SAT-26", value = {"field", "no field"})
    @RedWitness(verdict = "field", member = "SatelliteAccrual#advance", at = "state.readings.record(mid, phase, rate);",
            replacedBy = "state.readings.record(mid, phase, OptionalDouble.of(0d));",
            fails = "INV-SAT-26: field — the reading at tick", on = "2026-10-21")
    @RedWitness(verdict = "no field", member = "SatelliteAccrual#advance",
            at = "OptionalDouble rate = here.get().trappedDoseRate();",
            replacedBy = "OptionalDouble rate = OptionalDouble.of(here.get().trappedDoseRate().orElse(0d));",
            fails = "INV-SAT-26: no field — the reading at tick", on = "2026-10-21")
    @Test
    public void theDosimeterReadsThePlace() {
        SatelliteOrbit orbit = lowOrbitReleasedAt(0L);
        long step = SatelliteAccrual.segmentTicks(orbit);
        PlanetEnvironment[] bodies = {field(), PlanetEnvironment.of(Optional.empty(), DAY_TICKS, 0d)};
        for (int i = 0; i < 2; i++) {
            String name = i == 0 ? "field" : "no field";
            SatelliteAccrual.State state = new SatelliteAccrual.State(0L, 0d, 1000d, 0L, 0d, new FailureJournal(),
                    new DoseReadings());
            SatelliteAccrual.advance(about(orbit, bodies[i]), collector(), new SatelliteAccrual.Payload(false, true, 0d),
                    state, new SatelliteAccrual.Rules(true, 1d), 200L, DAY_TICKS);
            List<DoseReadings.Reading> readings = state.readings().readings();
            assertEquals("arrangement: " + name + " — one reading per segment", DAY_TICKS / step, readings.size());
            int nonZero = 0;
            for (DoseReadings.Reading r : readings) {
                Vector3dc at = orbit.positionAt(r.tick);
                OptionalDouble expected = bodies[i].sample(at, r.tick).trappedDoseRate();
                assertEquals("INV-SAT-26: " + name + " — the reading at tick " + r.tick + " is not the place's",
                        expected, r.gyPerS());
                if (i == 1) {
                    assertFalse("INV-SAT-26: no field — a reading is not absent", r.gyPerS().isPresent());
                } else if (r.gyPerS().getAsDouble() > 0d) {
                    nonZero++;
                }
            }
            assertTrue("arrangement: " + name + " — some readings were of a dose", i == 1 || nonZero > 0);
        }
    }
}
