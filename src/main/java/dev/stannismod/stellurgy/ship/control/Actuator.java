package dev.stannismod.stellurgy.ship.control;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * One thing aboard that can push or turn the ship, reduced to what the allocation needs: the wrench
 * it adds per unit of throttle, the range its throttle may take, and whether it can hold that
 * indefinitely.
 *
 * <p>This is deliberately not "a thruster". A chemical motor is a force at a point; a reaction
 * wheel is a pure torque that stores the momentum it gives the hull; a gimballed engine, when it
 * exists, is more than one of these sharing a block. The allocator sees only bounded linear
 * contributions, so a new kind of device is a new way of producing them and never a change here.</p>
 *
 * <p>A REACTION drive also says what it takes (C10 STAT-22, STAT-37): one {@link PropellantDemand} per
 * regime it can run. Its force depends on the outside pressure, so it remembers the pressure it was
 * evaluated at and can give its force at another (STAT-31). An engine with a variable exhaust velocity
 * is two columns that share one capacity — cruise and boost, {@code u_cruise + u_boost ≤ 1} (C10 STAT-42);
 * the boost column names its cruise twin.</p>
 *
 * <p>Every quantity is SI in the ship's own frame: newtons, newton-metres, newton-metre-seconds,
 * pascals, and positions in blocks, which are metres.</p>
 */
public final class Actuator {

    private final ActuatorId id;
    private final Vector3dc position;
    private final Vector3dc maxForce;
    private final Vector3dc maxTorque;
    private final double minThrottle;
    private final double momentumCapacity;
    private final List<PropellantDemand> demands;
    /** The unit push of a reaction drive; {@code null} for a device whose force does not follow a pressure. */
    @Nullable
    private final Vector3dc direction;
    /** The outside pressure {@link #maxForce} was evaluated at, Pa; NaN for a device that has none. */
    private final double ambientPascals;
    /** For a boost column, the cruise column it shares its capacity with; otherwise {@code null}. */
    @Nullable
    private final ActuatorId cruiseTwin;

    private Actuator(ActuatorId id, Vector3dc position, Vector3dc maxForce, Vector3dc maxTorque,
                     double minThrottle, double momentumCapacity, List<PropellantDemand> demands,
                     @Nullable Vector3dc direction, double ambientPascals, @Nullable ActuatorId cruiseTwin) {
        this.id = id;
        this.position = position;
        this.maxForce = maxForce;
        this.maxTorque = maxTorque;
        this.minThrottle = minThrottle;
        this.momentumCapacity = momentumCapacity;
        this.demands = Collections.unmodifiableList(new ArrayList<>(demands));
        this.direction = direction;
        this.ambientPascals = ambientPascals;
        this.cruiseTwin = cruiseTwin;
    }

    /**
     * A reaction drive at a point: it pushes along the unit {@code direction} with the force of its
     * regimes at {@code ambientPascals}, and declares what each regime takes (C10 STAT-37). Until
     * something decides which regime feeds it, its force is the LEAST of its regimes' — authority it has
     * whichever it is fed; a drive with no regime pushes nothing.
     */
    public static Actuator reactionDrive(ActuatorId id, Vector3dc position, Vector3dc direction,
                                         List<PropellantDemand> demands, double ambientPascals) {
        requireFinite(position, "position");
        requireFinite(direction, "direction");
        if (!(ambientPascals >= 0)) {
            throw new IllegalArgumentException("an outside pressure is never negative: " + ambientPascals);
        }
        Vector3d unit = new Vector3d(direction).normalize();
        return new Actuator(id, new Vector3d(position), unit.mul(leastForce(demands, ambientPascals), new Vector3d()),
                new Vector3d(), 0.0D, Double.POSITIVE_INFINITY, demands, unit, ambientPascals, null);
    }

