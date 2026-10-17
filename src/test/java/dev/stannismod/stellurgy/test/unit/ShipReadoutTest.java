package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.ship.control.*;
import dev.stannismod.stellurgy.ship.control.ShipReadout.View;
import dev.stannismod.stellurgy.ship.control.ShipReadout.Warning;
import dev.stannismod.stellurgy.ship.mass.MassContributor;
import dev.stannismod.stellurgy.ship.mass.MassContributor.Kind;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrame;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrameBuilder;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.joml.Vector3d;
import org.junit.Test;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * The readout is what every surface draws — console, HUD, assembler — so these pin what it promises
 * a pilot: the same engines accelerate a loaded ship less, thrust-to-weight is about the field the
 * ship is in, a direction the hull cannot give is named, and what crosses the wire arrives intact.
 *
 * <p>Frame: forward +Z, right -X, up +Y. Masses and thrusts are round numbers; the verdicts are
 * relationships between them.</p>
 */
public class ShipReadoutTest {

    /** A constant: a {@code ControlFrame} copies its axes and hands them out read-only. */
    private static final ControlFrame HELM =
            ControlFrame.of(new Vector3d(0, 0, 1), new Vector3d(-1, 0, 0), new Vector3d(0, 1, 0));
    private static final double T = 100_000.0D;

