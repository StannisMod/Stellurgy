package dev.stannismod.stellurgy.ship.control;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import dev.stannismod.stellurgy.ship.mass.ShipMassFrame;

/**
 * What one hull can do: for each of the twelve signed directions, the largest CLEAN force or angular
 * acceleration its actuators produce, and the recipe that produces it.
 *
 * <h2>Clean</h2>
 *
 * <p>Clean means the command does only what it names. Clean surge is the most forward force the
 * actuators can make while making no sideways force and no torque at all; clean pitch is the fastest
 * pitch acceleration they can make while pushing the hull nowhere and turning it about nothing else.
 * For an arbitrary hull that is a constrained allocation — maximise the named component, hold the
 * other five at zero, keep every throttle in its range — and it is solved as one, once per direction,
 * whenever the geometry or the centre of mass moves. A hull that cannot balance a direction is WEAK
 * in it; it is never allowed to deliver it by spinning.</p>
 *
 * <h2>Rotation is an acceleration, not a torque</h2>
 *
 * <p>The same torque turns a light hull quickly and a heavy one slowly, and on a hull whose inertia
 * is not aligned with the pilot's axes a torque about yaw does not produce pure yaw. So a rotational
 * direction asks for torque proportional to {@code I · axis} and its authority is the angular
 * acceleration that yields, in rad/s². {@link #torque} reports the torque behind it, which is the
 * other half of the story a readout tells.</p>
 *
 * <h2>Two endurances</h2>
 *
 * <p>Each direction is solved twice: with the devices that can run forever, and with everything
 * aboard including stored-momentum devices. Where the second is no better, it IS the first — a hull
 * that does not need its wheels for a direction does not wind them up delivering it.</p>
 *
 * <p>Derived state: nothing here is saved. It is rebuilt from the hull.</p>
 */
public final class ShipCapability {

    private static final Logger LOG = LogManager.getLogger("stellurgy.control");

    private final List<Actuator> actuators;
    private final ShipMassFrame mass;
    private final ControlFrame frame;
    /** [endurance][direction] */
    private final Recipe[][] recipes;

    private ShipCapability(List<Actuator> actuators, ShipMassFrame mass, ControlFrame frame,
                           Recipe[][] recipes) {
        this.actuators = actuators;
        this.mass = mass;
        this.frame = frame;
        this.recipes = recipes;
    }

