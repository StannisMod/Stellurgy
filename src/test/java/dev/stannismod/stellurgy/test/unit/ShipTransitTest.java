package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.space.ShipTransit;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.Test;

import java.math.BigInteger;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Contract tests for the automatic transit integrator. A flight is a SCALAR — origin name, target
 * name, blocks flown against the distance priced at departure, never a raw absolute whose meaning
 * would depend on the tick it was written at — so what has to hold is that it converges in the tick
 * count the price implies, never overshoots, never stalls, and never moves backwards.
 */
public class ShipTransitTest {

    private static GalacticCoord at(long ax, long ay, long az) {
        return GalacticCoord.ofAbsolute(ax, ay, az);
    }

    private static ShipTransit flight(long distanceBlocks) {
        return new ShipTransit(at(0, 0, 0), GalacticCoord.ofSectorLocal(3, -2, 5, 0, 0, 0),
                distanceBlocks);
    }

    @Test
    public void aFlightArrivesInExactTickCount() {
        ShipTransit t = flight(1000L);
        int ticks = 0;
        while (!t.arrived() && ticks < 1000) {
            t = t.advance(100); // 1000 / 100 = 10 ticks
            ticks++;
        }
        assertTrue(t.arrived());
        assertEquals(10, ticks);
        assertEquals(0.0, t.remainingDistance(), 0.0);
    }

    @Test
    public void arrivalNeverOvershootsTheDistanceItWasPriced() {
        // A speed that does not divide the distance must still finish exactly at the price.
        ShipTransit t = flight(1000L);
        for (int i = 0; i < 100 && !t.arrived(); i++) {
            t = t.advance(300); // 300, 600, 900, then the final partial step
        }
        assertTrue(t.arrived());
        assertEquals(1000L, t.travelledBlocks());
        assertEquals(0.0, t.remainingDistance(), 0.0);
        assertEquals(1.0, t.progress(), 1e-9);
    }

    @Test
    public void progressIsMonotonic() {
        ShipTransit t = flight(9_000L);
        double prev = t.remainingDistance();
        for (int i = 0; i < 200 && !t.arrived(); i++) {
            t = t.advance(137); // odd speed
            double now = t.remainingDistance();
            assertTrue("distance must not increase", now <= prev + 1e-6);
            prev = now;
        }
        assertTrue(t.arrived());
    }

    @Test
    public void speedExceedingDistanceArrivesNextTick() {
        ShipTransit t = flight(50L).advance(1000);
        assertTrue(t.arrived());
        assertEquals(50L, t.travelledBlocks());
    }

    @Test
    public void aZeroLengthFlightIsAlreadyArrivedAndStable() {
        ShipTransit t = new ShipTransit(at(42, 42, 42), at(42, 42, 42), 0L);
        assertTrue(t.arrived());
        ShipTransit next = t.advance(10);
        assertEquals(t.travelledBlocks(), next.travelledBlocks());
        assertTrue(next.arrived());
    }

    @Test
    public void nonPositiveSpeedDoesNotStallForeverButFinishes() {
        // A zero/negative speed is degenerate; the integrator resolves it by finishing rather than
        // looping forever (callers gate on ship power before starting a transit).
        assertTrue(flight(100L).advance(0).arrived());
        assertTrue(flight(100L).advance(-5).arrived());
    }

    /** Pins ADDR-12 (a mid-transit position is stored as origin name, target name and progress). */
    @Pins("ADDR-12")
    @Test
    public void bothEndsOfTheFlightSurviveIt() {
        GalacticCoord origin = GalacticCoord.ofSectorLocal(1, 2, 3, 400, 0, 0);
        GalacticCoord target = GalacticCoord.ofSectorLocal(3, -2, 5, 123, -456, 789);
        ShipTransit t = new ShipTransit(origin, target, 4_000L).advance(1_000);
        assertFalse(t.arrived());
        assertEquals(0.25, t.progress(), 1e-9);
    }

    /** Cases of the skip sweep; each region below is visited at least {@link #MIN_PER_REGION} times. */
    private static final int SKIP_CASES = 4_000;
    /** Six regions drawn uniformly land ~650 times each in {@code SKIP_CASES}; this is under a quarter of that. */
    private static final int MIN_PER_REGION = 150;
    /** Iterated advance is the second oracle only where it is cheap to run: flights this many ticks long. */
    private static final long MAX_ITERATED_TICKS = 3_000L;

