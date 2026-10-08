package dev.stannismod.stellurgy.test.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.Test;

import dev.stannismod.stellurgy.ship.control.Actuator;
import dev.stannismod.stellurgy.ship.control.ActuatorCommand;
import dev.stannismod.stellurgy.ship.control.ControlDirection;
import dev.stannismod.stellurgy.ship.control.ControlScheme;
import dev.stannismod.stellurgy.ship.control.Endurance;
import dev.stannismod.stellurgy.ship.control.MomentumStore;
import dev.stannismod.stellurgy.ship.control.ShipCapability;
import dev.stannismod.stellurgy.ship.control.ShipFlightModel;
import dev.stannismod.stellurgy.ship.control.ShipReadout;
import dev.stannismod.stellurgy.ship.mass.MassContributor;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrameBuilder;
import dev.stannismod.stellurgy.test.CleanCommandLaw;
import dev.stannismod.stellurgy.test.ShipMotionCases;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;

/**
 * Integration: the joints of ship-flight-model's kernel — where one contract's promise is another's
 * input, wired as the flight computer wires them ({@link ShipFlightModel#solve}). Each method compares a
 * value one side produced with a value the other side received or acted on, and is witnessed red by
 * breaking the HANDOFF between them, never one side's own decision (that side's law is pinned in
 * {@code ShipMotionLawsTest} and {@code ShipReadoutTest}).
 *
 * <ul>
 *   <li>readout ↔ scheme (INV-SFM-13): what the readout states is what the scheme delivers;</li>
 *   <li>mass frame ↔ readout (MECH-SFM-01 ↔ INV-SFM-07): the cargo the frame weighs is the mass the
 *       readout's acceleration divides by.</li>
 * </ul>
 *
 * <p>Every mass a verdict divides by is the hull's DECLARED mass, the sum of its contributors, so no
 * expected value shares the mass builder with the code under test. What it does NOT see: the inertia
 * tensor the builder computes (a rotation is judged by it; MECH-SFM-01 is pinned in {@code
 * ShipMassFrameTest}), what the world tells a device, how a hull is weighed from blocks, the flight law
 * around the scheme, and the packet that carries a readout to a client.</p>
 */
public class ShipMotionJointsTest {

    /**
     * INV-SFM-13, its first half: a command at a LIVE direction's readout figure is delivered whole.
     * The readout's figure (side A) is commanded into the scheme, and what the scheme's wrench does to
     * the hull (side B — force over the declared mass, or inverse inertia times torque) must equal it.
     *
     * <p>Over the hand-built hulls and fifty generated ones, each solved as the flight computer solves
     * it — DESIGN over every device, LIVE over all but the first motor — so DESIGN and LIVE differ and a
     * readout that handed over the wrong view would be seen. Commanded inside the figure by the solve's
     * own residual: exactly at the figure the last bit decides whether an axis reads as clipped
     * (measured 2026-10-07: delivered to 1e-16, and flagged saturated).</p>
     *
     * <p>red-witnessed: with {@code ShipReadout#of} at {@code ShipCapability[] views = {design, live};}
     * swapped to {@code {live, design}} (the readout handed the wrong view), this fails with "burst hull,
     * SURGE_POSITIVE SUSTAINED: the readout states 23.543976456 and the scheme delivers
     * 11.523999999999997", 2026-10-07.</p>
     */
    @Test
    public void aReadoutFigureIsDeliveredWhole() {
        ControlScheme scheme = ControlScheme.cleanAxes();
        int compared = 0;
        int weakened = 0;
        for (ShipMotionCases.Hull hull : hulls()) {
            ShipFlightModel model = solveWithOneMotorBroken(hull);
            ShipReadout readout = model.readout(ShipMotionCases.G);
            ShipCapability cap = model.live();
            double mass = hull.declaredMass();
            if (!(mass > 0.0D)) {
                continue;
            }
            Matrix3d inverse = new Matrix3d(hull.mass.getInertia()).invert();
            for (ControlDirection d : ControlDirection.values()) {
                for (Endurance e : Endurance.values()) {
                    if (readout.authority(ShipReadout.View.LIVE, d, e)
                            < readout.authority(ShipReadout.View.DESIGN, d, e) * (1.0D - CleanCommandLaw.RESIDUAL)) {
                        weakened++;
                    }
                    double figure = readout.acceleration(ShipReadout.View.LIVE, d, e) * (1.0D - CleanCommandLaw.RESIDUAL);
                    if (!(figure > 0.0D)) {
                        continue;
                    }
                    Vector3d axis = signedAxis(d);
                    Vector3d lin = new Vector3d();
                    Vector3d ang = new Vector3d();
                    (d.axis().isRotation() ? ang : lin).set(new Vector3d(axis).mul(figure));
                    ActuatorCommand c = scheme.allocate(cap, lin, ang, new MomentumStore(), ShipMotionCases.DT);
                    double delivered;
                    double tol;
                    if (d.axis().isRotation()) {
                        delivered = inverse.transform(new Vector3d(c.torque())).dot(axis);
                        tol = CleanCommandLaw.RESIDUAL * CleanCommandLaw.torqueScale(cap) * CleanCommandLaw.frobenius(inverse);
                    } else {
                        delivered = c.force().dot(axis) / mass;
                        tol = CleanCommandLaw.RESIDUAL * CleanCommandLaw.forceScale(cap) / mass;
                    }
                    assertEquals(hull.name + ", " + d + " " + e + ": the readout states " + figure
                            + " and the scheme delivers " + delivered + " (force " + c.force() + ", torque "
                            + c.torque() + ", declared mass " + mass + ")", figure, delivered, tol);
                    compared++;
                }
            }
        }
        requireArranged("readout figures must have been compared: " + compared, compared > 0);
        requireArranged("some LIVE figure must be weaker than its DESIGN figure, or a readout handing over"
                + " the wrong view could not be seen: " + weakened, weakened > 0);
    }

