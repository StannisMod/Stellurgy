package dev.stannismod.stellurgy.ship.control;

import dev.stannismod.stellurgy.ship.mass.ShipMassFrame;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.*;

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
 * <h2>Two endurances, two column sets</h2>
 *
 * <p>Each direction is solved twice: with the devices that can run forever, and with everything
 * aboard including stored-momentum devices. Where the second is no better, it IS the first — a hull
 * that does not need its wheels for a direction does not wind them up delivering it.</p>
 *
 * <p>And once more of each with the BOOST columns (C10 STAT-42): an engine with a variable
 * exhaust velocity is two columns sharing one capacity, {@code u_cruise + u_boost ≤ 1}, which the
 * programme holds as a row of its own. The cruise tables leave boost columns out; a hull with no boost
 * column has one set, used for both. Every set is built together, so switching between them costs
 * nothing.</p>
 *
 * <h2>One pressure, and the band it holds over</h2>
 *
 * <p>A reaction drive's force falls with the outside pressure by its own bell's area, so recipes solved
 * at one pressure leave a residual at another. The capability is solved at the pressure its drives were
 * evaluated at, and states the band around it inside which every cached recipe stays within the
 * allocation's residual tolerances (C10 STAT-43); inside it the applied force follows each
 * drive's affine law ({@link #wrenchAt}), outside it the model wants rebuilding.</p>
 *
 * <p>Derived state: nothing here is saved. It is rebuilt from the hull.</p>
 */
public final class ShipCapability {

    /** Effectively final, process lifetime: built once at class initialisation. */
    private static final Logger LOG = LogManager.getLogger("stellurgy.control");

    private static final int CRUISE = 0;
    private static final int BOOST = 1;

    private final List<Actuator> actuators;
    private final ShipMassFrame mass;
    private final ControlFrame frame;
    /** [set][endurance][direction] */
    private final Recipe[][][] recipes;
    /** Per actuator, the index of the column it shares its capacity with; -1 for none. */
    private final int[] twin;
    /** The pressure the drives were evaluated at, Pa; NaN when no drive follows a pressure. */
    private final double ambientPascals;
    /** Half the width of the band of pressures the recipes hold over, Pa. */
    private final double bandHalfWidth;

    private ShipCapability(List<Actuator> actuators, ShipMassFrame mass, ControlFrame frame,
                           Recipe[][][] recipes, int[] twin, double ambientPascals, double bandHalfWidth) {
        this.actuators = actuators;
        this.mass = mass;
        this.frame = frame;
        this.recipes = recipes;
        this.twin = twin;
        this.ambientPascals = ambientPascals;
        this.bandHalfWidth = bandHalfWidth;
    }

