package dev.stannismod.stellurgy.ship.control;

/**
 * One of the twelve signed directions a hull's authority is measured in.
 *
 * <p>Twelve and not six because a sign is not a symmetry: a hull with two engines aft and one
 * forward pushes forward twice as hard as it brakes, and folding the two into one magnitude would
 * average away the very property a player built.</p>
 */
public enum ControlDirection {
    SURGE_POSITIVE(ControlAxis.SURGE, true),
    SURGE_NEGATIVE(ControlAxis.SURGE, false),
    SWAY_POSITIVE(ControlAxis.SWAY, true),
    SWAY_NEGATIVE(ControlAxis.SWAY, false),
    HEAVE_POSITIVE(ControlAxis.HEAVE, true),
    HEAVE_NEGATIVE(ControlAxis.HEAVE, false),
    ROLL_POSITIVE(ControlAxis.ROLL, true),
    ROLL_NEGATIVE(ControlAxis.ROLL, false),
    PITCH_POSITIVE(ControlAxis.PITCH, true),
    PITCH_NEGATIVE(ControlAxis.PITCH, false),
    YAW_POSITIVE(ControlAxis.YAW, true),
    YAW_NEGATIVE(ControlAxis.YAW, false);

    /** Effectively final, process lifetime: set once when the object is built. */
    private final ControlAxis axis;
    /** Effectively final, process lifetime: set once when the object is built. */
    private final boolean positive;

    ControlDirection(ControlAxis axis, boolean positive) {
        this.axis = axis;
        this.positive = positive;
    }

    public ControlAxis axis() {
        return axis;
    }

    public boolean isPositive() {
        return positive;
    }

    public static ControlDirection of(ControlAxis axis, boolean positive) {
        return values()[axis.ordinal() * 2 + (positive ? 0 : 1)];
    }
}
