package dev.stannismod.stellurgy.environment;

/**
 * How a body turns: once every {@code periodTicks}, at angle {@code phaseRad} at tick zero, about the
 * z axis of its body-centred frame. The one rule for "where is the body's meridian 0 now", read by the
 * field (which is fixed to the body) and by anything placed relative to the turning surface.
 */
public final class BodyRotation {

    private final long periodTicks;
    private final double phaseRad;

    private BodyRotation(long periodTicks, double phaseRad) {
        this.periodTicks = periodTicks;
        this.phaseRad = phaseRad;
    }

    /** A body turning once every {@code periodTicks}, at angle {@code phaseRad} at tick zero. */
    public static BodyRotation of(long periodTicks, double phaseRad) {
        if (periodTicks <= 0L) {
            throw new IllegalArgumentException("rotation period must be positive: " + periodTicks);
        }
        return new BodyRotation(periodTicks, phaseRad);
    }

    /** The angle of the body's meridian 0 from the frame's x axis at {@code tick}, radians. */
    public double angleAt(long tick) {
        long turn = Math.floorMod(tick, periodTicks);
        return phaseRad + 2d * Math.PI * turn / periodTicks;
    }
}
