package dev.stannismod.stellurgy.ship.control;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.stannismod.stellurgy.ship.mass.ShipMassFrame;

/**
 * What a ship's readout says: the primitives of its flight model, and the arithmetic every surface
 * draws from them.
 *
 * <p>Primitives only — masses, the twelve signed authorities with their torques and endurances, the
 * local field — because those are what the server is the source of. Acceleration, thrust-to-weight
 * and the warnings are divisions and comparisons of those numbers, done here on whichever side holds
 * one of these, so the console, the HUD and the assembler cannot disagree about them.</p>
 *
 * <p>Two views side by side: DESIGN is every actuator aboard, LIVE is the ones working now. They
 * differ when a device is broken; the difference is what a pilot needs to see.</p>
 */
public final class ShipReadout {

    /** Which set of actuators a figure is about. */
    public enum View {
        /** Every actuator aboard, as built. */
        DESIGN,
        /** The actuators that are working right now. */
        LIVE
    }

    /** A soft verdict about the build: something a pilot should know, never a refusal. */
    public enum Warning {
        /** A direction the hull cannot deliver cleanly at all. */
        NO_AUTHORITY,
        /** A direction delivered only by stored-momentum devices, for a budget of seconds. */
        BURST_ONLY,
        /** Holdable upward thrust below the craft's weight in the local field. */
        CANNOT_HOVER
    }

    private static final int DIRECTIONS = ControlDirection.values().length;

    private final long revision;
    private final double structural;
    private final double content;
    private final double crew;
    private final double gravity;
    /** [view][endurance][direction]: newtons or rad/s² */
    private final double[][][] authority;
    /** [view][endurance][direction]: N·m behind a rotational authority, 0 for a translation */
    private final double[][][] torque;
    /** [view][direction]: seconds the burst figure lasts */
    private final double[][] seconds;

    private ShipReadout(long revision, double structural, double content, double crew, double gravity,
                        double[][][] authority, double[][][] torque, double[][] seconds) {
        this.revision = revision;
        this.structural = structural;
        this.content = content;
        this.crew = crew;
        this.gravity = gravity;
        this.authority = authority;
        this.torque = torque;
        this.seconds = seconds;
    }

    /**
     * The readout of a flight model.
     *
     * @param gravity the magnitude of the local field in m/s²; zero where there is none
     */
    public static ShipReadout of(long revision, ShipCapability design, ShipCapability live, double gravity) {
        double[][][] a = new double[2][2][DIRECTIONS];
        double[][][] t = new double[2][2][DIRECTIONS];
        double[][] s = new double[2][DIRECTIONS];
        ShipCapability[] views = {design, live};
        for (int v = 0; v < 2; v++) {
            for (ControlDirection d : ControlDirection.values()) {
                for (Endurance e : Endurance.values()) {
                    a[v][e.ordinal()][d.ordinal()] = views[v].authority(d, e);
                    t[v][e.ordinal()][d.ordinal()] = d.axis().isRotation() ? views[v].torque(d, e) : 0.0D;
                }
                s[v][d.ordinal()] = views[v].burstSeconds(d);
            }
        }
        ShipMassFrame mass = live.mass();
        return new ShipReadout(revision, mass.getStructuralMass(), mass.getContentMass(),
                mass.getCrewMass(), gravity, a, t, s);
    }

    public long revision() {
        return revision;
    }

    public double structuralMass() {
        return structural;
    }

    public double contentMass() {
        return content;
    }

    public double crewMass() {
        return crew;
    }

    public double totalMass() {
        return structural + content + crew;
    }

    /** The local field, m/s². */
    public double gravity() {
        return gravity;
    }

    /** Newtons for a translation, rad/s² for a rotation. */
    public double authority(View view, ControlDirection direction, Endurance endurance) {
        return authority[view.ordinal()][endurance.ordinal()][direction.ordinal()];
    }

    /** The torque behind a rotational figure, N·m. */
    public double torque(View view, ControlDirection direction, Endurance endurance) {
        return torque[view.ordinal()][endurance.ordinal()][direction.ordinal()];
    }

    public double burstSeconds(View view, ControlDirection direction) {
        return seconds[view.ordinal()][direction.ordinal()];
    }

    /**
     * The acceleration a figure gives this craft: m/s² for a translation (force over the whole mass,
     * cargo and crew included, which is the point), rad/s² for a rotation (already an acceleration).
     */
    public double acceleration(View view, ControlDirection direction, Endurance endurance) {
        double f = authority(view, direction, endurance);
        if (direction.axis().isRotation()) {
            return f;
        }
        double m = totalMass();
        return m > 0.0D ? f / m : 0.0D;
    }

    /**
     * Holdable upward thrust over weight in the local field. Infinite where there is no field — a
     * craft in a cell weighs nothing, and "infinitely able to hover" is the true reading, not a
     * placeholder.
     */
    public double thrustToWeight(View view) {
        double weight = totalMass() * gravity;
        double up = authority(view, ControlDirection.HEAVE_POSITIVE, Endurance.SUSTAINED);
        if (!(weight > 0.0D)) {
            return Double.POSITIVE_INFINITY;
        }
        return up / weight;
    }

    /** The warning, if any, about one direction. */
    public Warning warningFor(View view, ControlDirection direction) {
        if (!(authority(view, direction, Endurance.BURST) > 0.0D)) {
            return Warning.NO_AUTHORITY;
        }
        if (!(authority(view, direction, Endurance.SUSTAINED) > 0.0D)) {
            return Warning.BURST_ONLY;
        }
        return null;
    }

    /** Whether this craft can hold itself up in the local field on its sustained thrust. */
    public boolean canHover(View view) {
        return thrustToWeight(view) >= 1.0D;
    }

    public List<Warning> warnings(View view) {
        List<Warning> out = new ArrayList<>();
        for (ControlDirection d : ControlDirection.values()) {
            Warning w = warningFor(view, d);
            if (w != null && !out.contains(w)) {
                out.add(w);
            }
        }
        if (!canHover(view)) {
            out.add(Warning.CANNOT_HOVER);
        }
        return Collections.unmodifiableList(out);
    }

    public void write(DataOutput out) throws IOException {
        out.writeLong(revision);
        out.writeDouble(structural);
        out.writeDouble(content);
        out.writeDouble(crew);
        out.writeDouble(gravity);
        for (int v = 0; v < 2; v++) {
            for (int e = 0; e < 2; e++) {
                for (int d = 0; d < DIRECTIONS; d++) {
                    out.writeDouble(authority[v][e][d]);
                    out.writeDouble(torque[v][e][d]);
                }
            }
            for (int d = 0; d < DIRECTIONS; d++) {
                out.writeDouble(seconds[v][d]);
            }
        }
    }

    public static ShipReadout read(DataInput in) throws IOException {
        long revision = in.readLong();
        double structural = in.readDouble();
        double content = in.readDouble();
        double crew = in.readDouble();
        double gravity = in.readDouble();
        double[][][] a = new double[2][2][DIRECTIONS];
        double[][][] t = new double[2][2][DIRECTIONS];
        double[][] s = new double[2][DIRECTIONS];
        for (int v = 0; v < 2; v++) {
            for (int e = 0; e < 2; e++) {
                for (int d = 0; d < DIRECTIONS; d++) {
                    a[v][e][d] = in.readDouble();
                    t[v][e][d] = in.readDouble();
                }
            }
            for (int d = 0; d < DIRECTIONS; d++) {
                s[v][d] = in.readDouble();
            }
        }
        return new ShipReadout(revision, structural, content, crew, gravity, a, t, s);
    }
}
