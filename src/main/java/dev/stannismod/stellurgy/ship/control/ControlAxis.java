package dev.stannismod.stellurgy.ship.control;

/**
 * The six axes a pilot commands, named in the pilot's frame rather than the world's: three
 * translations along, and three rotations about, the {@link ControlFrame}'s forward, right and up.
 *
 * <p>A rotation is positive by the right-hand rule about its axis. Each axis has two independent
 * {@link ControlDirection}s, because a built hull is ordinarily stronger one way than the other.</p>
 */
public enum ControlAxis {
    SURGE(false),
    SWAY(false),
    HEAVE(false),
    ROLL(true),
    PITCH(true),
    YAW(true);

    /** Effectively final, process lifetime: set once when the object is built. */
    private final boolean rotation;

    ControlAxis(boolean rotation) {
        this.rotation = rotation;
    }

    /** Whether commanding this axis asks for an angular acceleration rather than a force. */
    public boolean isRotation() {
        return rotation;
    }
}