    /**
     * Solve every direction for this hull.
     *
     * @param actuators the devices that are aboard and working, in any order — they are sorted by
     *                  identity first, which is what makes the answer the same on every run. Every
     *                  reaction drive among them must have been evaluated at one outside pressure
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
        double ambient = commonPressure(fixed);
        int[] twin = twins(fixed);
        boolean anyBoost = false;
        for (Actuator a : fixed) {
            anyBoost |= a.isBoost();
        }
        Recipe[][][] recipes = new Recipe[2][Endurance.values().length][ControlDirection.values().length];
        boolean massless = !(mass.getTotalMass() > AllocationTolerances.MASS_EPSILON);
        for (ControlDirection d : ControlDirection.values()) {
            Recipe sustained = massless ? Recipe.none(fixed.size())
                    : solveOne(fixed, mass, frame, d, true);
            Recipe burst = massless ? Recipe.none(fixed.size())
                    : solveOne(fixed, mass, frame, d, false);
            if (burst.authority <= sustained.authority * (1.0D + AllocationTolerances.FORCE_RESIDUAL)) {
                burst = sustained;
            }
            recipes[CRUISE][Endurance.SUSTAINED.ordinal()][d.ordinal()] = sustained;
            recipes[CRUISE][Endurance.BURST.ordinal()][d.ordinal()] = burst;
            if (!anyBoost) {
                recipes[BOOST][Endurance.SUSTAINED.ordinal()][d.ordinal()] = sustained;
                recipes[BOOST][Endurance.BURST.ordinal()][d.ordinal()] = burst;
                continue;
            }
            Recipe boostSustained = massless ? sustained : solveOne(fixed, mass, frame, d, true, true);
            if (boostSustained.authority <= sustained.authority * (1.0D + AllocationTolerances.FORCE_RESIDUAL)) {
                boostSustained = sustained;
            }
            Recipe boostBurst = massless ? burst : solveOne(fixed, mass, frame, d, false, true);
            if (boostBurst.authority <= boostSustained.authority * (1.0D + AllocationTolerances.FORCE_RESIDUAL)) {
                boostBurst = boostSustained;
            }
            recipes[BOOST][Endurance.SUSTAINED.ordinal()][d.ordinal()] = boostSustained;
            recipes[BOOST][Endurance.BURST.ordinal()][d.ordinal()] = boostBurst;
        }
        double band = bandHalfWidth(fixed, mass, recipes);
        return new ShipCapability(fixed, mass, frame, recipes, twin, ambient, band);
    }

    /** The one pressure every reaction drive was evaluated at; NaN when there is none. */
    private static double commonPressure(List<Actuator> actuators) {
        double p = Double.NaN;
        for (Actuator a : actuators) {
            double q = a.ambientPascals();
            if (Double.isNaN(q)) {
                continue;
            }
            if (Double.isNaN(p)) {
                p = q;
            } else if (p != q) {
                throw new IllegalArgumentException("drives evaluated at two outside pressures, " + p + " and " + q
                        + " Pa; one capability is solved at one");
            }
        }
        return p;
    }

