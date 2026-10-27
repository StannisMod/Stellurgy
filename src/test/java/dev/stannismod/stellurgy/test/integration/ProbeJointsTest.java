package dev.stannismod.stellurgy.test.integration;

import dev.stannismod.stellurgy.api.satellite.SatelliteOrbit;
import dev.stannismod.stellurgy.environment.BodyRotation;
import dev.stannismod.stellurgy.environment.MagnetosphereDerivation;
import dev.stannismod.stellurgy.environment.PlanetEnvironment;
import dev.stannismod.stellurgy.probe.FirstProbe;
import dev.stannismod.stellurgy.probe.ProbePack;
import dev.stannismod.stellurgy.probe.ProbeRecord;
import dev.stannismod.stellurgy.test.contract.Regions;
import dev.stannismod.stellurgy.tile.satellite.TerminalReadout;
import dev.stannismod.stellurgy.satellite.DoseReadings;
import dev.stannismod.stellurgy.satellite.SatelliteAccrual;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.joml.Vector3d;
import org.junit.Test;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.SplittableRandom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Where the first probe meets the environment (first-probe INV-PRB-02): the orbit {@link FirstProbe} draws is
 * the one its record was read along. Each reading is compared with what the body's environment, asked
 * directly, gives at the probe's point on that orbit at the reading's tick — so a record read along another
 * path, at another tick, or of another quantity than the trapped dose disagrees.
 */
public class ProbeJointsTest {

    private static final double EARTH_RADIUS_M = 6_378_137d;

    /** Bodies compared: enough that their drawn fields and orbits differ, each record a thousand readings. */
    private static final int SUBJECTS = 6;

    /**
     * How far one computation repeated may land from itself: {@code Math.exp} and {@code Math.pow} are
     * allowed an ulp of error and differ between interpreted and compiled code (their javadoc), so the same
     * dose rate asked twice can differ in its last few bits — measured 2026-10-26, 20 parts in 10¹⁷. A
     * trillionth is far above that, and a reading taken a single tick off its place is still told apart from
     * it (this method's red witness).
     */
    private static final double SAME_COMPUTATION = 1e-12d;

    @Test
    @Pins("INV-PRB-02")
    @RedWitness(member = "SatelliteAccrual#advance", at = "state.readings.record(mid, phase, rate);",
            replacedBy = "state.readings.record(mid + 1L, phase, rate);",
            fails = "INV-PRB-02: the reading is not the place's dose rate", on = "2026-10-26")
    public void theProbeReadsThePlaceItFlew() {
        SplittableRandom draw = new SplittableRandom(6302L);
        int compared = 0;
        for (int i = 0; i < SUBJECTS; i++) {
            double radiusEarths = 0.7d + draw.nextDouble() * 0.8d;
            double massEarths = radiusEarths * radiusEarths * radiusEarths;
            long rotationTicks = 18_000L + draw.nextInt(24_000);
            long seed = draw.nextLong();
            PlanetEnvironment env = PlanetEnvironment.of(
                    MagnetosphereDerivation.derive(massEarths, radiusEarths, rotationTicks, false, true, 1d, seed),
                    rotationTicks, 0d);
            double radiusM = radiusEarths * EARTH_RADIUS_M;
            SatelliteOrbit orbit = FirstProbe.orbit(seed, radiusM, SatelliteOrbit.EARTH_MU_M3_S2 * massEarths,
                    BodyRotation.of(rotationTicks, 0d));
            SatelliteAccrual.Body body = new SatelliteAccrual.Body(orbit, Optional.of(env),
                    tick -> Optional.of(new Vector3d(1d, 0d, 0d)), radiusM);

            ProbeRecord record = ProbeRecord.lived(body, BodyRotation.of(rotationTicks, 0d),
                    new SatelliteAccrual.Rules(true, 1d), 0L);

            for (DoseReadings.Reading reading : record.readings().readings()) {
                OptionalDouble expected = env.sample(orbit.positionAt(reading.tick), reading.tick).trappedDoseRate();
                String where = "subject " + i + " at tick " + reading.tick;
                assertEquals("INV-PRB-02: a reading where the place has none, or none where it has one — " + where,
                        expected.isPresent(), reading.gyPerS().isPresent());
                if (expected.isPresent()) {
                    assertEquals("INV-PRB-02: the reading is not the place's dose rate — " + where,
                            expected.getAsDouble(), reading.gyPerS().getAsDouble(),
                            SAME_COMPUTATION * expected.getAsDouble());
                    if (expected.getAsDouble() > 0d) {
                        compared++;
                    }
                }
            }
        }
        assertTrue("arrangement: only " + compared + " non-zero readings compared", compared >= SUBJECTS * 100);
    }

