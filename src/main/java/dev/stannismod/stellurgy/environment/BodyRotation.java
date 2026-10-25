package dev.stannismod.stellurgy.environment;

/**
 * How a body turns about the z axis of its body-centred frame: one turn every {@code |siderealTicks|},
 * meridian 0 at angle {@code phaseRad} at tick zero. A positive period turns CLOCKWISE seen from +z — the
 * sense a prograde orbit runs in that frame (the cell's Y is the spin axis and its Z is −y, so an orbit whose
 * angle grows from the cell's x toward its z runs clockwise here) — and a negative one the other way. The one
 * rule for "where is the body's meridian 0 now", read by the field (which is fixed to the body) and by
 * anything placed relative to the turning surface.
 */
public final class BodyRotation {

    private final double siderealTicks;
    private final double phaseRad;

    private BodyRotation(double siderealTicks, double phaseRad) {
        this.siderealTicks = siderealTicks;
        this.phaseRad = phaseRad;
    }

    /**
     * A body turning once every {@code |siderealTicks|}, with its orbits when positive, against them when
     * negative; an infinite period does not turn.
     */
    public static BodyRotation of(double siderealTicks, double phaseRad) {
        if (siderealTicks == 0d || Double.isNaN(siderealTicks)) {
            throw new IllegalArgumentException("sidereal period must be non-zero: " + siderealTicks);
        }
        return new BodyRotation(siderealTicks, phaseRad);
    }

    /** The signed sidereal period, ticks: positive with the orbits. */
    public double siderealTicks() {
        return siderealTicks;
    }

    /** The angle of the body's meridian 0 from the frame's x axis at {@code tick}, radians. */
    public double angleAt(long tick) {
        if (Double.isInfinite(siderealTicks)) {
            return phaseRad;
        }
        double period = Math.abs(siderealTicks);
        double turn = (((tick % period) + period) % period) / period;
        return phaseRad - Math.signum(siderealTicks) * 2d * Math.PI * turn;
    }
}