    /** Four engines pushing up at the corners of a 3x3 slab, and one pushing forward on the centre line. */
    private static List<Actuator> engines() {
        List<Actuator> out = new ArrayList<>();
        int[][] corners = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] c : corners) {
            out.add(Actuator.pointForce(new ActuatorId(c[0], -1, c[1], 0), new Vector3d(c[0], 0, c[1]),
                    new Vector3d(0, T, 0)));
        }
        out.add(Actuator.pointForce(new ActuatorId(0, 0, -1, 0), new Vector3d(0, 0, -1), new Vector3d(0, 0, T)));
        return out;
    }

    private static ShipMassFrame slab(double cargoKg) {
        ShipMassFrameBuilder b = new ShipMassFrameBuilder();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                b.add(MassContributor.ofBlock(x, 0, z, 1000.0D, Kind.STRUCTURAL));
            }
        }
        if (cargoKg > 0.0D) {
            b.add(MassContributor.ofBlock(0, 0, 0, cargoKg, Kind.CONTENT));
        }
        return b.build();
    }

    private static ShipReadout readout(double cargoKg, double gravity) {
        List<Actuator> e = engines();
        return ShipFlightModel.solve(1L, slab(cargoKg), e, e, HELM).readout(gravity);
    }

    /**
     * Loading cargo leaves the engines' force where it was and lowers the acceleration it buys,
     * exactly in proportion to the mass — the causality a player is meant to see.
     *
     * Pins INV-SFM-07 (cargo lowers acceleration, never force, and thrust to weight is about the local field).
     */
    @Pins("INV-SFM-07")
    @RedWitness(verdict = "the same force", member = "ShipReadout#of", at = "views[v].authority(d, e);",
            replacedBy = "views[v].authority(d, e) * views[v].mass().getStructuralMass() / views[v].mass().getTotalMass();",
            fails = "the same force", on = "2026-10-17")
    @RedWitness(verdict = "accelerates less", member = "ShipReadout#acceleration", at = "double m = totalMass();",
            replacedBy = "double m = structuralMass();", fails = "the loaded ship accelerates less", on = "2026-10-17")
    @Test
    public void cargoLowersAccelerationNotForce() {
        ShipReadout empty = readout(0.0D, 9.81D);
        ShipReadout loaded = readout(9000.0D, 9.81D);
        // Both bars are slack for rounding only. Measured 2026-09-30: the force difference is exactly
        // 0.0 and the acceleration difference exactly 0.0 (accel 5.5556 m/s²); the witnessed breaks
        // were off by 50000 N and 5.56 m/s².
        assertEquals("the same force", empty.authority(View.LIVE, ControlDirection.SURGE_POSITIVE, Endurance.SUSTAINED),
                loaded.authority(View.LIVE, ControlDirection.SURGE_POSITIVE, Endurance.SUSTAINED), 1.0e-6);
        assertEquals("the loaded ship accelerates less, by the mass ratio",
                empty.acceleration(View.LIVE, ControlDirection.SURGE_POSITIVE, Endurance.SUSTAINED) / 2.0D,
                loaded.acceleration(View.LIVE, ControlDirection.SURGE_POSITIVE, Endurance.SUSTAINED), 1.0e-9);
    }

    /**
     * Thrust-to-weight is about the field the ship is in: the same hull that hovers on a light world
     * cannot on a heavy one, and where there is no field it weighs nothing — an infinite ratio, never
     * a refusal.
     *
     * Pins INV-SFM-07 (cargo lowers acceleration, never force, and thrust to weight is about the local field).
     */
    @Pins("INV-SFM-07")
    @RedWitness(verdict = "hovers", member = "ShipReadout#canHover", at = "return thrustToWeight(view) >= 1.0D;",
            replacedBy = "return thrustToWeight(view) >= 10.0D;", fails = "it hovers on the light world", on = "2026-10-17")
    @RedWitness(verdict = "the local field", member = "ShipReadout#thrustToWeight", at = "double weight = totalMass() * gravity;",
            replacedBy = "double weight = totalMass() * 9.81D;", fails = "and not on one five times heavier", on = "2026-10-17")
    @RedWitness(verdict = "no field", member = "ShipReadout#thrustToWeight", at = "return Double.POSITIVE_INFINITY;",
            replacedBy = "return 0.0D;", fails = "infinite where there is no field", on = "2026-10-17")
    @Test
    public void thrustToWeightIsAboutTheLocalField() {
        // 4T up against 9 t: TWR 4e5 / (9000 g)
        assertTrue("it hovers on the light world", readout(0.0D, 9.81D).canHover(View.LIVE));
        assertFalse("and not on one five times heavier", readout(0.0D, 5.0D * 9.81D).canHover(View.LIVE));
        assertTrue("infinite where there is no field",
                Double.isInfinite(readout(0.0D, 0.0D).thrustToWeight(View.LIVE)));
    }

    /**
     * A direction the hull cannot give is named, never averaged away, and so is not being able to
     * hover.
     */
    @RedWitness(verdict = "reverse is named", member = "ShipReadout#warningFor",
            at = "if (!(authority(view, direction, Endurance.BURST) > 0.0D)) { return Warning.NO_AUTHORITY; }",
            replacedBy = "if (!(authority(view, direction, Endurance.SUSTAINED) > 0.0D)) { return Warning.BURST_ONLY; }",
            fails = "reverse is named", on = "2026-10-17")
    @RedWitness(verdict = "forward is not", member = "ShipReadout#warningFor", at = "return null;",
            replacedBy = "return Warning.BURST_ONLY;", fails = "forward is not", on = "2026-10-17")
    @RedWitness(verdict = "cannot hover", member = "ShipReadout#warnings", at = "out.add(Warning.CANNOT_HOVER);",
            replacedBy = "", fails = "cannot hover is named", on = "2026-10-17")
    @Test
    public void whatTheHullCannotDoIsNamed() {
        ShipReadout r = readout(0.0D, 5.0D * 9.81D);
        assertEquals("reverse is named: no engine pushes back", Warning.NO_AUTHORITY,
                r.warningFor(View.LIVE, ControlDirection.SURGE_NEGATIVE));
        assertNull("forward is not", r.warningFor(View.LIVE, ControlDirection.SURGE_POSITIVE));
        assertTrue("cannot hover is named", r.warnings(View.LIVE).contains(Warning.CANNOT_HOVER));
    }

    /**
     * What crosses the wire arrives intact: every figure a surface reads, including an infinite
     * endurance, survives encoding and decoding.
     */
    @RedWitness(verdict = "revision", member = "ShipReadout#write", at = "out.writeLong(revision);",
            replacedBy = "out.writeLong(0L);", fails = "revision", on = "2026-10-17")
    @RedWitness(verdict = "mass", member = "ShipReadout#write", at = "out.writeDouble(structural);",
            replacedBy = "out.writeDouble(0.0D);", fails = "mass", on = "2026-10-17")
    @RedWitness(verdict = "field", member = "ShipReadout#write", at = "out.writeDouble(gravity);",
            replacedBy = "out.writeDouble(0.0D);", fails = "field", on = "2026-10-17")
    @RedWitness(verdict = "every figure", member = "ShipReadout#write", at = "out.writeDouble(authority[v][e][d]);",
            replacedBy = "out.writeDouble(0.0D);", fails = "every figure", on = "2026-10-17")
    @RedWitness(verdict = "every torque", member = "ShipReadout#write", at = "out.writeDouble(torque[v][e][d]);",
            replacedBy = "out.writeDouble(0.0D);", fails = "every figure: torque", on = "2026-10-17")
    @RedWitness(verdict = "endurance", member = "ShipReadout#write", at = "out.writeDouble(seconds[v][d]);",
            replacedBy = "out.writeDouble(0.0D);", fails = "endurance", on = "2026-10-17")
    @Test
    public void theWireCarriesEveryFigure() throws IOException {
        // A wheel aboard, so the rotational figures, their torques and a finite endurance exist to be
        // carried: a hull with none sends zeros there, and zeros survive any encoding.
        List<Actuator> hull = engines();
        hull.add(Actuator.pureTorque(new ActuatorId(0, 1, 0, 0), new Vector3d(5000, 0, 0), 20_000));
        hull.add(Actuator.pureTorque(new ActuatorId(0, 1, 0, 1), new Vector3d(0, 5000, 0), 20_000));
        hull.add(Actuator.pureTorque(new ActuatorId(0, 1, 0, 2), new Vector3d(0, 0, 5000), 20_000));
        ShipReadout sent = ShipFlightModel.solve(1L, slab(1234.0D), hull, hull, HELM).readout(9.81D);
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                "the arrangement carries a rotation: a finite yaw endurance and a torque behind it",
                !Double.isInfinite(sent.burstSeconds(View.LIVE, ControlDirection.YAW_POSITIVE))
                        && sent.torque(View.LIVE, ControlDirection.YAW_POSITIVE, Endurance.BURST) > 0.0D);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        sent.write(new DataOutputStream(bytes));
        ShipReadout got = ShipReadout.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals("revision", sent.revision(), got.revision());
        assertEquals("mass", sent.totalMass(), got.totalMass(), 0.0D);
        assertEquals("field", sent.gravity(), got.gravity(), 0.0D);
        for (View v : View.values()) {
            for (ControlDirection d : ControlDirection.values()) {
                for (Endurance e : Endurance.values()) {
                    assertEquals("every figure: " + v + " " + d + " " + e,
                            sent.authority(v, d, e), got.authority(v, d, e), 0.0D);
                    assertEquals("every figure: torque " + v + " " + d + " " + e,
                            sent.torque(v, d, e), got.torque(v, d, e), 0.0D);
                }
                assertEquals("every figure: endurance " + v + " " + d,
                        sent.burstSeconds(v, d), got.burstSeconds(v, d), 0.0D);
            }
        }
    }
}
