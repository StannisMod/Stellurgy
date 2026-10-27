package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.api.satellite.SatelliteOrbit;
import dev.stannismod.stellurgy.environment.BodyRotation;
import dev.stannismod.stellurgy.environment.MagnetosphereDerivation;
import dev.stannismod.stellurgy.environment.PlanetEnvironment;
import dev.stannismod.stellurgy.probe.EntryTrajectory;
import dev.stannismod.stellurgy.probe.FallPlacement;
import dev.stannismod.stellurgy.probe.FirstProbe;
import dev.stannismod.stellurgy.probe.ProbeRecord;
import dev.stannismod.stellurgy.probe.RadioReading;
import dev.stannismod.stellurgy.satellite.DoseReadings;
import dev.stannismod.stellurgy.satellite.FailureJournal;
import dev.stannismod.stellurgy.satellite.RadiationWear;
import dev.stannismod.stellurgy.satellite.SatelliteAccrual;
import dev.stannismod.stellurgy.space.DescentShell;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Regions;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3d;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The first probe's laws (first-probe INV-PRB-01, -03, -04, -11..-14): what it recorded before it died, how
 * it falls, when and where, and what the radio reads of its wreck.
 *
 * <p>Every subject is built through production — the probe's orbit by {@link FirstProbe#orbit}, its body's
 * field by {@link MagnetosphereDerivation#derive}, its entry by {@link FirstProbe#entry} — over bodies drawn
 * from the range a rocky planet spans. The star is held still along +x: an input of the experiment, which
 * only decides when the probe's panel is lit.</p>
 */
public class ProbeLawsTest {

    private static final double EARTH_RADIUS_M = 6_378_137d;
    private static final double EARTH_G_MS2 = 9.80665d;

    /**
     * The slack the two dose sums — the accrual's, carried part by part, and the environment's, summed along
     * the path — may differ by at the budget: the rounding of two sums of the same terms in a different
     * order, a few ulps of each of up to a few hundred thousand terms. A billionth of the budget is a
     * millionth of a millionth of a gray; a sample grid that differed by one segment would miss by a whole
     * segment's dose, orders of magnitude more.
     */
    private static final double DOSE_SLACK = 1e-9d;

    /**
     * Subjects per law. Seeded, so a red replays. At least {@link #MIN_PER_REGION} of every region must be
     * visited — counted at the subject, after the call — and the draws below land each region about a third
     * of the time, so 40 subjects visit each more than ten times in expectation.
     */
    private static final int SUBJECTS = 40;
    private static final int MIN_PER_REGION = 4;

    /**
     * INV-PRB-01. A probe that dies has its last reading in the segment it died in and none after, and its
     * journal ends with that one stop; one that outlives its record reads to the end and journals no stop.
     * Whether it should have died is decided by an oracle outside the accrual: the environment's own dose
     * sum along the same orbit, times the wear scale, against the electronics' budget.
     */
    @Test
    @Pins("INV-PRB-01")
    @RedWitness(verdict = "never stops", member = "ProbeRecord#lived", at = "if (!dosimeter.works(rules)) {",
            replacedBy = "if (false) {", fails = "INV-PRB-01: outlived its record though its dose", on = "2026-10-26")
    @RedWitness(verdict = "stops a segment late", member = "ProbeRecord#lived",
            at = "(state.segmentsDone() - 1L) * step", replacedBy = "state.segmentsDone() * step",
            fails = "INV-PRB-01: died a segment late", on = "2026-10-26")
    @Regions(of = "INV-PRB-01", value = {"dies in the belt", "outlives the record"})
    public void theRecordBreaksOffWhereTheDoseLawKillsIt() {
        SplittableRandom draw = new SplittableRandom(632L);
        int died = 0;
        int outlived = 0;
        for (int i = 0; i < SUBJECTS; i++) {
            double radiusEarths = 0.5d + draw.nextDouble() * 1.5d;
            double densityEarths = 0.8d + draw.nextDouble() * 0.5d;
            double massEarths = densityEarths * radiusEarths * radiusEarths * radiusEarths;
            long rotationTicks = 12_000L + draw.nextInt(36_000);
            long seed = draw.nextLong();
            PlanetEnvironment env = PlanetEnvironment.of(
                    MagnetosphereDerivation.derive(massEarths, radiusEarths, rotationTicks, false, true, 1d, seed),
                    rotationTicks, 0d);
            double radiusM = radiusEarths * EARTH_RADIUS_M;
            SatelliteOrbit orbit = FirstProbe.orbit(seed, radiusM, SatelliteOrbit.EARTH_MU_M3_S2 * massEarths,
                    BodyRotation.of(rotationTicks, 0d));
            // A third of the subjects with the wear off, the rest at a scale between the physical one and
            // ten thousand times it, so both a slow death and a fast one are drawn.
            boolean wear = draw.nextInt(3) != 0;
            double scale = Math.pow(10d, draw.nextDouble() * 4d);
            SatelliteAccrual.Rules rules = new SatelliteAccrual.Rules(wear, scale);
            SatelliteAccrual.Body body = new SatelliteAccrual.Body(orbit, Optional.of(env),
                    tick -> Optional.of(new Vector3d(1d, 0d, 0d)), radiusM);

            ProbeRecord record = ProbeRecord.lived(body, BodyRotation.of(rotationTicks, 0d), rules, 0L);

            String subject = "subject " + i + ": radius " + radiusEarths + " R, density " + densityEarths
                    + ", rotation " + rotationTicks + ", orbit r=" + orbit.radiusM() / radiusM + " R i="
                    + Math.toDegrees(orbit.inclinationRad()) + " deg, wear " + wear + " x" + scale + ", died "
                    + record.diedAt();
            long step = SatelliteAccrual.segmentTicks(orbit);
            double lifetimeDose = env.accruedDose(orbit, orbit.epochTick(), 0L) * scale;
            List<DoseReadings.Reading> readings = record.readings().readings();
            List<FailureJournal.Entry> journal = record.journal().entries();

            if (record.diedAt().isPresent()) {
                died++;
                long diedAt = record.diedAt().getAsLong();
                long start = diedAt - step / 2L;
                double before = env.accruedDose(orbit, orbit.epochTick(), start) * scale;
                double through = env.accruedDose(orbit, orbit.epochTick(), start + step) * scale;
                double budget = RadiationWear.ELECTRONICS_BUDGET_GY;
                assertTrue("INV-PRB-01: died with wear off — " + subject, wear);
                assertTrue("INV-PRB-01: died before its dose reached the budget (" + through + " Gy through the "
                        + "segment) — " + subject, through >= budget * (1d - DOSE_SLACK));
                assertTrue("INV-PRB-01: died a segment late, its dose was already " + before + " Gy before it — "
                        + subject, before < budget * (1d + DOSE_SLACK));
                assertTrue("INV-PRB-01: no reading at all — " + subject, !readings.isEmpty());
                assertEquals("INV-PRB-01: the last reading is not in the segment it died in — " + subject,
                        diedAt, readings.get(readings.size() - 1).tick);
                FailureJournal.Entry last = journal.get(journal.size() - 1);
                assertEquals("INV-PRB-01: the journal does not end with the stop — " + subject,
                        FailureJournal.Kind.PART_FAILED, last.kind);
                assertEquals("INV-PRB-01: the stop is not at the death — " + subject, diedAt, last.tick);
                int stops = 0;
                for (FailureJournal.Entry e : journal) {
                    if (e.kind == FailureJournal.Kind.PART_FAILED && e.slot == last.slot) {
                        stops++;
                    }
                }
                assertEquals("INV-PRB-01: the dosimeter stopped more than once — " + subject, 1, stops);
            } else {
                outlived++;
                assertTrue("INV-PRB-01: outlived its record though its dose (" + lifetimeDose + " Gy) passed the "
                        + "budget — " + subject, !wear || lifetimeDose < RadiationWear.ELECTRONICS_BUDGET_GY * (1d + DOSE_SLACK));
                for (FailureJournal.Entry e : journal) {
                    assertTrue("INV-PRB-01: a stop journalled by a probe that outlived its record — " + subject,
                            e.kind != FailureJournal.Kind.PART_FAILED);
                }
                assertTrue("INV-PRB-01: no reading at all — " + subject, !readings.isEmpty());
                long lastTick = readings.get(readings.size() - 1).tick;
                assertTrue("INV-PRB-01: stopped reading at " + lastTick + ", before the record's last segment — "
                        + subject, lastTick >= -(step + step / 2L) && lastTick < 0L);
            }
        }
        assertTrue("arrangement: only " + died + " subjects died", died >= MIN_PER_REGION);
        assertTrue("arrangement: only " + outlived + " subjects outlived the record", outlived >= MIN_PER_REGION);
    }

    /**
     * INV-PRB-03. The specific orbital energy v²/2 − μ/r is read off every tick of a fall and never rises past
     * the integration's stated error — a billionth of its magnitude ({@link EntryTrajectory}'s class
     * javadoc); with no air it stays at its start within that error. That error, held over the longest fall,
     * is a speed error of a few micrometres a second at orbital speed.
     */
    @Test
    @Pins("INV-PRB-03")
    @RedWitness(verdict = "air", member = "EntryTrajectory#derivative",
            at = "double drag = -rho * v / (2d * e.ballisticKgM2);",
            replacedBy = "double drag = -rho * v / (2d * e.ballisticKgM2) + 1e-3d / v;",
            fails = "INV-PRB-03: energy rose by", on = "2026-10-26")
    @RedWitness(verdict = "no air", member = "EntryTrajectory#derivative",
            at = "double gravity = -e.muM3S2 / (r * r * r);",
            replacedBy = "double gravity = -e.muM3S2 / (r * r * r) * 0.999d;",
            fails = "INV-PRB-03: with no air the energy moved by", on = "2026-10-26")
    @Regions(of = "INV-PRB-03", value = {"air", "no air"})
    public void dragOnlyTakesEnergy() {
        SplittableRandom draw = new SplittableRandom(6320L);
        int withAir = 0;
        int withoutAir = 0;
        for (int i = 0; i < SUBJECTS; i++) {
            EntryTrajectory.Entry entry = drawnEntry(draw);
            EntryTrajectory fall = EntryTrajectory.fly(entry);
            double mu = entry.muM3S2();
            double e0 = energy(fall, 0, entry);
            double tolerance = 1e-9d * Math.abs(e0);
            String subject = "subject " + i + ": " + describe(entry) + ", " + fall.ticks() + " ticks";
            boolean airless = entry.surfaceDensityKgM3() == 0d;
            if (airless) {
                withoutAir++;
            } else {
                withAir++;
            }
            double previous = e0;
            for (int t = 1; t <= fall.ticks(); t++) {
                double e = energy(fall, t, entry);
                if (e > previous + tolerance) {
                    fail("INV-PRB-03: energy rose by " + (e - previous) + " J/kg at tick " + t + " (mu " + mu
                            + ") — " + subject);
                }
                if (airless && Math.abs(e - e0) > tolerance) {
                    fail("INV-PRB-03: with no air the energy moved by " + (e - e0) + " J/kg by tick " + t + " — "
                            + subject);
                }
                previous = e;
            }
            if (!airless) {
                assertTrue("INV-PRB-03: the air took nothing — " + subject, previous < e0 - tolerance);
            }
        }
        assertTrue("arrangement: only " + withAir + " falls through air", withAir >= MIN_PER_REGION);
        assertTrue("arrangement: only " + withoutAir + " falls with no air", withoutAir >= MIN_PER_REGION);
    }

    /**
     * INV-PRB-04. Every entry the probe is given lands, its last sample on the ground. Where the probe falls
     * in near-equilibrium it lands at its terminal speed √(2βg/ρ₀) and above it by no more than twice the
     * first-order lag: an equilibrium speed v_t·e^{h/2H} falls as the body descends at v, at the rate
     * v_t²/(2H), and a body lagging it by a fraction ε loses speed at 2gε, so ε ≈ v_t²/(4gH). Near-equilibrium
     * is where that fraction is under five per cent ("thick air"); elsewhere only the landing is claimed.
     */
    @Test
    @Pins("INV-PRB-04")
    @RedWitness(verdict = "lands", member = "FirstProbe#entry",
            at = "Math.min(1d, 2d * orbitLineAltitudeM / r0)", replacedBy = "Math.min(1d, 0d * orbitLineAltitudeM / r0)",
            fails = "INV-PRB-04: did not land", on = "2026-10-26")
    @RedWitness(verdict = "terminal speed", member = "EntryTrajectory#derivative",
            at = "double drag = -rho * v / (2d * e.ballisticKgM2);",
            replacedBy = "double drag = -rho * v / (3d * e.ballisticKgM2);",
            fails = "INV-PRB-04: landed at", on = "2026-10-26")
    @Regions(of = "INV-PRB-04", value = {"thick air", "thin air", "no air"})
    public void itReachesTheGroundAtItsTerminalSpeed() {
        SplittableRandom draw = new SplittableRandom(63200L);
        int thick = 0;
        int thin = 0;
        int none = 0;
        for (int i = 0; i < SUBJECTS; i++) {
            EntryTrajectory.Entry entry = drawnEntry(draw);
            EntryTrajectory fall;
            String subject = "subject " + i + ": " + describe(entry);
            try {
                fall = EntryTrajectory.fly(entry);
            } catch (IllegalStateException refused) {
                throw new AssertionError("INV-PRB-04: did not land — " + subject, refused);
            }
            assertEquals("INV-PRB-04: the last sample is not on the ground — " + subject,
                    0d, fall.altitudeAt(fall.ticks()), 0d);
            if (entry.surfaceDensityKgM3() == 0d) {
                none++;
                continue;
            }
            double g = entry.surfaceGravityMS2();
            double terminal = Math.sqrt(2d * entry.ballisticKgM2() * g / entry.surfaceDensityKgM3());
            double lag = terminal * terminal / (4d * g * entry.scaleHeightM());
            if (lag >= 0.05d) {
                thin++;
                continue;
            }
            thick++;
            double ratio = fall.speedAt(fall.ticks()) / terminal;
            assertTrue("INV-PRB-04: landed at " + ratio + " of its terminal speed " + terminal + " m/s, expected 1 to "
                    + (1d + 2d * lag) + " — " + subject, ratio >= 1d && ratio <= 1d + 2d * lag);
        }
        assertTrue("arrangement: only " + thick + " falls in thick air", thick >= MIN_PER_REGION);
        assertTrue("arrangement: only " + thin + " falls in thin air", thin >= MIN_PER_REGION);
        assertTrue("arrangement: only " + none + " falls with no air", none >= MIN_PER_REGION);
    }

    /**
     * INV-PRB-11. The fall is due exactly when somebody has logged in, the delay has run, and the sun is more
     * than six degrees down. The sun's elevation is computed here from the world's celestial angle as vanilla
     * defines it — 0 at noon, 0.25 at sunset, 0.5 at midnight — so 90° − 360°·min(a, 1 − a). Each region is
     * drawn with its edges: the delay one tick short and exactly run, the sun at the six-degree line and just
     * past it either way.
     */
    @Test
    @Pins("INV-PRB-11")
    @Regions(of = "INV-PRB-11", value = {"nobody logged in", "before the delay", "day", "twilight", "night"})
    @RedWitness(verdict = "twilight", member = "FirstProbe#isNight", at = "a > 0.25d + NIGHT_DEPTH_TURNS",
            replacedBy = "a > 0.25d", fails = "INV-PRB-11:", on = "2026-10-27")
    @RedWitness(verdict = "delay", member = "FirstProbe#due", at = "now - firstLoginTick >= delayTicks",
            replacedBy = "now - firstLoginTick >= 0L", fails = "INV-PRB-11:", on = "2026-10-27")
    public void itFallsAtNightAfterTheDelay() {
        SplittableRandom draw = new SplittableRandom(63211L);
        int[] visited = new int[5];
        for (int i = 0; i < 20_000; i++) {
            long delay = draw.nextInt(100_000);
            long first = draw.nextInt(10) == 0 ? -1L : draw.nextInt(1_000_000);
            long now = first < 0L ? draw.nextInt(2_000_000) : first + delay + (draw.nextInt(3) - 1) * (draw.nextBoolean() ? 1L : draw.nextInt(50_000));
            float angle;
            switch (draw.nextInt(3)) {
                case 0:
                    // On either side of a six-degree line, by about a hundredth of a degree.
                    double edge = (draw.nextBoolean() ? 0.25d : 0.75d) + (draw.nextBoolean() ? 1d : -1d) * 6d / 360d;
                    angle = (float) (edge + (draw.nextDouble() - 0.5d) * 6e-5d);
                    break;
                default:
                    angle = (float) draw.nextDouble();
                    break;
            }
            double a = angle - Math.floor(angle);
            double elevation = 90d - 360d * Math.min(a, 1d - a);
            boolean logged = first >= 0L;
            boolean passed = logged && now - first >= delay;
            boolean expected = logged && passed && elevation < -6d;
            if (!logged) {
                visited[0]++;
            } else if (!passed) {
                visited[1]++;
            } else if (elevation > 0d) {
                visited[2]++;
            } else if (elevation >= -6d) {
                visited[3]++;
            } else {
                visited[4]++;
            }
            if (Math.abs(elevation + 6d) < 1e-3d) {
                continue; // the line itself: a float's last bit decides it, and either answer is the line
            }
            assertEquals("INV-PRB-11: first login " + first + ", now " + now + ", delay " + delay + ", sun at "
                    + elevation + " deg", expected, FirstProbe.due(first, now, delay, angle));
        }
        String[] names = {"nobody logged in", "before the delay", "day", "twilight", "night"};
        for (int r = 0; r < names.length; r++) {
            assertTrue("arrangement: region `" + names[r] + "` visited " + visited[r] + " times", visited[r] >= 100);
        }
    }

    /**
     * INV-PRB-12. Every offset lies in the band between 16 and 64 chunks (256 and 1 024 blocks), and it is spread
     * over the band's AREA: half of all draws fall inside the radius that halves that area, √((r₁² + r₂²)/2),
     * and the directions average to nothing. Over twenty thousand draws a fair half lands within 0.014 of 0.5
     * (four standard errors, √(0.25/n)); a draw even in radius instead puts 0.59 inside.
     */
    @Test
    @Pins("INV-PRB-12")
    @RedWitness(member = "FirstProbe#landingOffset", at = "Math.sqrt(inner + draw.nextDouble() * (outer - inner))",
            replacedBy = "Math.sqrt(inner) + draw.nextDouble() * (Math.sqrt(outer) - Math.sqrt(inner))",
            fails = "INV-PRB-12: not spread over the band's area", on = "2026-10-27")
    public void itLandsInTheBand() {
        SplittableRandom draw = new SplittableRandom(63212L);
        int n = 20_000;
        double inner = 16d * 16d;
        double outer = 64d * 16d;
        double halving = Math.sqrt((inner * inner + outer * outer) / 2d);
        int inside = 0;
        double sumCos = 0d;
        double sumSin = 0d;
        for (int i = 0; i < n; i++) {
            double[] offset = FirstProbe.landingOffset(draw);
            double r = Math.hypot(offset[0], offset[1]);
            assertTrue("INV-PRB-12: drawn at " + r + " blocks, outside the band", r >= inner - 1e-9d && r <= outer + 1e-9d);
            if (r < halving) {
                inside++;
            }
            sumCos += offset[0] / r;
            sumSin += offset[1] / r;
        }
        double fraction = (double) inside / n;
        double se = Math.sqrt(0.25d / n);
        assertEquals("INV-PRB-12: not spread over the band's area — " + fraction + " inside the halving radius",
                0.5d, fraction, 4d * se);
        double directionBound = 4d * Math.sqrt(0.5d / n);
        assertEquals("INV-PRB-12: directions lean east or west", 0d, sumCos / n, directionBound);
        assertEquals("INV-PRB-12: directions lean north or south", 0d, sumSin / n, directionBound);
    }

    /**
     * INV-PRB-13. Laid over a flat world, the fall stands on the landing point's ground at its last tick, its
     * whole downrange distance back along its heading at the entry height at its first, and on that heading's
     * line between. The tolerances are a metre's billionth at a thousand kilometres: the arithmetic of one
     * subtraction and one product, which is all the laying does.
     */
    @Test
    @Pins("INV-PRB-13")
    @RedWitness(member = "FallPlacement#at", at = "double back = path.landingDownrange() - down;",
            replacedBy = "double back = down;", fails = "INV-PRB-13: not at its landing", on = "2026-10-27")
    public void theFallEndsWhereItLands() {
        SplittableRandom draw = new SplittableRandom(63213L);
        for (int i = 0; i < SUBJECTS / 4; i++) {
            EntryTrajectory fall = EntryTrajectory.fly(drawnEntry(draw));
            double landX = (draw.nextDouble() - 0.5d) * 6e6d;
            double groundY = 40d + draw.nextDouble() * 160d;
            double landZ = (draw.nextDouble() - 0.5d) * 6e6d;
            double heading = draw.nextDouble() * 2d * Math.PI;
            FallPlacement laid = new FallPlacement(landX, groundY, landZ, heading, fall);
            String subject = "subject " + i + ": " + fall.ticks() + " ticks, heading " + heading;
            double tol = 1e-9d * fall.landingDownrange() + 1e-6d;

            Vec3d end = laid.at(fall.ticks());
            assertEquals("INV-PRB-13: not at its landing (x) — " + subject, landX, end.x, tol);
            assertEquals("INV-PRB-13: not at its landing (y) — " + subject, groundY, end.y, tol);
            assertEquals("INV-PRB-13: not at its landing (z) — " + subject, landZ, end.z, tol);

            Vec3d start = laid.at(0d);
            assertEquals("INV-PRB-13: does not start at the entry height — " + subject,
                    groundY + fall.altitudeAt(0), start.y, tol);
            assertEquals("INV-PRB-13: does not start its downrange distance back — " + subject,
                    fall.landingDownrange(), Math.hypot(landX - start.x, landZ - start.z), tol);
            for (int k = 1; k < 8; k++) {
                Vec3d p = laid.at(fall.ticks() * k / 8d);
                double across = (p.x - landX) * Math.sin(heading) - (p.z - landZ) * Math.cos(heading);
                double along = (landX - p.x) * Math.cos(heading) + (landZ - p.z) * Math.sin(heading);
                assertEquals("INV-PRB-13: off its heading's line at " + k + "/8 — " + subject, 0d, across, tol);
                assertTrue("INV-PRB-13: behind its landing at " + k + "/8 — " + subject, along >= -tol);
            }
        }
    }

    /**
     * INV-PRB-14. With nothing to hear the radio reads nothing. With transmitters, its bearing is the direction
     * to the nearest — found here by its own distance comparison — and moving the receiver farther from that
     * one, along the same line, lowers the strength. Nothing it reads is a distance: its type declares no
     * member named for one.
     */
    @Test
    @Pins("INV-PRB-14")
    @Regions(of = "INV-PRB-14", value = {"none", "one", "several"})
    @RedWitness(member = "RadioReading#at", at = "if (d2 < best) {", replacedBy = "if (d2 > best || strongest == null) {",
            fails = "INV-PRB-14: the bearing is not toward the nearest", on = "2026-10-27")
    public void theRadioReadsABearingAndAStrength() {
        assertTrue("INV-PRB-14: a reading with nothing to hear",
                !RadioReading.at(0d, 64d, 0d, java.util.Collections.<BlockPos>emptyList()).isPresent());
        SplittableRandom draw = new SplittableRandom(63214L);
        int one = 0;
        int several = 0;
        for (int i = 0; i < 2_000; i++) {
            int count = draw.nextInt(4) == 0 ? 1 : 2 + draw.nextInt(6);
            List<BlockPos> transmitters = new java.util.ArrayList<>();
            for (int k = 0; k < count; k++) {
                transmitters.add(new BlockPos(draw.nextInt(200_001) - 100_000, 40 + draw.nextInt(100),
                        draw.nextInt(200_001) - 100_000));
            }
            double x = (draw.nextDouble() - 0.5d) * 2e5d;
            double y = 60d + draw.nextDouble() * 40d;
            double z = (draw.nextDouble() - 0.5d) * 2e5d;
            BlockPos nearest = null;
            double best = Double.POSITIVE_INFINITY;
            for (BlockPos t : transmitters) {
                double d = Math.sqrt(sq(t.getX() + 0.5d - x) + sq(t.getY() + 0.5d - y) + sq(t.getZ() + 0.5d - z));
                if (d < best) {
                    best = d;
                    nearest = t;
                }
            }
            if (count == 1) {
                one++;
            } else {
                several++;
            }
            RadioReading reading = RadioReading.at(x, y, z, transmitters).get();
            double expected = Math.atan2(nearest.getZ() + 0.5d - z, nearest.getX() + 0.5d - x);
            assertEquals("INV-PRB-14: the bearing is not toward the nearest of " + count + " — receiver at " + x + ","
                    + z, 0d, Math.IEEEremainder(reading.bearingRad - expected, 2d * Math.PI), 1e-9d);
            if (count == 1) {
                // Farther along the line away from it: the same bearing, a weaker signal.
                double back = 10d + draw.nextDouble() * 1e5d;
                double fx = x - Math.cos(expected) * back;
                double fz = z - Math.sin(expected) * back;
                RadioReading farther = RadioReading.at(fx, y, fz, transmitters).get();
                assertTrue("INV-PRB-14: " + back + " blocks farther the signal is " + farther.strengthDbm
                        + " dBm against " + reading.strengthDbm, farther.strengthDbm < reading.strengthDbm);
            }
        }
        for (java.lang.reflect.Field field : RadioReading.class.getFields()) {
            assertTrue("INV-PRB-14: a reading carries " + field.getName(),
                    !field.getName().toLowerCase(java.util.Locale.ROOT).contains("distance"));
        }
        for (java.lang.reflect.Method method : RadioReading.class.getMethods()) {
            assertTrue("INV-PRB-14: a reading answers " + method.getName(),
                    !method.getName().toLowerCase(java.util.Locale.ROOT).contains("distance"));
        }
        assertTrue("arrangement: one transmitter " + one + " times", one >= 100);
        assertTrue("arrangement: several transmitters " + several + " times", several >= 100);
    }

    private static double sq(double v) {
        return v * v;
    }

    /**
     * An entry as the probe is given one, about a rocky body drawn from the range rocky planets span: radius
     * 0.1 to 2 Earth radii at 0.6 to 1.4 Earth's density (so surface gravity scales with both), air of none, of
     * a trace (0.1 to 5 % of an atmosphere) or of 5 to 300 %, a third each; its atmosphere line where the body's
     * radius puts it, or — as a planet file may state one — anywhere from 20 to 300 km.
     */
    private static EntryTrajectory.Entry drawnEntry(SplittableRandom draw) {
        double radiusEarths = 0.1d + draw.nextDouble() * 1.9d;
        double densityEarths = 0.6d + draw.nextDouble() * 0.8d;
        double radiusM = radiusEarths * EARTH_RADIUS_M;
        double g = EARTH_G_MS2 * densityEarths * radiusEarths;
        int air = draw.nextInt(3);
        double percent = air == 0 ? 0d : air == 1 ? 0.1d + draw.nextDouble() * 4.9d : 5d + draw.nextDouble() * 295d;
        double line = draw.nextBoolean()
                ? DescentShell.orbitLineWorldY(radiusEarths)
                : 20_000d + draw.nextDouble() * 280_000d;
        return FirstProbe.entry(radiusM, g, percent, line);
    }

    private static double energy(EntryTrajectory fall, int tick, EntryTrajectory.Entry entry) {
        double v = fall.speedAt(tick);
        return v * v / 2d - entry.muM3S2() / (entry.bodyRadiusM() + fall.altitudeAt(tick));
    }

    private static String describe(EntryTrajectory.Entry e) {
        return "radius " + e.bodyRadiusM() + " m, g " + e.surfaceGravityMS2() + ", air " + e.surfaceDensityKgM3()
                + " kg/m3, scale height " + e.scaleHeightM() + " m, entry " + e.startAltitudeM() + " m at "
                + e.startSpeedMS() + " m/s";
    }
}
