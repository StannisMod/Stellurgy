package dev.stannismod.stellurgy.ship.control;

import java.util.List;

import dev.stannismod.stellurgy.ship.mass.ShipMassFrame;

/**
 * One ship's derived flight state at one revision: what it can do as built, and what it can do with
 * the devices working now.
 *
 * <p>Immutable, so a physics step reading it on another thread sees either the old model or the new
 * one and never half of each. Never saved: it is rebuilt from the hull on load, after a crossing, and
 * whenever the hull or its load changes.</p>
 */
public final class ShipFlightModel {

    private final long revision;
    private final ShipCapability design;
    private final ShipCapability live;

    private ShipFlightModel(long revision, ShipCapability design, ShipCapability live) {
        this.revision = revision;
        this.design = design;
        this.live = live;
    }

    /**
     * Solve both views over one mass frame.
     *
     * @param revision strictly increasing per ship, so a consumer tests staleness by comparing it
     */
    public static ShipFlightModel solve(long revision, ShipMassFrame mass, List<Actuator> design,
                                        List<Actuator> live, ControlFrame frame) {
        return new ShipFlightModel(revision, ShipCapability.solve(design, mass, frame),
                ShipCapability.solve(live, mass, frame));
    }

    public long revision() {
        return revision;
    }

    /** Every device aboard. */
    public ShipCapability design() {
        return design;
    }

    /** The devices working now; this is what flies. */
    public ShipCapability live() {
        return live;
    }

    /** The readout of this model in a field of {@code gravity} m/s². */
    public ShipReadout readout(double gravity) {
        return ShipReadout.of(revision, design, live, gravity);
    }
}
