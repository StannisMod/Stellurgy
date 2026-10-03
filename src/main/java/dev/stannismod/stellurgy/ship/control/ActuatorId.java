package dev.stannismod.stellurgy.ship.control;

/**
 * The stable identity of one actuator: the block it lives in, plus its index among that block's
 * actuators (a reaction wheel is three, one per axis).
 *
 * <p>It exists to ORDER actuators. The allocation is deterministic only for a fixed column order,
 * and a position is the one identity that is the same on every load, on both sides of the
 * connection, and independent of the order a hull walk happens to visit blocks in.</p>
 */
public final class ActuatorId implements Comparable<ActuatorId> {

    private final int x;
    private final int y;
    private final int z;
    private final int index;

    public ActuatorId(int x, int y, int z, int index) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.index = index;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    /** Which of its block's actuators this is; a device that keeps state per actuator indexes it by this. */
    public int index() {
        return index;
    }

    @Override
    public int compareTo(ActuatorId o) {
        if (x != o.x) {
            return Integer.compare(x, o.x);
        }
        if (y != o.y) {
            return Integer.compare(y, o.y);
        }
        if (z != o.z) {
            return Integer.compare(z, o.z);
        }
        return Integer.compare(index, o.index);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ActuatorId)) {
            return false;
        }
        ActuatorId other = (ActuatorId) o;
        return x == other.x && y == other.y && z == other.z && index == other.index;
    }

    @Override
    public int hashCode() {
        return ((x * 31 + y) * 31 + z) * 31 + index;
    }

    @Override
    public String toString() {
        return "#" + x + "," + y + "," + z + (index == 0 ? "" : "/" + index);
    }
}