    /**
     * The BOOST column of {@code cruise}: the same nozzle run at a lower exhaust velocity, pushing with
     * {@code boostDemands}' force, and sharing the cruise column's capacity — every recipe and every
     * command keeps {@code u_cruise + u_boost ≤ 1} (STAT-42). Its identity is the cruise column's with the
     * next index, so a block that declares a boost column puts nothing else at that index.
     */
    public static Actuator boostOf(Actuator cruise, List<PropellantDemand> boostDemands) {
        if (cruise.direction == null || cruise.cruiseTwin != null) {
            throw new IllegalArgumentException("only a reaction drive's cruise column has a boost column: " + cruise);
        }
        ActuatorId at = cruise.id;
        Vector3dc unit = cruise.direction;
        return new Actuator(new ActuatorId(at.x(), at.y(), at.z(), at.index() + 1), cruise.position,
                unit.mul(leastForce(boostDemands, cruise.ambientPascals), new Vector3d()), new Vector3d(), 0.0D,
                Double.POSITIVE_INFINITY, boostDemands, unit, cruise.ambientPascals, at);
    }

    /**
     * A force applied at a point, which may only push: throttle from zero to one. It produces torque
     * about the centre of mass through its lever arm, which is why where it is built matters.
     * Holdable indefinitely, takes nothing, and does not follow a pressure.
     *
     * @param maxForce the force at full throttle, in newtons; its direction is the push on the hull
     */
    public static Actuator pointForce(ActuatorId id, Vector3dc position, Vector3dc maxForce) {
        requireFinite(position, "position");
        requireFinite(maxForce, "maxForce");
        return new Actuator(id, new Vector3d(position), new Vector3d(maxForce), new Vector3d(), 0.0D,
                Double.POSITIVE_INFINITY, Collections.<PropellantDemand>emptyList(), null, Double.NaN, null);
    }

    /**
     * A pure torque about {@code maxTorque}'s axis, in either sense: throttle from minus one to one.
     * It pushes nothing, and it cannot hold a torque forever — it gives the hull momentum by taking
     * the opposite momentum itself, up to {@code momentumCapacity}.
     */
    public static Actuator pureTorque(ActuatorId id, Vector3dc maxTorque, double momentumCapacity) {
        requireFinite(maxTorque, "maxTorque");
        if (!(momentumCapacity > 0.0D) || Double.isInfinite(momentumCapacity)) {
            throw new IllegalArgumentException("a stored-momentum device needs a finite positive "
                    + "capacity, got " + momentumCapacity);
        }
        return new Actuator(id, new Vector3d(), new Vector3d(), new Vector3d(maxTorque), -1.0D,
                momentumCapacity, Collections.<PropellantDemand>emptyList(), null, Double.NaN, null);
    }

    private static double leastForce(List<PropellantDemand> demands, double ambientPascals) {
        double force = demands.isEmpty() ? 0 : Double.POSITIVE_INFINITY;
        for (PropellantDemand d : demands) {
            force = Math.min(force, d.forceAt(ambientPascals));
        }
        return force;
    }

    /** What it takes at full throttle, one entry per regime it can run; empty for a device that throws no mass. */
    public List<PropellantDemand> demands() {
        return demands;
    }

    /**
     * The one regime this drive burns, or {@code null} when it has none or has not been fed yet (more
     * than one regime declared). A LIVE drive of a metered craft always has exactly one.
     */
    @Nullable
    public PropellantDemand feed() {
        return demands.size() == 1 ? demands.get(0) : null;
    }

    /**
     * This drive as it runs on what is aboard (C10 STAT-38): among the regimes every fluid of which
     * {@code aboard} accepts, the one with the least force at the pressure it was evaluated at — ties by
     * propellant name — as the drive's only regime. {@code null} when no regime is fed: the drive is
     * idle for want of propellant. A device that takes nothing is fed by nothing and returned as it is.
     */
    @Nullable
    public Actuator fedBy(Predicate<String> aboard) {
        if (demands.isEmpty()) {
            return this;
        }
        PropellantDemand chosen = null;
        double chosenForce = Double.POSITIVE_INFINITY;
        for (PropellantDemand d : demands) {
            boolean fed = true;
            for (String fluid : d.shares().keySet()) {
                fed &= aboard.test(fluid);
            }
            if (!fed) {
                continue;
            }
            double f = d.forceAt(ambientPascals);
            if (chosen == null || f < chosenForce
                    || (f == chosenForce && d.propellant().compareTo(chosen.propellant()) < 0)) {
                chosen = d;
                chosenForce = f;
            }
        }
        if (chosen == null) {
            return null;
        }
        return new Actuator(id, position, new Vector3d(direction).mul(chosenForce), maxTorque, minThrottle,
                momentumCapacity, Collections.singletonList(chosen), direction, ambientPascals, cruiseTwin);
    }

