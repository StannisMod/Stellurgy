package dev.stannismod.stellurgy.ship.control;

import org.joml.Vector3d;
import org.joml.Vector3dc;

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
 * <p>Every quantity is SI in the ship's own frame: newtons, newton-metres, newton-metre-seconds,
 * and positions in blocks, which are metres.</p>
 */
public final class Actuator {

    private final ActuatorId id;
    private final Vector3dc position;
    private final Vector3dc maxForce;
    private final Vector3dc maxTorque;
    private final double minThrottle;
    private final double momentumCapacity;

    private Actuator(ActuatorId id, Vector3dc position, Vector3dc maxForce, Vector3dc maxTorque,
                     double minThrottle, double momentumCapacity) {
        this.id = id;
        this.position = position;
        this.maxForce = maxForce;
        this.maxTorque = maxTorque;
        this.minThrottle = minThrottle;
        this.momentumCapacity = momentumCapacity;
    }

    /**
     * A force applied at a point, which may only push: throttle from zero to one. It produces torque
     * about the centre of mass through its lever arm, which is why where it is built matters.
     * Holdable indefinitely.
     *
     * @param maxForce the force at full throttle, in newtons; its direction is the push on the hull
     */
    public static Actuator pointForce(ActuatorId id, Vector3dc position, Vector3dc maxForce) {
        requireFinite(position, "position");
        requireFinite(maxForce, "maxForce");
        return new Actuator(id, new Vector3d(position), new Vector3d(maxForce), new Vector3d(), 0.0D,
                Double.POSITIVE_INFINITY);
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
                momentumCapacity);
    }

    public ActuatorId id() {
        return id;
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

    /** The force it produces at full throttle, in newtons. */
    public Vector3dc maxForce() {
        return maxForce;
    }

    /**
     * The six-component wrench per unit throttle about {@code centre}: force, then torque. The
     * torque of a point force is its lever arm crossed with it, which is the whole reason a hull's
     * geometry decides its authority.
     */
    double[] wrenchAbout(Vector3dc centre) {
        Vector3d arm = new Vector3d(position).sub(centre);
        Vector3d torque = arm.cross(maxForce, new Vector3d()).add(maxTorque);
        return new double[] {maxForce.x(), maxForce.y(), maxForce.z(), torque.x, torque.y, torque.z};
    }

    private static void requireFinite(Vector3dc v, String what) {
        if (!v.isFinite()) {
            throw new IllegalArgumentException(what + " is not finite: " + v);
        }
    }

    @Override
    public String toString() {
        return "Actuator" + id + (isSustained()
                ? "{force " + maxForce + " at " + position + '}'
                : "{torque " + maxTorque + ", capacity " + momentumCapacity + '}');
    }
}
