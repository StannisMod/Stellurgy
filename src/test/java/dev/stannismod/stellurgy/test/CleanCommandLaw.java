package dev.stannismod.stellurgy.test;

import java.util.List;

import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import dev.stannismod.stellurgy.ship.control.Actuator;
import dev.stannismod.stellurgy.ship.control.ActuatorCommand;
import dev.stannismod.stellurgy.ship.control.ActuatorId;
import dev.stannismod.stellurgy.ship.control.ControlAxis;
import dev.stannismod.stellurgy.ship.control.MomentumStore;
import dev.stannismod.stellurgy.ship.control.ShipCapability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Instrument: the law a delivered command obeys (ship-flight-model INV-SFM-12) as one verifier, and the
 * readings its failures print. Shared by the owner's laws and joints classes.
 */
public final class CleanCommandLaw {

    private CleanCommandLaw() {
    }

    /**
     * The residual a clean recipe may leave on an axis it does not name, as a fraction of the largest
     * force or torque aboard — the bound the capability solve itself enforces (INV-SFM-01; measured
     * worst 1.9e-15). A delivered command is a positive sum of such recipes, so it inherits the bound.
     */
    public static final double RESIDUAL = 1.0e-6D;

    // ---- the law ------------------------------------------------------------------------------------

    /**
     * The law every step obeys. Per pilot axis, comparing what was asked with what the wrench does to
     * this hull (force over mass along a translation, inverse inertia times torque along a rotation):
     * an axis not asked for gets nothing; an axis asked for gets its own sign and no more than asked;
     * an unsaturated command gets exactly what it asked. Every throttle is in its device's range and
     * every wheel inside its capacity.
     *
     * <p>The mass a translation is judged by is the hull's DECLARED mass ({@link
     * ShipMotionCases.Hull#declaredMass}), never the capability's own frame: judged by {@code
     * cap.mass()}, a scheme that multiplied by a wrong mass the capability also carried would be divided
     * back by the same wrong mass and pass. What this verifier does NOT see: a defect in the inertia
     * tensor the mass builder computes, which a rotation is judged by (MECH-SFM-01, pinned in {@code
     * ShipMassFrameTest}), and whether the law it checks is sensitive at all — that is witnessed by the
     * red records of the laws that call it, not here.</p>
     */
    public static void requireHonest(String where, ShipMotionCases.Hull hull, ShipCapability cap,
                                      Vector3dc lin, Vector3dc ang, ActuatorCommand c, MomentumStore momentum) {
        List<Actuator> actuators = cap.actuators();
        for (int i = 0; i < actuators.size(); i++) {
            Actuator a = actuators.get(i);
            double u = c.throttle(i);
            assertTrue(where + ": " + a.id() + " throttle " + u + " out of [" + a.minThrottle() + ", 1]",
                    u >= a.minThrottle() - 1.0e-12D && u <= 1.0D + 1.0e-12D);
            if (!a.isSustained()) {
                double held = momentum.given(a.id());
                assertTrue(where + ": " + a.id() + " holds " + held + " past its capacity "
                        + a.momentumCapacity(), Math.abs(held) <= a.momentumCapacity() * (1.0D + 1.0e-12D));
            }
        }
        double mass = hull.declaredMass();
        double forceTol = RESIDUAL * forceScale(cap);
        double torqueTol = RESIDUAL * torqueScale(cap);
        if (mass > 0.0D) {
            Vector3d a = new Vector3d(c.force()).div(mass);
            for (ControlAxis axis : ControlAxis.values()) {
                if (!axis.isRotation()) {
                    Vector3dc e = ShipMotionCases.HELM.axis(axis);
                    axisVerdict(where, axis, lin.dot(e), a.dot(e), forceTol / mass, c.isSaturated());
                }
            }
            Matrix3d inverse = new Matrix3d(hull.mass.getInertia()).invert();
            Vector3d alpha = inverse.transform(new Vector3d(c.torque()));
            double alphaTol = torqueTol * frobenius(inverse);
            for (ControlAxis axis : ControlAxis.values()) {
                if (axis.isRotation()) {
                    Vector3dc e = ShipMotionCases.HELM.axis(axis);
                    axisVerdict(where, axis, ang.dot(e), alpha.dot(e), alphaTol, c.isSaturated());
                }
            }
        } else {
            assertEquals(where + ": a massless hull is pushed by nothing", 0.0D, c.force().length(), forceTol);
            assertEquals(where + ": and turned by nothing", 0.0D, c.torque().length(), torqueTol);
        }
    }

