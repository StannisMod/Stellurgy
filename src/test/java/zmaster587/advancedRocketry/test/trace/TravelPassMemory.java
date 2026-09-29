package zmaster587.advancedRocketry.test.trace;

import net.minecraft.entity.EntityLivingBase;

/**
 * What the {@code ShipFrameTravel} instruments relay between two seams of ONE side's pass — one per
 * side, kept in that side's {@link SideTrace}.
 *
 * <p>Production computes the facts a record needs in different places a few frames apart, and some
 * of those places have no entity in hand ({@code noteTickHistory}) or no body at all (the pose pass's
 * return). So an instrument writes a fact at the first seam and reads it at the second. Both seams
 * run on the side's own thread, and the client and the server resolve with the same code, so the
 * relay must be per SIDE — which is what the {@code ThreadLocal}s it replaces were standing in for,
 * held as statics for the life of the JVM.</p>
 *
 * <p>Test source set: absent from a released jar.</p>
 */
public final class TravelPassMemory {

    /** The body whose tick is being resolved right now — captured at {@code travel}'s HEAD, read at
     *  the entity-less per-tick seam a few frames later. */
    public EntityLivingBase resolving;

    /** Resolved ticks THIS side has produced, counted where production resolves one — the leading
     *  number of the tick line. */
    public long resolvedTicks;

    /**
     * The walk inputs and ship-frame motion of the tick being resolved:
     * {@code [strafe, forward, motionShipX, motionShipY, motionShipZ]}.
     *
     * <p><b>Its staleness is the production behaviour it replaces, not a new one.</b> Only the ABOARD
     * path computes a ship-frame motion; on a flying tick these five are the last aboard tick's.
     * Zeroes until the first aboard tick, which is a body that has not walked yet.</p>
     */
    public double[] walk = {0.0, 0.0, 0.0, 0.0, 0.0};

    /** The largest re-seat step of the pose pass currently running on this side. Released by
     *  {@link #takeReseatPassMax}, which every pass calls exactly once. */
    private double reseatPassMax;

    /** The side's memory. */
    public static TravelPassMemory of(SideTrace side) {
        return side.memory(TravelPassMemory.class, TravelPassMemory::new);
    }

    /** Note one re-seat: {@code seat} is where the body is being put, {@code from*} where it was.
     *  Zero on a still ship, one tick of ship motion at the body's radius on a moving one. */
    public void noteReseat(double seatX, double seatY, double seatZ,
                           double fromX, double fromY, double fromZ) {
        double dx = seatX - fromX;
        double dy = seatY - fromY;
        double dz = seatZ - fromZ;
        double step = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (step > reseatPassMax) {
            reseatPassMax = step;
        }
    }

    /** The largest step since the last call, and reset — read once per pass, by its recorder. */
    public double takeReseatPassMax() {
        double max = reseatPassMax;
        reseatPassMax = 0.0;
        return max;
    }
}
