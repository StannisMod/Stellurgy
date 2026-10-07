package dev.stannismod.stellurgy.test.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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
import static org.junit.Assert.assertTrue;

/**
 * Integration: the joints of ship-flight-model's kernel — where one of its contracts' promises is
 * another's input, wired as the flight computer wires them ({@link ShipFlightModel#solve}).
 *
 * <ul>
 *   <li>readout (MECH-SFM-09/INV-SFM-07) ↔ delivery (INV-SFM-12): what the readout tells a pilot is
 *       what the scheme delivers;</li>
 *   <li>mass frame (MECH-SFM-01) ↔ readout (INV-SFM-07): cargo enters acceleration, never force.</li>
 * </ul>
 *
 * <p>What it does NOT see: what the world tells a device, how a hull is weighed from blocks, the flight
 * law around the scheme, and the solver that integrates the wrench — see {@link ShipMotionCases}.</p>
 */
public class ShipMotionJointsTest {

    // ---- 3. the readout is what flies -------------------------------------------------------------

    /**
     * What the readout tells a pilot is what the scheme delivers: commanding exactly a direction's
     * readout acceleration — sustained, or burst from a fresh wheel — gets exactly that, not saturated;
     * a direction the readout calls NO_AUTHORITY delivers nothing; and a craft with a broken motor is
     * never stronger in any direction than as built.
     *
     * <p>Over the hand-built hulls and fifty generated ones, each solved as the flight computer solves
     * it: one model, DESIGN over every device and LIVE over the working ones (the first motor taken out
     * as broken), read in the overworld's field.</p>
     *
     * <p>Contract: this fails if a readout figure and the delivered motion part company, or a working
     * set is credited with more than the full one.</p>
     */
    @Test
    public void theReadoutIsWhatTheSchemeDelivers() {
        List<ShipMotionCases.Hull> hulls = new ArrayList<>();
        hulls.add(ShipMotionCases.burstHull());
        hulls.add(ShipMotionCases.symmetricHull());
        hulls.add(ShipMotionCases.wheelOnlyHull());
        Random rng = new Random(0xF1E1DL);
        for (int h = 0; h < 50; h++) {
            hulls.add(ShipMotionCases.randomHull(rng, "generated hull #" + h));
        }
        ControlScheme scheme = ControlScheme.cleanAxes();
        int compared = 0;
        for (ShipMotionCases.Hull hull : hulls) {
            List<Actuator> live = new ArrayList<>(hull.actuators);
            for (int i = 0; i < live.size(); i++) {
                if (live.get(i).isSustained()) {
                    live.remove(i);
                    break;
                }
            }
            ShipFlightModel model = ShipFlightModel.solve(1L, hull.mass, hull.actuators, live, ShipMotionCases.HELM);
            ShipReadout readout = model.readout(ShipMotionCases.G);
            ShipCapability cap = model.live();
            double mass = cap.mass().getTotalMass();
            double tol = CleanCommandLaw.RESIDUAL * CleanCommandLaw.forceScale(cap);
            for (ControlDirection d : ControlDirection.values()) {
                for (Endurance e : Endurance.values()) {
                    double design = readout.authority(ShipReadout.View.DESIGN, d, e);
                    double working = readout.authority(ShipReadout.View.LIVE, d, e);
                    assertTrue(hull.name + ": a broken motor never makes " + d + " " + e
                                    + " stronger — design " + design + ", live " + working,
                            working <= design * (1.0D + CleanCommandLaw.RESIDUAL) + tol);

                    // Inside the figure by the solve's own residual: the readout divides by the mass
                    // and the scheme multiplies back, and exactly at the figure the last bit decides
                    // whether the axis reads as clipped. Measured 2026-10-07: delivered to 1e-16, and
                    // flagged saturated.
                    double figure = readout.acceleration(ShipReadout.View.LIVE, d, e) * (1.0D - CleanCommandLaw.RESIDUAL);
                    Vector3d lin = new Vector3d();
                    Vector3d ang = new Vector3d();
                    Vector3d axis = new Vector3d(ShipMotionCases.HELM.axis(d.axis())).mul(d.isPositive() ? 1.0D : -1.0D);
                    (d.axis().isRotation() ? ang : lin).set(axis.mul(figure));
                    MomentumStore fresh = new MomentumStore();
                    ActuatorCommand c = scheme.allocate(cap, lin, ang, fresh, ShipMotionCases.DT);
                    String where = hull.name + ", " + d + " at its live " + e + " figure " + figure;
                    CleanCommandLaw.requireHonest(where, cap, lin, ang, c, fresh);
                    if (figure > 0.0D) {
                        assertTrue(where + ": the readout's own figure is delivered without saturating"
                                        + " — force " + c.force() + ", torque " + c.torque() + ", mass " + mass
                                        + ", authority " + cap.authority(d, e) + ", asked " + lin + " / " + ang
                                        + ", wheel throttles " + CleanCommandLaw.wheelThrottles(cap, c),
                                !c.isSaturated());
                        compared++;
                    }
                }
                if (readout.warningFor(ShipReadout.View.LIVE, d) == ShipReadout.Warning.NO_AUTHORITY) {
                    Vector3d lin = new Vector3d();
                    Vector3d ang = new Vector3d();
                    Vector3d axis = new Vector3d(ShipMotionCases.HELM.axis(d.axis())).mul(d.isPositive() ? 1.0D : -1.0D);
                    (d.axis().isRotation() ? ang : lin).set(axis);
                    ActuatorCommand c = scheme.allocate(cap, lin, ang, new MomentumStore(), ShipMotionCases.DT);
                    double got = d.axis().isRotation() ? c.torque().length() : c.force().length();
                    assertEquals(hull.name + ": " + d + " has NO_AUTHORITY on the readout, so asking for it"
                            + " delivers nothing", 0.0D, got, CleanCommandLaw.RESIDUAL * CleanCommandLaw.torqueScale(cap));
                    assertTrue(hull.name + ": and says so", c.isSaturated() || mass <= 0.0D);
                }
            }
        }
        requireArranged("some readout figure must have been compared: " + compared, compared > 0);
    }

