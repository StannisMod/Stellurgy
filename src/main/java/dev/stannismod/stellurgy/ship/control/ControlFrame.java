package dev.stannismod.stellurgy.ship.control;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The pilot's frame, expressed in the ship's own frame: which way is forward, right and up for the
 * person at the helm.
 *
 * <p>Authority is measured against this frame and not against the ship's block axes, because
 * "forward" is where the flight computer faces, and a hull is built around its helm, not around
 * north.</p>
 */
public final class ControlFrame {

    private final Vector3dc forward;
    private final Vector3dc right;
    private final Vector3dc up;

    private ControlFrame(Vector3dc forward, Vector3dc right, Vector3dc up) {
        this.forward = forward;
        this.right = right;
        this.up = up;
    }

    /**
     * The frame whose forward, right and up are the three given ship-frame vectors.
     *
     * <p>All three are given, none derived: which way "right" points is the pilot convention of the
     * flight law that feeds this frame, and deriving it here from a cross product would be a second
     * copy of that convention free to disagree with the first.</p>
     *
     * @throws IllegalArgumentException when the three are not unit length and mutually perpendicular —
     *         a skewed frame would let one axis's authority leak into another's
     */
    public static ControlFrame of(Vector3dc forward, Vector3dc right, Vector3dc up) {
        if (!unit(forward) || !unit(right) || !unit(up)
                || Math.abs(forward.dot(up)) > 1.0e-9D || Math.abs(forward.dot(right)) > 1.0e-9D
                || Math.abs(right.dot(up)) > 1.0e-9D) {
            throw new IllegalArgumentException("forward " + forward + ", right " + right + " and up "
                    + up + " are not orthonormal");
        }
        return new ControlFrame(new Vector3d(forward), new Vector3d(right), new Vector3d(up));
    }

    private static boolean unit(Vector3dc v) {
        return Math.abs(v.length() - 1.0D) <= 1.0e-9D;
    }

    /** The unit vector, in the ship frame, that {@code axis} translates along or rotates about. */
    public Vector3dc axis(ControlAxis axis) {
        switch (axis) {
            case SURGE:
            case ROLL:
                return forward;
            case SWAY:
            case PITCH:
                return right;
            case HEAVE:
            case YAW:
                return up;
            default:
                throw new IllegalStateException(String.valueOf(axis));
        }
    }

    @Override
    public String toString() {
        return "ControlFrame{forward=" + forward + ", right=" + right + ", up=" + up + '}';
    }
}
