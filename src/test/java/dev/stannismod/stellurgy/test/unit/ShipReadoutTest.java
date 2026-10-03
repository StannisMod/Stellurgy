package dev.stannismod.stellurgy.test.unit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3d;
import org.junit.Test;

import dev.stannismod.stellurgy.ship.control.Actuator;
import dev.stannismod.stellurgy.ship.control.ActuatorId;
import dev.stannismod.stellurgy.ship.control.ControlDirection;
import dev.stannismod.stellurgy.ship.control.ControlFrame;
import dev.stannismod.stellurgy.ship.control.Endurance;
import dev.stannismod.stellurgy.ship.control.ShipFlightModel;
import dev.stannismod.stellurgy.ship.control.ShipReadout;
import dev.stannismod.stellurgy.ship.control.ShipReadout.View;
import dev.stannismod.stellurgy.ship.control.ShipReadout.Warning;
import dev.stannismod.stellurgy.ship.mass.MassContributor;
import dev.stannismod.stellurgy.ship.mass.MassContributor.Kind;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrame;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrameBuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The readout is what every surface draws — console, HUD, assembler — so these pin what it promises
 * a pilot: the same engines accelerate a loaded ship less, thrust-to-weight is about the field the
 * ship is in, a direction the hull cannot give is named, and what crosses the wire arrives intact.
 *
 * <p>Frame: forward +Z, right -X, up +Y. Masses and thrusts are round numbers; the verdicts are
 * relationships between them.</p>
 */
public class ShipReadoutTest {

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
     * <p>red-witnessed: one break per verdict, 2026-09-30 — {@code ShipReadout#of} at {@code a[v][e.ordinal()][d.ordinal()] = views[v].authority(d, e);} recording the force
     * scaled by the mass fails "the same force" with 50000 against 100000; {@code ShipReadout#acceleration} at {@code return m > 0.0D ? f / m : 0.0D;}
     * dividing by the structural mass only fails "the loaded ship accelerates less" with 11.11 against
     * 5.56.</p>
     */
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
     * <p>red-witnessed: one break per verdict, 2026-09-30 — {@code ShipReadout#canHover} at {@code return thrustToWeight(view) >= 1.0D;} demanding a ratio
     * of 10 to hover fails "it hovers on the light world"; {@code ShipReadout#thrustToWeight} at {@code double weight = totalMass() * gravity;} weighing against a
     * fixed 9.81 instead of the local field fails "and not on one five times heavier";
     * {@code ShipReadout#thrustToWeight} at {@code return Double.POSITIVE_INFINITY;} answering 0 for no field fails "infinite where there is no field".</p>
     */
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
     *
     * <p>red-witnessed: one break per verdict, 2026-09-30 — {@code ShipReadout#warningFor} at {@code if (!(authority(view, direction, Endurance.BURST) > 0.0D))} testing the
     * sustained figure first fails "reverse is named" with BURST_ONLY; {@code ShipReadout#warningFor} at {@code return Warning.BURST_ONLY;}
     * answering BURST_ONLY where nothing is wrong fails "forward is not"; {@code ShipReadout#warnings} at {@code out.add(Warning.CANNOT_HOVER);} not
     * adding the hover warning fails "cannot hover is named".</p>
     */
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
     *
     * <p>red-witnessed: one break per verdict, 2026-09-30 — each by writing a zero in place of one
     * field — {@code ShipReadout#write} at {@code out.writeLong(revision);} fails "revision"; {@code ShipReadout#write} at {@code out.writeDouble(structural);} fails "mass" with 1234 against
     * 10234; {@code ShipReadout#write} at {@code out.writeDouble(gravity);} fails "field"; {@code ShipReadout#write} at {@code out.writeDouble(authority[v][e][d]);} fails "every figure" on DESIGN SURGE_POSITIVE;
     * {@code ShipReadout#write} at {@code out.writeDouble(torque[v][e][d]);} fails "every figure: torque" on DESIGN ROLL_POSITIVE BURST (it was GREEN before the
     * wheel was added — a hull with no rotation sends zeros there); {@code ShipReadout#write} at {@code out.writeDouble(seconds[v][d]);} fails "endurance" on
     * an infinite figure.</p>
     */
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