    /**
     * INV-SFM-13, its second half: a direction the readout calls {@code NO_AUTHORITY} is delivered
     * nothing. The readout's warning (side A) against what the scheme's wrench does when that direction
     * is asked for (side B).
     *
     * <p>Over the same hulls as above plus the wheel-only hull, whose translations have no authority at
     * all and whose rotations are burst-only — the case where a readout reading the wrong endurance would
     * call a direction unavailable that the scheme can deliver.</p>
     *
     * <p>red-witnessed: with {@code ShipReadout#of} at {@code a[v][e.ordinal()][d.ordinal()] =
     * views[v].authority(d, e);} handed {@code Endurance.SUSTAINED} for both endurances (the burst figure
     * lost on the way to the readout), this fails with "burst hull: ROLL_POSITIVE is NO_AUTHORITY on the
     * readout, and the scheme delivered 270833.33333333326", 2026-10-07.</p>
     */
    @Test
    public void aDirectionTheReadoutCallsUnavailableIsDeliveredNothing() {
        ControlScheme scheme = ControlScheme.cleanAxes();
        int unavailable = 0;
        for (ShipMotionCases.Hull hull : hulls()) {
            ShipFlightModel model = solveWithOneMotorBroken(hull);
            ShipReadout readout = model.readout(ShipMotionCases.G);
            ShipCapability cap = model.live();
            for (ControlDirection d : ControlDirection.values()) {
                if (readout.warningFor(ShipReadout.View.LIVE, d) != ShipReadout.Warning.NO_AUTHORITY) {
                    continue;
                }
                Vector3d lin = new Vector3d();
                Vector3d ang = new Vector3d();
                (d.axis().isRotation() ? ang : lin).set(signedAxis(d));
                ActuatorCommand c = scheme.allocate(cap, lin, ang, new MomentumStore(), ShipMotionCases.DT);
                double got = d.axis().isRotation() ? c.torque().length() : c.force().length();
                double tol = CleanCommandLaw.RESIDUAL
                        * (d.axis().isRotation() ? CleanCommandLaw.torqueScale(cap) : CleanCommandLaw.forceScale(cap));
                assertEquals(hull.name + ": " + d + " is NO_AUTHORITY on the readout, and the scheme"
                        + " delivered " + got, 0.0D, got, tol);
                unavailable++;
            }
        }
        requireArranged("some direction must read NO_AUTHORITY: " + unavailable, unavailable > 0);
    }