    public ActuatorId id() {
        return id;
    }

    /** For a boost column, the cruise column whose capacity it shares; {@code null} for any other device. */
    @Nullable
    public ActuatorId cruiseTwin() {
        return cruiseTwin;
    }

    /** Whether this is a boost column (STAT-42). */
    public boolean isBoost() {
        return cruiseTwin != null;
    }

    /** The outside pressure the force was evaluated at, Pa; NaN for a device whose force follows none. */
    public double ambientPascals() {
        return ambientPascals;
    }

    /** The lowest throttle: zero for a device that can only push, minus one for one that can turn both ways. */
    public double minThrottle() {
        return minThrottle;
    }

    /** Whether this device can hold its output indefinitely; a stored-momentum device cannot. */
    public boolean isSustained() {
        return Double.isInfinite(momentumCapacity);
    }

    /** The momentum it can store before it saturates, in N·m·s; infinite for a sustained device. */
    public double momentumCapacity() {
        return momentumCapacity;
    }

    /** The torque it produces at full throttle, in N·m, independent of where the centre of mass is. */
    public Vector3dc maxTorque() {
        return maxTorque;
    }

    /** The force it produces at full throttle, in newtons, at the pressure it was evaluated at. */
    public Vector3dc maxForce() {
        return maxForce;
    }

    /**
     * The six-component wrench per unit throttle about {@code centre}: force, then torque. The
     * torque of a point force is its lever arm crossed with it, which is the whole reason a hull's
     * geometry decides its authority.
     */
    double[] wrenchAbout(Vector3dc centre) {
        return wrenchOf(maxForce, centre);
    }

    /**
     * The same wrench with {@code ambientPascals} outside: a reaction drive's force follows each regime's
     * affine law at that pressure (STAT-31); any other device is unaffected.
     */
    double[] wrenchAtPressure(Vector3dc centre, double ambientPascals) {
        if (direction == null || ambientPascals == this.ambientPascals) {
            return wrenchAbout(centre);
        }
        return wrenchOf(new Vector3d(direction).mul(leastForce(demands, ambientPascals)), centre);
    }

    private double[] wrenchOf(Vector3dc force, Vector3dc centre) {
        Vector3d arm = new Vector3d(position).sub(centre);
        Vector3d torque = arm.cross(force, new Vector3d()).add(maxTorque);
        return new double[] {force.x(), force.y(), force.z(), torque.x, torque.y, torque.z};
    }

    /**
     * How far the pressure may move from the one this drive was evaluated at before its force stops
     * being affine in it — a regime that pushes reaching separation, or one past it starting to push;
     * infinite for a device that follows no pressure.
     */
    double affineRoom() {
        if (direction == null) {
            return Double.POSITIVE_INFINITY;
        }
        double room = Double.POSITIVE_INFINITY;
        for (PropellantDemand d : demands) {
            room = Math.min(room, Math.abs(d.separationPascals() - ambientPascals));
        }
        return room;
    }

    /** The largest pressure slope among its regimes, m²: how much force one pascal can move. */
    double exitArea() {
        double area = 0;
        for (PropellantDemand d : demands) {
            area = Math.max(area, d.exitArea());
        }
        return area;
    }

    /** Where it pushes from, ship frame. */
    Vector3dc position() {
        return position;
    }

    private static void requireFinite(Vector3dc v, String what) {
        if (!v.isFinite()) {
            throw new IllegalArgumentException(what + " is not finite: " + v);
        }
    }

    @Override
    public String toString() {
        return "Actuator" + id + (isSustained()
                ? "{force " + maxForce + " at " + position + (isBoost() ? ", boost" : "") + '}'
                : "{torque " + maxTorque + ", capacity " + momentumCapacity + '}');
    }
}
