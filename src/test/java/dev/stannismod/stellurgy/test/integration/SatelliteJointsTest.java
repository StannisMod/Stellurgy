package dev.stannismod.stellurgy.test.integration;

import dev.stannismod.stellurgy.api.satellite.SatelliteOrbit;
import dev.stannismod.stellurgy.environment.BodyRotation;
import dev.stannismod.stellurgy.environment.MagnetosphereDerivation;
import dev.stannismod.stellurgy.environment.PlanetEnvironment;
import dev.stannismod.stellurgy.environment.PlanetaryDipole;
import dev.stannismod.stellurgy.satellite.DoseReadings;
import dev.stannismod.stellurgy.satellite.FailureJournal;
import dev.stannismod.stellurgy.satellite.HeldModel;
import dev.stannismod.stellurgy.satellite.SatelliteAccrual;
import dev.stannismod.stellurgy.satellite.SatelliteDosimeter;
import dev.stannismod.stellurgy.tile.satellite.TerminalReadout;
import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;
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
                    new SatelliteAccrual.Payload(true, false, 100d, 2d), states[i], new SatelliteAccrual.Rules(true, 1d),
                    100L + i, windowStart + WINDOW_SEGMENTS * step);
            assertEquals("arrangement: satellite " + i + " took exactly the window",
                    skipped + WINDOW_SEGMENTS, states[i].segmentsDone());
        }
        assertTrue("INV-SAT-22: the satellite crossing the high-dose segment journalled no loss",
                lost(states[0].journal()) > 0);
        assertEquals("INV-SAT-22: the satellite outside it journalled a loss", 0, lost(states[1].journal()));

        SatelliteAccrual.State day = new SatelliteAccrual.State(0L, 0d, 0d, BUFFER_POINTS, 0d, new FailureJournal(), null);
        SatelliteAccrual.advance(about(first, environment), collector(), new SatelliteAccrual.Payload(true, false, 100d, 2d),
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
            // A charged battery that carries the dosimeter through every eclipse, so each segment is read.
            SatelliteAccrual.State state = new SatelliteAccrual.State(0L, 1e6d, 1e6d, 0L, 0d, new FailureJournal(),
                    new DoseReadings());
            SatelliteAccrual.advance(about(orbit, bodies[i]), collector(), new SatelliteAccrual.Payload(false, true, 0d, 1d),
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

    /** Orbits flown for the closing law: four orbits of 64 readings fill the terminal's 256 rows exactly. */
    private static final int CLOSING_ORBITS = TerminalReadout.CAPACITY / SatelliteAccrual.SEGMENTS_PER_PERIOD;
    /** The body's turn and day, ticks: a vanilla day, which is what the overworld's body turns by. */
    private static final double CLOSING_DAY_TICKS = 24_000d;
    /** Seeds tried for a field that is not uniform along the equator; the first that is, is used. */
    private static final int CLOSING_SEEDS = 200;

    /**
     * Pins INV-SAT-42 where it meets the field it models (environment, through INV-SAT-26 and INV-SAT-41): a
     * dosimeter on an equatorial orbit inside the inner belt of a body whose field is tilted and offset —
     * production's derivation from an Earth's inputs — reads for four orbits, and one terminal takes every
     * reading, each placed by production's ground track. The readings the terminal holds then expect each
     * reading better than the uniform field does, AND better than a map keyed by the orbit's phase — the
     * squared residuals sum to less than either's. The first is MECH-SAT-33's closing; the second is why a
     * region is a place over the body and not a stretch of the orbit: the body turns under the orbit, so one
     * phase sees different places. The body and its turn are the test's; that production
     * builds a real body's environment from the same turn it places rows by is `SatelliteLife#environmentOf`'s,
     * read in code. Counted: how many readings stand where another reading also stood (at least half the
     * table), so the closing is measured where there is something to close.
     */
    @Pins("INV-SAT-42")
    @RedWitness(member = "HeldModel#expect", at = "return here.isPresent() ? here : meanOf(Basis.ORBIT, null, held);",
            replacedBy = "return meanOf(Basis.ORBIT, null, held);", fails = "INV-SAT-42: closing", on = "2026-10-25")
    @RedWitness(member = "GroundTrack#under", at = "Downlink.under(orbit.positionAt(tick), turn, tick)",
            replacedBy = "Downlink.under(orbit.positionAt(tick), turn, 0L)", fails = "INV-SAT-42: closing",
            on = "2026-10-25")
    @Test
    public void readingARegionAgainClosesWhatTheUniformFieldLeftThere() {
        BodyRotation turn = BodyRotation.of(CLOSING_DAY_TICKS, 0d);
        SatelliteOrbit orbit = SatelliteOrbit.releasedAt(1.5d * AstronomicalBodyHelper.EARTH_RADIUS_METRES, 0d,
                SatelliteOrbit.EARTH_MU_M3_S2, turn, 0L);
        TerminalReadout readout = null;
        long seed = 0L;
        for (; seed < CLOSING_SEEDS && readout == null; seed++) {
            Optional<PlanetaryDipole> field = MagnetosphereDerivation.derive(1d, 1d, (long) CLOSING_DAY_TICKS, false,
                    true, 1d, seed);
            if (!field.isPresent()) {
                continue;
            }
            TerminalReadout taken = flyAndTake(orbit, turn, PlanetEnvironment.of(field, CLOSING_DAY_TICKS, 0d));
            if (unevenAlongTheEquator(taken)) {
                readout = taken;
            }
        }
        assertTrue("arrangement: no field among " + CLOSING_SEEDS + " seeds varies along the equator", readout != null);

        double held = 0d;
        double uniform = 0d;
        double byPhase = 0d;
        int revisited = 0;
        List<TerminalReadout.Row> rows = readout.rows();
        for (TerminalReadout.Row row : rows) {
            Optional<HeldModel.Expectation> expected = readout.expectationOf(row);
            if (!expected.isPresent()) {
                continue;
            }
            double value = row.gyPerS().getAsDouble();
            double residual = expected.get().residualOf(value);
            held += residual * residual;
            // The two models the held one is measured against, from the readings' own values: the uniform
            // field, and a map keyed by where on the ORBIT a reading was taken instead of where over the body.
            double all = 0d;
            int n = 0;
            double samePhase = 0d;
            int m = 0;
            boolean placeRevisited = false;
            for (TerminalReadout.Row other : rows) {
                if (other == row || !other.gyPerS().isPresent()) {
                    continue;
                }
                all += other.gyPerS().getAsDouble();
                n++;
                if (segmentOf(other) == segmentOf(row)) {
                    samePhase += other.gyPerS().getAsDouble();
                    m++;
                }
                placeRevisited |= other.region().equals(row.region());
            }
            double againstUniform = value - all / n;
            uniform += againstUniform * againstUniform;
            double againstPhase = m == 0 ? againstUniform : value - samePhase / m;
            byPhase += againstPhase * againstPhase;
            if (placeRevisited) {
                revisited++;
            }
        }
        String inCase = " (seed " + (seed - 1) + ", held " + held + ", by phase " + byPhase + ", uniform " + uniform + ")";
        assertTrue("arrangement: readings whose place another reading revisited: " + revisited + inCase,
                revisited >= TerminalReadout.CAPACITY / 2);
        assertTrue("INV-SAT-42: closing — the held readings expect no better than the uniform field" + inCase,
                held < uniform);
        assertTrue("INV-SAT-42: closing — the held readings expect no better than a map of the orbit's phases" + inCase,
                held < byPhase);
    }

    /** Which of the orbit's segments a row was read in. */
    private static int segmentOf(TerminalReadout.Row row) {
        return (int) Math.floor(row.phaseRad / (2d * Math.PI / SatelliteAccrual.SEGMENTS_PER_PERIOD));
    }

    /** Four orbits of a dosimeter's accrual on {@code orbit} in {@code field}, taken by one terminal. */
    private static TerminalReadout flyAndTake(SatelliteOrbit orbit, BodyRotation turn, PlanetEnvironment field) {
        // A battery no segment can empty and a panel that always covers the draw: every segment reads.
        double battery = 1e12d;
        List<SatelliteAccrual.Part> parts = new ArrayList<>();
        parts.add(new SatelliteAccrual.Part(0, SatelliteAccrual.PartKind.ELECTRONICS, true, 0d, 0, 0d));
        parts.add(new SatelliteAccrual.Part(1, SatelliteAccrual.PartKind.POWER_GENERATION, false,
                10d * SatelliteDosimeter.DRAW_PER_TICK, 0, 0d));
        SatelliteAccrual.State state = new SatelliteAccrual.State(0L, battery, battery, 0L, 0d, new FailureJournal(),
                new DoseReadings());
        SatelliteAccrual.Body body = new SatelliteAccrual.Body(orbit, Optional.of(field),
                tick -> Optional.of(new Vector3d(1d, 0d, 0d)), AstronomicalBodyHelper.EARTH_RADIUS_METRES);
        SatelliteAccrual.advance(body, parts, new SatelliteAccrual.Payload(false, true, 0d, SatelliteDosimeter.DRAW_PER_TICK),
                state, new SatelliteAccrual.Rules(false, 1d), 1L,
                orbit.epochTick() + CLOSING_ORBITS * orbit.periodTicks());
        TerminalReadout readout = new TerminalReadout();
        readout.take(1L, state.journal(), state.readings(), 0, 0, 0, 0, TerminalReadout.GroundTrack.under(orbit, turn));
        return readout;
    }

    /** Whether the readings' regions differ by a factor of two or more in their mean: a field that is not uniform. */
    private static boolean unevenAlongTheEquator(TerminalReadout readout) {
        java.util.Map<HeldModel.Region, double[]> sums = new java.util.HashMap<>();
        for (TerminalReadout.Row row : readout.rows()) {
            if (row.gyPerS().isPresent()) {
                double[] s = sums.computeIfAbsent(row.region(), r -> new double[2]);
                s[0] += row.gyPerS().getAsDouble();
                s[1]++;
            }
        }
        double low = Double.POSITIVE_INFINITY;
        double high = 0d;
        for (double[] s : sums.values()) {
            low = Math.min(low, s[0] / s[1]);
            high = Math.max(high, s[0] / s[1]);
        }
        return sums.size() > 1 && low > 0d && high >= 2d * low;
    }
}
