package dev.stannismod.stellurgy.mission;

import dev.stannismod.stellurgy.atmosphere.AirState;

/**
 * How long a gas-collection mission takes to bring back what it planned to.
 * <p>
 * <b>The intake draws a gas as fast as that gas presses on it.</b> The rate is the rocket's intake
 * power times the partial pressure of the chosen gas, in atmospheres, so a gas twice as dense in the
 * air fills the same tanks in half the time. That is the whole depth mechanic of a giant, with no
 * table of depths anywhere: where the air is denser, the cut is richer.
 * <p>
 * A trace gas is not refused. It is harvestable at the rate its pressure gives, and a gas at a
 * millionth of an atmosphere takes a million times longer than the same gas at one; whether that is
 * worth a rocket is the player's call.
 */
public final class GasHarvest {

    /** The tank size the timing curve is normalised to: 64 buckets, in mB. */
    private static final long BASE_CAPACITY_MB = 64_000L;

    /** How gently a bigger tank lengthens the mission: sublinear, so size still pays. */
    private static final double CAPACITY_EXPONENT = 0.2D;

    /** mB per second that one unit of intake power draws from a gas at one atmosphere. */
    private static final long MB_PER_SECOND_PER_INTAKE_AT_ONE_ATM = 25L;

    /** What a mission with no intake or nothing to collect is given, in seconds. */
    private static final long UNPLANNABLE_SECONDS = 180L;

    private GasHarvest() {
    }

    /**
     * The mission's length in seconds: the shorter of the timing curve for this tank and the time to
     * collect what was planned, both at the rate this gas's partial pressure allows. Never zero.
     *
     * @param plannedMb          what the mission will bring back, in mB
     * @param capacityForTiming  the tank size the curve is read at, in mB
     * @param intakePower        the rocket's intake power
     * @param partialPressure    the chosen gas's partial pressure, in {@link AirState#ONE_ATM} units;
     *                           must be positive, since a gas that is not in the air is not offered
     */
    public static long missionSeconds(long plannedMb, int capacityForTiming, int intakePower,
                                      long partialPressure) {
        if (partialPressure <= 0L) {
            throw new IllegalArgumentException("a gas absent from the air cannot be harvested: partial pressure "
                    + partialPressure);
        }
        if (intakePower <= 0 || plannedMb <= 0L) {
            return UNPLANNABLE_SECONDS;
        }
        double mbPerSecond = (double) MB_PER_SECOND_PER_INTAKE_AT_ONE_ATM * intakePower
                * ((double) partialPressure / (double) AirState.ONE_ATM);
        double ratio = Math.max(1.0D, (double) capacityForTiming / (double) BASE_CAPACITY_MB);
        double effectiveCapacityMb = (double) BASE_CAPACITY_MB * Math.pow(ratio, CAPACITY_EXPONENT);

        double curveSeconds = Math.floor(effectiveCapacityMb / mbPerSecond);
        double collectSeconds = Math.ceil((double) plannedMb / mbPerSecond);
        return Math.max(1L, (long) Math.min(curveSeconds, collectSeconds));
    }
}