    /**
     * Pins INV-SPC-21: a skip of Δ ticks flies a flight what Δ ticks of the per-tick advance fly it — the
     * blocks flown grow by speed·Δ and never past the priced distance (C16 CLOCK-7). Two oracles: the
     * clause's closed form, min(distance, travelled + speed·Δ), computed exactly in {@link BigInteger}; and,
     * on flights short enough to run, Δ calls of {@code advance}. Regions, counted from the state the
     * subject saw: Δ = 0; Δ = 1; Δ one tick short of arrival (the last quantum); Δ exactly the ticks to
     * arrive; Δ past them; Δ so large that speed·Δ overflows a long. The degenerate subjects — an arrived
     * flight, a zero-length one — are drawn as extra cases inside the sweep.
     */
    @Pins("INV-SPC-21")
    @RedWitness(member = "ShipTransit#carry", at = "travelledBlocks + speedBlocksPerTick * ticks",
            replacedBy = "travelledBlocks + speedBlocksPerTick * (ticks - 1L)",
            fails = "the blocks flown", on = "2026-10-25")
    @RedWitness(member = "ShipTransit#carry", at = "ticks >= ticksToArrive", replacedBy = "false",
            fails = "the blocks flown", on = "2026-10-25")
    @Test
    public void aSkipFliesWhatItsTicksWouldFly() {
        Random draw = new Random(0x5C1BL);
        int[] visits = new int[6];
        int iterated = 0;
        for (int i = 0; i < SKIP_CASES; i++) {
            boolean small = draw.nextBoolean();
            long distance = i % 97 == 0 ? 0L : small ? draw.nextInt(20_000) : (long) (draw.nextDouble() * 1e12);
            long travelled = i % 50 == 0 ? distance : (long) (draw.nextDouble() * distance);
            long speed = 1L + (small ? draw.nextInt(50) : (long) (draw.nextDouble() * 1e6));
            long remaining = distance - travelled;
            long toArrive = remaining == 0L ? 0L : (remaining + speed - 1L) / speed;
            int region = draw.nextInt(6);
            long skip;
            switch (region) {
                case 0: skip = 0L; break;
                case 1: skip = 1L; break;
                case 2: skip = Math.max(0L, toArrive - 1L); break;
                case 3: skip = toArrive; break;
                case 4: skip = toArrive + 1L + draw.nextInt(1_000); break;
                default: skip = Long.MAX_VALUE - draw.nextInt(1_000); break;
            }
            ShipTransit before = new ShipTransit(at(0, 0, 0), at(1, 0, 0), distance, travelled);
            String inCase = " — INV-SPC-21: distance " + distance + ", travelled " + travelled + ", speed "
                    + speed + ", skip " + skip;

            ShipTransit after = before.carry(speed, skip);

            BigInteger flown = BigInteger.valueOf(speed).multiply(BigInteger.valueOf(skip));
            long expected = flown.add(BigInteger.valueOf(travelled)).min(BigInteger.valueOf(distance))
                    .longValueExact();
            assertEquals("the blocks flown" + inCase, expected, after.travelledBlocks());
            assertEquals("the priced distance is the flight's, a skip does not reprice it" + inCase,
                    distance, after.distanceBlocks());
            if (skip <= MAX_ITERATED_TICKS) {
                ShipTransit ticked = before;
                for (long k = 0; k < skip; k++) {
                    ticked = ticked.advance(speed);
                }
                assertEquals("a skip flies what its ticks fly" + inCase, ticked.travelledBlocks(),
                        after.travelledBlocks());
                assertEquals("a skip arrives when its ticks arrive" + inCase, ticked.arrived(), after.arrived());
                iterated++;
            }
            boolean overflows = flown.bitLength() > 63;
            if (remaining > 0L && (region != 2 || toArrive > 1L) && (region != 5 || overflows)) {
                visits[region]++;
            }
        }
        for (int r = 0; r < visits.length; r++) {
            assertTrue("arrangement: skip region " + r + " was visited " + visits[r] + " times",
                    visits[r] >= MIN_PER_REGION);
        }
        assertTrue("arrangement: the per-tick oracle ran " + iterated + " times", iterated >= SKIP_CASES / 4);
    }

    /**
     * A flight with a speed of zero or less is resolved as {@code advance} resolves it — it finishes rather
     * than stalling — and a skip agrees: INV-SPC-21 binds the skip to the per-tick advance, not to the
     * closed form, which would read "flies nothing" here.
     */
    @Pins("INV-SPC-21")
    @RedWitness(member = "ShipTransit#carry", at = "return advance(speedBlocksPerTick);", replacedBy = "return this;",
            fails = "INV-SPC-21", on = "2026-10-25")
    @Test
    public void aSkipOfAFlightWithNoSpeedEndsItAsItsTicksWould() {
        for (long speed : new long[] {0L, -5L}) {
            ShipTransit before = flight(100L);
            assertEquals("INV-SPC-21: speed " + speed, before.advance(speed).arrived(),
                    before.carry(speed, 10L).arrived());
        }
    }

    @Test
    public void aLongFlightNeverStalls() {
        ShipTransit t = flight(1_000_000L);
        long before = t.travelledBlocks();
        t = t.advance(10);
        assertTrue("a flight must advance every tick while in progress", t.travelledBlocks() > before);
    }
}
