package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.atmosphere.AirState;
import dev.stannismod.stellurgy.mission.GasHarvest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A gas-collection mission is drawn at the rate the chosen gas presses on the intake: the same harvest
 * from a gas twice as dense takes half as long, and a trace gas is harvestable, only slowly.
 *
 * <p>Every case collects one full 64-bucket harvest into a 64-bucket tank with intake power 10, so the
 * only thing that differs between them is the partial pressure.</p>
 */
public class GasHarvestTest {

    private static final long HARVEST_MB = 64_000L;
    private static final int TANK_MB = 64_000;
    private static final int INTAKE = 10;

    private static long secondsAt(long partialPressure) {
        return GasHarvest.missionSeconds(HARVEST_MB, TANK_MB, INTAKE, partialPressure);
    }

    /** red-witnessed: with {@code GasHarvest#missionSeconds} at
     *  {@code * ((double) partialPressure / (double) AirState.ONE_ATM)} removed from the rate, both
     *  pressures timed 256 s and the twice-as-dense gas was not faster. */
    @Test
    public void aGasTwiceAsDenseFillsTheSameTanksInHalfTheTime() {
        long atOne = secondsAt(AirState.ONE_ATM);
        long atTwo = secondsAt(2L * AirState.ONE_ATM);
        assertTrue("the harvest must take time at all: " + atOne + " s at one atmosphere", atOne > 1L);
        assertEquals("at one atmosphere " + atOne + " s, so at two it is half that", atOne, 2L * atTwo);
    }

    /** red-witnessed: with {@code GasHarvest#missionSeconds} at
     *  {@code * ((double) partialPressure / (double) AirState.ONE_ATM)} removed from the rate, a
     *  part-per-million gas timed 256 s, the same as a whole atmosphere of it. */
    @Test
    public void aTraceGasIsHarvestableAtTheRateItsPressureGives() {
        long atOne = secondsAt(AirState.ONE_ATM);
        long atOnePpm = secondsAt(AirState.ONE_ATM / 1_000_000L);
        assertTrue("a part per million is a million times slower than a whole atmosphere (" + atOne
                        + " s there), within a second of rounding: " + atOnePpm + " s",
                Math.abs(atOnePpm - 1_000_000L * atOne) <= 1L);
    }

    /** red-witnessed: with {@code GasHarvest#missionSeconds} at {@code if (partialPressure <= 0L)}
     *  narrowed to {@code < 0L}, a gas at zero pressure was timed instead of refused. */
    @Test
    public void aGasAbsentFromTheAirIsRefused() {
        try {
            long seconds = secondsAt(0L);
            fail("a gas at zero partial pressure was timed at " + seconds + " s instead of refused");
        } catch (IllegalArgumentException expected) {
            // the refusal is the contract
        }
    }
}
