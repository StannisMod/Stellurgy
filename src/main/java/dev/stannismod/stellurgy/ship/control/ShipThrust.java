package dev.stannismod.stellurgy.ship.control;

import org.joml.Vector3d;

/**
 * What a held throttle asks of a hull with Flight Assist off: in each held signed direction, the
 * acceleration the hull can hold there, scaled by how far the throttle is deflected — and nothing else.
 * No velocity is aimed at and no speed bounds it, so the craft accelerates for as long as the throttle
 * is held, which is what flight without the assist is.
 *
 * <p>Asked for the SUSTAINED figure because a held throttle is a request to keep pushing: a burst leans
 * on a wheel that empties, and would turn a steady push into one that fades.</p>
 */
public final class ShipThrust {

    private ShipThrust() {}

    /**
     * The acceleration, m/s² in the ship's own frame, that the body-frame throttle {@code throttle} —
     * {@code {forward, right, up}}, each deflection in {@code [-1, 1]} — asks of a hull whose figures
     * are {@code readout}, its directions laid out in {@code frame}.
     */
    public static Vector3d wanted(double[] throttle, ShipReadout readout, ControlFrame frame) {
        Vector3d out = new Vector3d();
        add(out, throttle[0], ControlAxis.SURGE, readout, frame);
        add(out, throttle[1], ControlAxis.SWAY, readout, frame);
        add(out, throttle[2], ControlAxis.HEAVE, readout, frame);
        return out;
    }

    private static void add(Vector3d out, double deflection, ControlAxis axis, ShipReadout readout,
                            ControlFrame frame) {
        double held = Math.max(-1.0D, Math.min(1.0D, deflection));
        if (held == 0.0D || Double.isNaN(held)) {
            return;
        }
        ControlDirection direction = ControlDirection.of(axis, held > 0.0D);
        double accel = readout.acceleration(ShipReadout.View.LIVE, direction, Endurance.SUSTAINED);
        out.fma(held * accel, frame.axis(axis));
    }
}