    // ---- 4. cargo ----------------------------------------------------------------------------------

    /**
     * Cargo stowed at the centre of mass changes how fast the hull accelerates and nothing else about
     * its pushing: every translational authority keeps its newtons, and the readout's acceleration
     * falls by exactly the ratio of the masses.
     *
     * <p>Contract: this fails if content mass enters the force a hull can make, or the readout divides
     * by anything but the whole mass.</p>
     */
    @Test
    public void cargoChangesAccelerationNotForce() {
        ShipMotionCases.Hull empty = ShipMotionCases.symmetricHull();
        Vector3dc centre = empty.mass.getCentreOfMass();
        ShipMassFrameBuilder laden = new ShipMassFrameBuilder();
        laden.addAll(empty.contributors);
        double cargo = 64 * ShipMotionCases.BLOCK_KG;
        laden.add(MassContributor.of(centre.x(), centre.y(), centre.z(), cargo, MassContributor.BLOCK_EXTENT,
                MassContributor.Kind.CONTENT));
        ShipReadout before = ShipFlightModel.solve(1L, empty.mass, empty.actuators, empty.actuators, ShipMotionCases.HELM)
                .readout(ShipMotionCases.G);
        ShipReadout after = ShipFlightModel.solve(2L, laden.build(), empty.actuators, empty.actuators, ShipMotionCases.HELM)
                .readout(ShipMotionCases.G);
        requireArranged("the cargo must be weighed as content: " + after.contentMass(),
                Math.abs(after.contentMass() - cargo) <= cargo * CleanCommandLaw.RESIDUAL);
        double ratio = before.totalMass() / after.totalMass();
        for (ControlDirection d : ControlDirection.values()) {
            if (d.axis().isRotation()) {
                continue;
            }
            double f0 = before.authority(ShipReadout.View.LIVE, d, Endurance.SUSTAINED);
            double f1 = after.authority(ShipReadout.View.LIVE, d, Endurance.SUSTAINED);
            assertEquals(d + ": cargo keeps the force", f0, f1, f0 * CleanCommandLaw.RESIDUAL);
            assertEquals(d + ": and lowers the acceleration by the mass ratio " + ratio,
                    before.acceleration(ShipReadout.View.LIVE, d, Endurance.SUSTAINED) * ratio,
                    after.acceleration(ShipReadout.View.LIVE, d, Endurance.SUSTAINED),
                    f0 * CleanCommandLaw.RESIDUAL / after.totalMass());
        }
    }
}
