package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.world.TimeSkip;
import dev.stannismod.stellurgy.world.weather.StellurgyDimensionWorldInfo;
import org.junit.Test;

import java.util.Arrays;
import java.util.OptionalLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Issue #66 — pure-math unit pins for
 * {@link StellurgyDimensionWorldInfo#computeSleepWakeTime(long, int)}: the sleep wake-up
 * must land on the planet's dawn (a multiple of {@code rotationalPeriod}),
 * strictly forward, by at most one planetary day.
 */
public class SleepWakeTimeTest {

    private static void assertDawnInvariants(long current, int rp) {
        long wake = StellurgyDimensionWorldInfo.computeSleepWakeTime(current, rp);
        assertEquals("wake must land on a multiple of rotationalPeriod (dawn) for rp=" + rp
                + ", current=" + current, 0L, Math.floorMod(wake, (long) rp));
        assertTrue("wake must move strictly forward (current=" + current + ", wake=" + wake + ")",
                wake > current);
        assertTrue("wake must skip less than a full extra day (current=" + current
                + ", wake=" + wake + ", rp=" + rp + ")", wake - current <= rp);
    }

    /** Pins INV-WGEN-11 (computeSleepWakeTime always lands on a dawn and moves strictly forward). */
    @Pins("INV-WGEN-11")
    @Test
    public void rp24000MatchesVanillaRounding() {
        // With a 24000 day, the helper must reproduce vanilla's i - i%24000.
        for (long t : new long[]{0L, 1L, 12345L, 23999L, 24000L, 50000L}) {
            long vanilla = (t + 24000L) - (t + 24000L) % 24000L;
            assertEquals("rp=24000 must equal vanilla rounding at t=" + t,
                    vanilla, StellurgyDimensionWorldInfo.computeSleepWakeTime(t, 24000));
        }
    }

    /** Pins INV-WGEN-11 (computeSleepWakeTime always lands on a dawn and moves strictly forward). */
    @Pins("INV-WGEN-11")
    @Test
    public void nonVanillaPeriodsLandOnDawn() {
        for (int rp : new int[]{13888, 46875, 128000, 1, 7777}) {
            for (long t : new long[]{0L, 1L, 5000L, 99999L, 1_000_000L}) {
                assertDawnInvariants(t, rp);
            }
        }
    }

    /** Pins INV-WGEN-11 (computeSleepWakeTime always lands on a dawn and moves strictly forward). */
    @Pins("INV-WGEN-11")
    @RedWitness(member = "StellurgyDimensionWorldInfo#computeSleepWakeTime", at = "long next = current + rotationalPeriod;",
            replacedBy = "long next = current;", fails = "expected:<41664> but was:<27776>", on = "2026-10-19")
    @Test
    public void alreadyAtDawnSkipsToNextDay() {
        // current exactly on a dawn boundary -> advance a full day, not stay put.
        int rp = 13888;
        long wake = StellurgyDimensionWorldInfo.computeSleepWakeTime(2L * rp, rp);
        assertEquals(3L * rp, wake);
    }

    /** Pins INV-WGEN-11 (computeSleepWakeTime always lands on a dawn and moves strictly forward). */
    @Pins("INV-WGEN-11")
    @Test
    public void nonPositivePeriodFallsBackTo24000() {
        // Defensive: a bad rotationalPeriod must not divide-by-zero or loop.
        assertEquals(24000L, StellurgyDimensionWorldInfo.computeSleepWakeTime(0L, 0));
        assertEquals(24000L, StellurgyDimensionWorldInfo.computeSleepWakeTime(0L, -5));
    }

    /**
     * MECH-WGEN-32: a sleep ends the night only when its mode is met, and moves the universe to the earliest
     * dawn it takes. Three occupied worlds — dawns 5 000, 2 000 and none — completing in the first. Server-wide:
     * all three asleep and allowed → 2 000; one awake → nothing; one locked → nothing. Per world: the first alone
     * → 5 000, whoever else is awake. A completing world that is locked → nothing either way. A sleep taking only
     * a world with no dawn → the overworld's.
     */
    @Pins("MECH-WGEN-32")
    @RedWitness(verdict = "everyone", member = "TimeSkip#skipFor", at = "if (!part.allAsleep || !part.allowed) {",
            replacedBy = "if (false) {", fails = "MECH-WGEN-32: server-wide, one awake", on = "2026-10-24")
    @RedWitness(verdict = "earliest", member = "TimeSkip#skipFor",
            at = "earliest = Math.min(earliest, part.dawn.getAsLong());",
            replacedBy = "earliest = earliest == Long.MAX_VALUE ? part.dawn.getAsLong() : Math.max(earliest, part.dawn.getAsLong());",
            fails = "MECH-WGEN-32: server-wide, all asleep", on = "2026-10-24")
    @RedWitness(verdict = "per world", member = "TimeSkip#skipFor", at = "taking.add(own);",
            replacedBy = "taking.addAll(worlds);", fails = "MECH-WGEN-32: per world", on = "2026-10-24")
    @Test
    public void aSleepEndsTheNightOnlyWhenItsModeIsMet() {
        OptionalLong overworldDawn = OptionalLong.of(7_000L);
        TimeSkip.Sleepers first = new TimeSkip.Sleepers(true, true, OptionalLong.of(5_000L));
        TimeSkip.Sleepers second = new TimeSkip.Sleepers(true, true, OptionalLong.of(2_000L));
        TimeSkip.Sleepers noDawn = new TimeSkip.Sleepers(true, true, OptionalLong.empty());
        TimeSkip.Sleepers awake = new TimeSkip.Sleepers(false, true, OptionalLong.of(1_000L));
        TimeSkip.Sleepers locked = new TimeSkip.Sleepers(true, false, OptionalLong.of(1_000L));

        assertEquals("MECH-WGEN-32: server-wide, all asleep — the earliest dawn", OptionalLong.of(2_000L),
                TimeSkip.skipFor(true, Arrays.asList(first, second, noDawn), 0, overworldDawn));
        assertEquals("MECH-WGEN-32: server-wide, one awake — the night does not end", OptionalLong.empty(),
                TimeSkip.skipFor(true, Arrays.asList(first, awake, noDawn), 0, overworldDawn));
        assertEquals("MECH-WGEN-32: server-wide, one locked — the night does not end", OptionalLong.empty(),
                TimeSkip.skipFor(true, Arrays.asList(first, locked, noDawn), 0, overworldDawn));
        assertEquals("MECH-WGEN-32: per world — the completing world alone, to its dawn", OptionalLong.of(5_000L),
                TimeSkip.skipFor(false, Arrays.asList(first, awake, second), 0, overworldDawn));
        assertEquals("MECH-WGEN-32: a locked completing world ends no night", OptionalLong.empty(),
                TimeSkip.skipFor(false, Arrays.asList(locked, first), 0, overworldDawn));
        assertEquals("MECH-WGEN-32: a sleep taking no dawn moves to the overworld's", OptionalLong.of(7_000L),
                TimeSkip.skipFor(false, Arrays.asList(noDawn, first), 0, overworldDawn));
    }
}