    /**
     * The joint MECH-SFM-01 ↔ INV-SFM-07: cargo the mass frame weighs is the mass the readout's
     * acceleration divides by. Cargo stowed at the centre of mass leaves the force a hull makes as it
     * was, so the laden readout's acceleration must be the empty hull's force over the DECLARED laden
     * mass — the hull's contributors plus the cargo put aboard.
     *
     * <p>Relative on purpose: the one production quantity in the expected
     * value is the empty hull's force authority, which the cargo is the experiment's way of leaving
     * unchanged; every mass in it is declared.</p>
     *
     * <p>red-witnessed: with {@code ShipReadout#of} at {@code mass.getContentMass()} replaced by
     * {@code 0.0D} (the cargo dropped between the frame and the readout), this fails with "SURGE_POSITIVE:
     * the laden readout must divide the hull's force 4905000.0 N by the declared laden mass 485000.0 kg
     * expected:&lt;10.11340206185567&gt; but was:&lt;29.727272727272727&gt;", 2026-10-07.</p>
     */
    @Test
    public void cargoTheFrameWeighsIsTheMassTheReadoutDividesBy() {
        ShipMotionCases.Hull empty = ShipMotionCases.symmetricHull();
        Vector3dc centre = empty.mass.getCentreOfMass();
        double cargo = 64 * ShipMotionCases.BLOCK_KG;
        ShipMassFrameBuilder laden = new ShipMassFrameBuilder();
        laden.addAll(empty.contributors);
        laden.add(MassContributor.of(centre.x(), centre.y(), centre.z(), cargo, MassContributor.BLOCK_EXTENT,
                MassContributor.Kind.CONTENT));
        ShipReadout before = ShipFlightModel.solve(1L, empty.mass, empty.actuators, empty.actuators, ShipMotionCases.HELM)
                .readout(ShipMotionCases.G);
        ShipReadout after = ShipFlightModel.solve(2L, laden.build(), empty.actuators, empty.actuators, ShipMotionCases.HELM)
                .readout(ShipMotionCases.G);
        double declared = empty.declaredMass() + cargo;
        int compared = 0;
        for (ControlDirection d : ControlDirection.values()) {
            if (d.axis().isRotation()) {
                continue;
            }
            double force = before.authority(ShipReadout.View.LIVE, d, Endurance.SUSTAINED);
            if (!(force > 0.0D)) {
                continue;
            }
            assertEquals(d + ": the laden readout must divide the hull's force " + force + " N by the declared"
                            + " laden mass " + declared + " kg",
                    force / declared, after.acceleration(ShipReadout.View.LIVE, d, Endurance.SUSTAINED),
                    force * CleanCommandLaw.RESIDUAL / declared);
            compared++;
        }
        requireArranged("the symmetric hull must push in some translation: " + compared, compared > 0);
    }

    // ---- the hulls -----------------------------------------------------------------------------------

    private static List<ShipMotionCases.Hull> hulls() {
        List<ShipMotionCases.Hull> hulls = new ArrayList<>();
        hulls.add(ShipMotionCases.burstHull());
        hulls.add(ShipMotionCases.symmetricHull());
        hulls.add(ShipMotionCases.wheelOnlyHull());
        Random rng = new Random(0xF1E1DL);
        for (int h = 0; h < 50; h++) {
            hulls.add(ShipMotionCases.randomHull(rng, "generated hull #" + h));
        }
        return hulls;
    }

    /** DESIGN over every device, LIVE without the first sustained motor: the flight computer's two views. */
    private static ShipFlightModel solveWithOneMotorBroken(ShipMotionCases.Hull hull) {
        List<Actuator> live = new ArrayList<>(hull.actuators);
        for (int i = 0; i < live.size(); i++) {
            if (live.get(i).isSustained()) {
                live.remove(i);
                break;
            }
        }
        return ShipFlightModel.solve(1L, hull.mass, hull.actuators, live, ShipMotionCases.HELM);
    }

    private static Vector3d signedAxis(ControlDirection d) {
        return new Vector3d(ShipMotionCases.HELM.axis(d.axis())).mul(d.isPositive() ? 1.0D : -1.0D);
    }
}
