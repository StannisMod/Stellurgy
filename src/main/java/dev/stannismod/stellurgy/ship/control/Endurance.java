package dev.stannismod.stellurgy.ship.control;

/**
 * How long an authority figure can be held.
 *
 * <p>A reaction wheel does not make torque, it trades momentum with the hull, and it can take only
 * so much before it saturates. So a hull that pushes forward cleanly only because a wheel is
 * nulling an off-centre engine has that authority for a number of seconds, not in general — and the
 * two figures are reported apart rather than averaged into one that is true of neither.</p>
 */
public enum Endurance {
    /** What the devices that can run indefinitely deliver on their own. */
    SUSTAINED,
    /** What everything aboard delivers together, stored-momentum devices included, for a budget of time. */
    BURST
}