    private static int[] twins(List<Actuator> actuators) {
        int[] twin = new int[actuators.size()];
        Arrays.fill(twin, -1);
        Map<ActuatorId, Integer> index = new HashMap<>();
        for (int i = 0; i < actuators.size(); i++) {
            index.put(actuators.get(i).id(), i);
        }
        for (int i = 0; i < actuators.size(); i++) {
            ActuatorId cruise = actuators.get(i).cruiseTwin();
            Integer j = cruise == null ? null : index.get(cruise);
            if (j != null) {
                twin[i] = j;
                twin[j] = i;
            }
        }
        return twin;
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
     * The largest clean authority in {@code direction} on the cruise columns: newtons for a translation,
     * rad/s² for a rotation. Zero where the hull cannot deliver the direction cleanly at all.
     */
    public double authority(ControlDirection direction, Endurance endurance) {
        return recipe(direction, endurance).authority;
    }

    /** The same with the boost columns allowed; equal to {@link #authority} on a hull without any. */
    public double boostAuthority(ControlDirection direction, Endurance endurance) {
        return boostRecipe(direction, endurance).authority;
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

    /** The pressure this capability was solved at, Pa; NaN for a hull whose force follows no pressure. */
    public double ambientPascals() {
        return ambientPascals;
    }

    /**
     * Whether the recipes still hold at {@code pascals} outside: inside the band of C10 STAT-43. Always
     * true for a hull whose force follows no pressure.
     */
    public boolean holdsAt(double pascals) {
        return Double.isNaN(ambientPascals) || Math.abs(pascals - ambientPascals) <= bandHalfWidth;
    }

    /** Half the width of that band, Pa; infinite for a hull whose force follows no pressure. */
    public double bandHalfWidth() {
        return bandHalfWidth;
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

    /** The cruise recipe. */
    Recipe recipe(ControlDirection direction, Endurance endurance) {
        return recipes[CRUISE][endurance.ordinal()][direction.ordinal()];
    }

    /** The recipe with the boost columns allowed. */
    Recipe boostRecipe(ControlDirection direction, Endurance endurance) {
        return recipes[BOOST][endurance.ordinal()][direction.ordinal()];
    }

    /**
     * The burst recipe of the same column set as {@code sustained}: the boost set's when
     * {@code sustained} is that set's own sustained recipe and differs from the cruise one, the cruise
     * set's otherwise.
     */
    Recipe burstOf(ControlDirection direction, Recipe sustained) {
        boolean boosted = sustained == recipes[BOOST][Endurance.SUSTAINED.ordinal()][direction.ordinal()]
                && sustained != recipes[CRUISE][Endurance.SUSTAINED.ordinal()][direction.ordinal()];
        return recipes[boosted ? BOOST : CRUISE][Endurance.BURST.ordinal()][direction.ordinal()];
    }

    /** The index of the column {@code i} shares its capacity with, or -1. */
    int twin(int i) {
        return twin[i];
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

    /**
     * The wrench {@code throttles} produce with {@code ambientPascals} outside: each reaction drive at
     * its affine law at that pressure (STAT-31, STAT-43). At the pressure the capability was solved at
     * it is {@link #wrench}.
     */
    double[] wrenchAt(double[] throttles, double ambientPascals) {
        if (Double.isNaN(this.ambientPascals) || ambientPascals == this.ambientPascals) {
            return wrench(throttles);
        }
        Vector3dc centre = mass.getCentreOfMass();
        double[] total = new double[6];
        for (int i = 0; i < actuators.size(); i++) {
            double u = throttles[i];
            if (u == 0.0D) {
                continue;
            }
            double[] w = actuators.get(i).wrenchAtPressure(centre, ambientPascals);
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
     * Half the width of the band of pressures over which every cached recipe keeps its off-axis
     * residual within the allocation's tolerances: a pressure {@code Δp} away moves recipe {@code u}'s
     * wrench by {@code −Δp·Σ uᵢAᵢ(nᵢ, rᵢ×nᵢ)}, so it is at most {@code |Δp|·Σ|uᵢ|Aᵢ} in force and
     * {@code |Δp|·Σ|uᵢ|Aᵢ|rᵢ|} in torque, against {@code FORCE_RESIDUAL·forceScale} and
     * {@code TORQUE_RESIDUAL·torqueScale} — the scales the solve itself compares with. And never so wide
     * that a drive's regime crosses its separation pressure, where the force stops being affine.
     */
    private static double bandHalfWidth(List<Actuator> actuators, ShipMassFrame mass, Recipe[][][] recipes) {
        Vector3dc centre = mass.getCentreOfMass();
        double forceScale = 0.0D;
        double torqueScale = 0.0D;
        double room = Double.POSITIVE_INFINITY;
        double[] area = new double[actuators.size()];
        double[] arm = new double[actuators.size()];
        for (int i = 0; i < actuators.size(); i++) {
            Actuator a = actuators.get(i);
            double[] w = a.wrenchAbout(centre);
            for (int k = 0; k < 3; k++) {
                forceScale = Math.max(forceScale, Math.abs(w[k]));
                torqueScale = Math.max(torqueScale, Math.abs(w[k + 3]));
            }
            area[i] = a.exitArea();
            arm[i] = new Vector3d(a.position()).sub(centre).length();
            room = Math.min(room, a.affineRoom());
        }
        double half = room;
        for (Recipe[][] set : recipes) {
            for (Recipe[] endurance : set) {
                for (Recipe r : endurance) {
                    double forceSlope = 0.0D;
                    double torqueSlope = 0.0D;
                    for (int i = 0; i < area.length; i++) {
                        double u = Math.abs(r.throttles[i]);
                        forceSlope += u * area[i];
                        torqueSlope += u * area[i] * arm[i];
                    }
                    if (forceSlope > 0.0D) {
                        half = Math.min(half, AllocationTolerances.FORCE_RESIDUAL * forceScale / forceSlope);
                    }
                    if (torqueSlope > 0.0D) {
                        half = Math.min(half, AllocationTolerances.TORQUE_RESIDUAL * Math.max(torqueScale, 0.0D)
                                / torqueSlope);
                    }
                }
            }
        }
        return half;
    }

    /**
     * One linear programme: maximise s subject to {@code W u = s t}, every throttle in its range, and
     * — with the boost columns in — every cruise and boost pair inside one capacity.
     * {@code t} is the unit force along the axis for a translation and {@code I · axis} for a
     * rotation. A two-way device is two one-way columns, and a shared capacity is a row
     * {@code u_c + u_b − w = 0} with {@code 0 ≤ w ≤ 1}, so every variable starts at zero, which is
     * feasible, and the solver needs no phase one.
     */
    private static Recipe solveOne(List<Actuator> actuators, ShipMassFrame mass, ControlFrame frame,
                                   ControlDirection direction, boolean sustainedOnly, boolean withBoost) {
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
        int[] columnOf = new int[n];
        Arrays.fill(columnOf, -1);
        for (int i = 0; i < n; i++) {
            Actuator a = actuators.get(i);
            if (sustainedOnly && !a.isSustained()) {
                continue;
            }
            if (!withBoost && a.isBoost()) {
                continue;
            }
            double[] w = a.wrenchAbout(centre);
            columnOf[i] = columns.size();
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
        // shared capacities: a boost column and its cruise twin, both in this solve
        List<int[]> groups = new ArrayList<>();
        Map<ActuatorId, Integer> byId = new HashMap<>();
        for (int i = 0; i < n; i++) {
            byId.put(actuators.get(i).id(), i);
        }
        for (int i = 0; i < n; i++) {
            ActuatorId cruise = actuators.get(i).cruiseTwin();
            Integer c = cruise == null ? null : byId.get(cruise);
            if (c != null && columnOf[i] >= 0 && columnOf[c] >= 0) {
                groups.add(new int[] {columnOf[c], columnOf[i]});
            }
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
        int g = groups.size();
        int sIndex = cols + g;
        double[][] a = new double[6 + g][sIndex + 1];
        for (int j = 0; j < cols; j++) {
            double[] w = columns.get(j);
            for (int k = 0; k < 6; k++) {
                a[k][j] = w[k] / (k < 3 ? forceScale : torqueScale);
            }
        }
        for (int q = 0; q < g; q++) {
            a[6 + q][groups.get(q)[0]] = 1.0D;
            a[6 + q][groups.get(q)[1]] = 1.0D;
            a[6 + q][cols + q] = -1.0D;
        }
        // the s column, itself scaled to order one; sigma converts back
        double sMax = 0.0D;
        for (int k = 0; k < 6; k++) {
            double v = target[k] / (k < 3 ? forceScale : torqueScale);
            a[k][sIndex] = -v;
            sMax = Math.max(sMax, Math.abs(v));
        }
        double sigma = 1.0D / sMax;
        for (int k = 0; k < 6; k++) {
            a[k][sIndex] *= sigma;
        }
        double[] upper = new double[sIndex + 1];
        java.util.Arrays.fill(upper, 1.0D);
        upper[sIndex] = Double.POSITIVE_INFINITY;
        double[] c = new double[sIndex + 1];
        c[sIndex] = 1.0D;

        BoundedSimplex.Result result = BoundedSimplex.maximise(a, upper, c,
                AllocationTolerances.PIVOT, AllocationTolerances.MAX_ITERATIONS);
        if (!result.converged) {
            LOG.warn("clean allocation for {} did not converge over {} actuators; the hull is given "
                    + "NO authority in that direction, which is not a statement about its geometry",
                    direction, n);
            return Recipe.none(n);
        }

        double s = result.x[sIndex] * sigma;
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
        for (int[] pair : groups) {
            double shared = throttles[owner.get(pair[0])] + throttles[owner.get(pair[1])];
            if (shared > 1.0D + AllocationTolerances.PIVOT) {
                LOG.warn("clean allocation for {} drove a cruise and boost pair to {} of one capacity; the "
                        + "hull is given NO authority in that direction", direction, shared);
                return Recipe.none(n);
            }
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

    /** {@link #solveOne} over the cruise columns. */
    private static Recipe solveOne(List<Actuator> actuators, ShipMassFrame mass, ControlFrame frame,
                                   ControlDirection direction, boolean sustainedOnly) {
        return solveOne(actuators, mass, frame, direction, sustainedOnly, false);
    }
}