    /**
     * Where the probe's record meets a satellite terminal (first-probe INV-PRB-15): a terminal holding the
     * recorder with no pack shows exactly the core record's readings and journal; with a pack, every one of
     * those is still shown, beside the pack's own. The pack is a file's text, read by the production parser.
     */
    @Test
    @Pins("INV-PRB-15")
    @Regions(of = "INV-PRB-15", value = {"no pack", "pack"})
    @RedWitness(member = "TerminalReadout#holdRecord",
            at = "for (DoseReadings.Reading r : record.get().readings().readings()) {",
            replacedBy = "for (DoseReadings.Reading r : pack.readings().isEmpty() ? record.get().readings().readings() : java.util.Collections.<DoseReadings.Reading>emptyList()) {",
            fails = "INV-PRB-15: with a pack the rows are not the core's and the pack's", on = "2026-10-27")
    public void aPackOnlyAddsToTheRecord() {
        double radiusM = EARTH_RADIUS_M;
        PlanetEnvironment env = PlanetEnvironment.of(
                MagnetosphereDerivation.derive(1d, 1d, 23_934L, false, true, 1d, 6303L), 23_934d, 0d);
        BodyRotation turn = BodyRotation.of(23_934d, 0d);
        SatelliteOrbit orbit = FirstProbe.orbit(6303L, radiusM, SatelliteOrbit.EARTH_MU_M3_S2, turn);
        ProbeRecord record = ProbeRecord.lived(new SatelliteAccrual.Body(orbit, Optional.of(env),
                tick -> Optional.of(new Vector3d(1d, 0d, 0d)), radiusM), turn, new SatelliteAccrual.Rules(true, 1d), 0L);
        int core = record.readings().readings().size() + record.journal().entries().size();
        assertTrue("arrangement: the record holds " + core + " entries, too few to stand for a record", core > 100);

        TerminalReadout bare = new TerminalReadout();
        bare.holdRecord(Optional.of(record), ProbePack.NONE);
        assertEquals("INV-PRB-15: with no pack the rows are not the core record's", core, bare.rows().size());
        assertCoreShown("with no pack", record, bare);

        ProbePack pack = ProbePack.parse(new java.io.StringReader(
                "{\"notes\": [\"a note\"], \"readings\": [{\"tick\": -5000000, \"gyPerS\": 1e-4}, {\"tick\": -4000000, \"gyPerS\": 2e-4}]}"));
        TerminalReadout packed = new TerminalReadout();
        packed.holdRecord(Optional.of(record), pack);
        assertEquals("INV-PRB-15: with a pack the rows are not the core's and the pack's", core + 2, packed.rows().size());
        assertCoreShown("with a pack", record, packed);
    }

    private static void assertCoreShown(String region, ProbeRecord record, TerminalReadout readout) {
        java.util.List<TerminalReadout.Row> rows = readout.rows();
        for (DoseReadings.Reading reading : record.readings().readings()) {
            boolean shown = false;
            for (TerminalReadout.Row row : rows) {
                if (row.fromProbe && row.kind == TerminalReadout.Kind.DOSE_RATE && row.tick == reading.tick
                        && row.gyPerS().isPresent() == reading.gyPerS().isPresent()
                        && (!reading.gyPerS().isPresent() || row.gyPerS().getAsDouble() == reading.gyPerS().getAsDouble())) {
                    shown = true;
                    break;
                }
            }
            assertTrue("INV-PRB-15: " + region + " the core reading at tick " + reading.tick + " is not shown", shown);
        }
    }
}