    public static void axisVerdict(String where, ControlAxis axis, double want, double got, double tol,
                                    boolean saturated) {
        if (want == 0.0D) {
            assertEquals(where + ": " + axis + " was not asked for and moved anyway", 0.0D, got, tol);
            return;
        }
        double sense = Math.signum(want);
        assertTrue(where + ": " + axis + " delivered the wrong way — asked " + want + ", got " + got,
                got * sense >= -tol);
        assertTrue(where + ": " + axis + " delivered more than asked — asked " + want + ", got " + got,
                Math.abs(got) <= Math.abs(want) + tol);
        if (!saturated) {
            assertEquals(where + ": " + axis + " delivered less than asked without saying so",
                    want, got, tol);
        }
    }

    // ---- reading a command --------------------------------------------------------------------------

    public static boolean usesAWheel(ShipCapability cap, ActuatorCommand c) {
        for (int i = 0; i < cap.actuators().size(); i++) {
            if (!cap.actuators().get(i).isSustained() && c.throttle(i) != 0.0D) {
                return true;
            }
        }
        return false;
    }

    /** Each wheel axis as {@code index:held/capacity}, the store's own numbers. */
    public static String wheels(ShipCapability cap, MomentumStore momentum) {
        StringBuilder out = new StringBuilder();
        for (Actuator a : cap.actuators()) {
            if (a.isSustained()) {
                continue;
            }
            out.append(a.id().index()).append(':').append(momentum.given(a.id())).append('/')
                    .append(a.momentumCapacity()).append(' ');
        }
        return out.length() == 0 ? "none" : out.toString().trim();
    }

    public static String wheelThrottles(ShipCapability cap, ActuatorCommand c) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < cap.actuators().size(); i++) {
            if (!cap.actuators().get(i).isSustained()) {
                out.append(cap.actuators().get(i).id().index()).append(':').append(c.throttle(i)).append(' ');
            }
        }
        return out.length() == 0 ? "none" : out.toString().trim();
    }

    public static double wheelMomentum(ShipCapability cap, MomentumStore momentum) {
        double total = 0.0D;
        for (Actuator a : cap.actuators()) {
            if (!a.isSustained()) {
                total += Math.abs(momentum.given(a.id()));
            }
        }
        return total;
    }

    /** The largest force any device aboard makes, N. */
    public static double forceScale(ShipCapability cap) {
        double s = 0.0D;
        for (Actuator a : cap.actuators()) {
            s = Math.max(s, a.maxForce().length());
        }
        return Math.max(s, 1.0D);
    }

    /** The largest torque any device aboard makes about the centre of mass, N·m. */
    public static double torqueScale(ShipCapability cap) {
        Vector3dc centre = cap.mass().getCentreOfMass();
        double s = 0.0D;
        for (Actuator a : cap.actuators()) {
            ActuatorId id = a.id();
            double lever = new Vector3d(id.x() + 0.5D, id.y() + 0.5D, id.z() + 0.5D).sub(centre).length();
            s = Math.max(s, Math.max(a.maxTorque().length(), a.maxForce().length() * lever));
        }
        return Math.max(s, 1.0D);
    }

    public static double frobenius(Matrix3d m) {
        return Math.sqrt(m.m00 * m.m00 + m.m01 * m.m01 + m.m02 * m.m02 + m.m10 * m.m10 + m.m11 * m.m11
                + m.m12 * m.m12 + m.m20 * m.m20 + m.m21 * m.m21 + m.m22 * m.m22);
    }

}