    /**
     * Solve every direction for this hull.
     *
     * @param actuators the devices that are aboard and working, in any order — they are sorted by
     *                  identity first, which is what makes the answer the same on every run
     * @param mass      the hull's mass frame, in the same ship frame as the actuator positions
     */
    public static ShipCapability solve(Collection<Actuator> actuators, ShipMassFrame mass,
                                       ControlFrame frame) {
        List<Actuator> sorted = new ArrayList<>(actuators);
        sorted.sort((a, b) -> a.id().compareTo(b.id()));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).id().equals(sorted.get(i - 1).id())) {
                throw new IllegalArgumentException("two actuators share the identity "
                        + sorted.get(i).id() + "; the allocation order would not be defined");
            }
        }
        List<Actuator> fixed = Collections.unmodifiableList(sorted);
        Recipe[][] recipes = new Recipe[Endurance.values().length][ControlDirection.values().length];
        boolean massless = !(mass.getTotalMass() > AllocationTolerances.MASS_EPSILON);
        for (ControlDirection d : ControlDirection.values()) {
            Recipe sustained = massless ? Recipe.none(fixed.size())
                    : solveOne(fixed, mass, frame, d, true);
            Recipe burst = massless ? Recipe.none(fixed.size())
                    : solveOne(fixed, mass, frame, d, false);
            if (burst.authority <= sustained.authority * (1.0D + AllocationTolerances.FORCE_RESIDUAL)) {
                burst = sustained;
            }
            recipes[Endurance.SUSTAINED.ordinal()][d.ordinal()] = sustained;
            recipes[Endurance.BURST.ordinal()][d.ordinal()] = burst;
        }
        return new ShipCapability(fixed, mass, frame, recipes);
    }

    /** The devices this capability was solved over, in allocation order. */
    public List<Actuator> actuators() {
        return actuators;
    }

    public ShipMassFrame mass() {
        return mass;
    }

    public ControlFrame frame() {
        return frame;
    }

    /**
     * The largest clean authority in {@code direction}: newtons for a translation, rad/s² for a
     * rotation. Zero where the hull cannot deliver the direction cleanly at all.
     */
    public double authority(ControlDirection direction, Endurance endurance) {
        return recipe(direction, endurance).authority;
    }

    /**
     * The torque behind a rotational authority, in N·m: what the actuators produce, as opposed to the
     * motion it makes on this hull.
     *
     * @throws IllegalArgumentException for a translation, which has no torque to report
     */
    public double torque(ControlDirection direction, Endurance endurance) {
        if (!direction.axis().isRotation()) {
            throw new IllegalArgumentException(direction + " is a translation");
        }
        return authority(direction, endurance) * inertiaAlong(frame.axis(direction.axis())).length();
    }

    /**
     * How long the {@link Endurance#BURST} figure in {@code direction} can be held before a
     * stored-momentum device saturates, in seconds. Infinite when that figure needs none.
     */
    public double burstSeconds(ControlDirection direction) {
        return recipe(direction, Endurance.BURST).seconds;
    }

    /** One line per actuator the recipe uses, for a developer asking why a hull is weak. */
    public String explain(ControlDirection direction, Endurance endurance) {
        Recipe r = recipe(direction, endurance);
        StringBuilder out = new StringBuilder();
        out.append(direction).append(' ').append(endurance).append(": authority ")
                .append(String.format(Locale.ROOT, "%.4g", r.authority));
        for (int i = 0; i < actuators.size(); i++) {
            if (Math.abs(r.throttles[i]) > AllocationTolerances.FORCE_RESIDUAL) {
                out.append("\n  ").append(actuators.get(i).id()).append(' ')
                        .append(String.format(Locale.ROOT, "%.1f%%", r.throttles[i] * 100.0D));
            }
        }
        double[] w = wrench(r.throttles);
        out.append(String.format(Locale.ROOT, "%n  wrench F=(%.4g, %.4g, %.4g) tau=(%.4g, %.4g, %.4g)",
                w[0], w[1], w[2], w[3], w[4], w[5]));
        return out.toString();
    }

    Recipe recipe(ControlDirection direction, Endurance endurance) {
        return recipes[endurance.ordinal()][direction.ordinal()];
    }

    /** The wrench about the centre of mass that {@code throttles} produce: force, then torque. */
    double[] wrench(double[] throttles) {
        Vector3dc centre = mass.getCentreOfMass();
        double[] total = new double[6];
        for (int i = 0; i < actuators.size(); i++) {
            double u = throttles[i];
            if (u == 0.0D) {
                continue;
            }
            double[] w = actuators.get(i).wrenchAbout(centre);
            for (int k = 0; k < 6; k++) {
                total[k] += u * w[k];
            }
        }
        return total;
    }

    Vector3d inertiaAlong(Vector3dc axis) {
        Matrix3dc inertia = mass.getInertia();
        return inertia.transform(new Vector3d(axis));
    }

    /**
     * One linear programme: maximise s subject to {@code W u = s t}, every throttle in its range.
     * {@code t} is the unit force along the axis for a translation and {@code I · axis} for a
     * rotation. A two-way device is two one-way columns, so every variable starts at zero, which is
     * feasible, and the solver needs no phase one.
     */
    private static Recipe solveOne(List<Actuator> actuators, ShipMassFrame mass, ControlFrame frame,
                                   ControlDirection direction, boolean sustainedOnly) {
        int n = actuators.size();
        Vector3dc centre = mass.getCentreOfMass();
        Vector3d axis = new Vector3d(frame.axis(direction.axis()));
        if (!direction.isPositive()) {
            axis.negate();
        }
        double[] target = new double[6];
        if (direction.axis().isRotation()) {
            Vector3d t = mass.getInertia().transform(new Vector3d(axis));
            if (!(t.length() > AllocationTolerances.MASS_EPSILON)) {
                return Recipe.none(n);
            }
            target[3] = t.x;
            target[4] = t.y;
            target[5] = t.z;
        } else {
            target[0] = axis.x;
            target[1] = axis.y;
            target[2] = axis.z;
        }

        // columns: one per one-way device, two per two-way device
        List<double[]> columns = new ArrayList<>();
        List<Integer> owner = new ArrayList<>();
        List<Double> sense = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Actuator a = actuators.get(i);
            if (sustainedOnly && !a.isSustained()) {
                continue;
            }
            double[] w = a.wrenchAbout(centre);
            columns.add(w);
            owner.add(i);
            sense.add(1.0D);
            if (a.minThrottle() < 0.0D) {
                double[] neg = new double[6];
                for (int k = 0; k < 6; k++) {
                    neg[k] = -w[k] * -a.minThrottle();
                }
                columns.add(neg);
                owner.add(i);
                sense.add(a.minThrottle());
            }
        }
        if (columns.isEmpty()) {
            return Recipe.none(n);
        }

        // scale force and torque rows to order one, so one pivot tolerance means the same on both
        double forceScale = 0.0D;
        double torqueScale = 0.0D;
        for (double[] w : columns) {
            for (int k = 0; k < 3; k++) {
                forceScale = Math.max(forceScale, Math.abs(w[k]));
                torqueScale = Math.max(torqueScale, Math.abs(w[k + 3]));
            }
        }
        if (!(forceScale > 0.0D)) {
            forceScale = 1.0D;
        }
        if (!(torqueScale > 0.0D)) {
            torqueScale = 1.0D;
        }
        int cols = columns.size();
        double[][] a = new double[6][cols + 1];
        for (int j = 0; j < cols; j++) {
            double[] w = columns.get(j);
            for (int k = 0; k < 6; k++) {
                a[k][j] = w[k] / (k < 3 ? forceScale : torqueScale);
            }
        }
        // the s column, itself scaled to order one; sigma converts back
        double sMax = 0.0D;
        for (int k = 0; k < 6; k++) {
            double v = target[k] / (k < 3 ? forceScale : torqueScale);
            a[k][cols] = -v;
            sMax = Math.max(sMax, Math.abs(v));
        }
        double sigma = 1.0D / sMax;
        for (int k = 0; k < 6; k++) {
            a[k][cols] *= sigma;
        }
        double[] upper = new double[cols + 1];
        java.util.Arrays.fill(upper, 1.0D);
        upper[cols] = Double.POSITIVE_INFINITY;
        double[] c = new double[cols + 1];
        c[cols] = 1.0D;

        BoundedSimplex.Result result = BoundedSimplex.maximise(a, upper, c,
                AllocationTolerances.PIVOT, AllocationTolerances.MAX_ITERATIONS);
        if (!result.converged) {
            LOG.warn("clean allocation for {} did not converge over {} actuators; the hull is given "
                    + "NO authority in that direction, which is not a statement about its geometry",
                    direction, n);
            return Recipe.none(n);
        }

        double s = result.x[cols] * sigma;
        double[] throttles = new double[n];
        for (int j = 0; j < cols; j++) {
            double x = Math.max(0.0D, Math.min(1.0D, result.x[j]));
            throttles[owner.get(j)] += x * (sense.get(j) > 0.0D ? 1.0D : sense.get(j));
        }

        // verify: the constraints are the contract, and a recipe that breaks them is refused
        double[] w = new double[6];
        for (int i = 0; i < n; i++) {
            if (throttles[i] != 0.0D) {
                double[] wi = actuators.get(i).wrenchAbout(centre);
                for (int k = 0; k < 6; k++) {
                    w[k] += throttles[i] * wi[k];
                }
            }
        }
        double forceResidual = 0.0D;
        double torqueResidual = 0.0D;
        for (int k = 0; k < 6; k++) {
            double r = Math.abs(w[k] - s * target[k]);
            if (k < 3) {
                forceResidual = Math.max(forceResidual, r);
            } else {
                torqueResidual = Math.max(torqueResidual, r);
            }
        }
        if (forceResidual > AllocationTolerances.FORCE_RESIDUAL * forceScale
                || torqueResidual > AllocationTolerances.TORQUE_RESIDUAL * torqueScale) {
            LOG.warn("clean allocation for {} left a residual force {} N / torque {} N·m; the hull "
                    + "is given NO authority in that direction, which is not a statement about its "
                    + "geometry", direction, forceResidual, torqueResidual);
            return Recipe.none(n);
        }
        if (!(s > 0.0D)) {
            return Recipe.none(n);
        }

        double seconds = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            Actuator act = actuators.get(i);
            if (!act.isSustained() && throttles[i] != 0.0D) {
                double rate = Math.abs(throttles[i]) * act.maxTorque().length();
                if (rate > 0.0D) {
                    seconds = Math.min(seconds, act.momentumCapacity() / rate);
                }
            }
        }
        return new Recipe(throttles, s, seconds);
    }
}
