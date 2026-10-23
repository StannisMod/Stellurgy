package dev.stannismod.stellurgy.ship.guidance;

import net.minecraft.nbt.NBTTagCompound;

/**
 * One order for a craft's MOTION — what a motion slot holds and its guidance flies.
 *
 * <p>An intent states the geometry it wants and never the path: {@link #goTo} a thing, {@link #hold}
 * the craft within a distance of one. How the craft gets there — how fast, which way it points, when
 * it brakes — is guidance's, worked out on every tick from the hull's own authority, so a heavy or
 * one-sided hull flies the same order worse rather than differently.</p>
 *
 * <p>Immutable.</p>
 */
public final class MotionIntent {

    /** The verbs a motion slot can hold. */
    public enum Kind {
        /** Fly to the thing and stay there: alongside a ship, or landed on a surface point. */
        GO_TO,
        /** Keep the craft within {@link #range()} of the thing, never inside a ship's hull. */
        HOLD
    }

    private static final String NBT_KIND = "kind";
    private static final String NBT_RANGE = "range";
    private static final String NBT_TARGET = "target";

    private final Kind kind;
    private final GuidanceTarget target;
    private final double range;

    private MotionIntent(Kind kind, GuidanceTarget target, double range) {
        this.kind = kind;
        this.target = target;
        this.range = range;
    }

    /** Fly to {@code target} and stay there. */
    public static MotionIntent goTo(GuidanceTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("a GO_TO needs a target");
        }
        return new MotionIntent(Kind.GO_TO, target, 0.0D);
    }

    /**
     * Keep the craft within {@code range} blocks of {@code target}.
     *
     * @throws IllegalArgumentException for a negative or non-finite range
     */
    public static MotionIntent hold(GuidanceTarget target, double range) {
        if (target == null) {
            throw new IllegalArgumentException("a HOLD needs a target");
        }
        if (!(range >= 0.0D) || Double.isInfinite(range)) {
            throw new IllegalArgumentException("a HOLD's range must be a finite distance; got " + range);
        }
        return new MotionIntent(Kind.HOLD, target, range);
    }

    public Kind kind() {
        return kind;
    }

    public GuidanceTarget target() {
        return target;
    }

    /** The distance a HOLD keeps the craft within, blocks; zero for a GO_TO. */
    public double range() {
        return range;
    }

    /** Whether this order is a landing: a GO_TO a surface point. */
    boolean isLanding() {
        return kind == Kind.GO_TO && target.isSurface();
    }

    /**
     * The point the craft should make for from {@code position}, world frame, blocks.
     *
     * <p>The geometry is a SHELL about the placed target: nothing nearer than its keep-out distance
     * plus {@code clearance} (a ship's two hulls, plus the margin an arrival is judged within, so even
     * an arrival at its far edge leaves the hulls apart — zero keep-out for a surface point; an orbit's
     * sphere is its keep-out alone, with no hull to keep from), and
     * nothing farther than that for a GO_TO, or than the larger of that and the range for a HOLD. The
     * aim is the craft's own position while it is inside the shell, otherwise the nearest point of the
     * shell along the line to the target. A craft exactly on the target is sent straight up out of it.</p>
     */
    double[] aimFrom(double[] position, GuidanceTarget.Placement placed, double clearance) {
        double[] target = placed.position();
        double inner = !(placed.keepOut() > 0.0D) ? 0.0D
                : placed.hullsApart() ? placed.keepOut() + clearance : placed.keepOut();
        double outer = kind == Kind.GO_TO ? inner : Math.max(inner, range);
        double dx = position[0] - target[0];
        double dy = position[1] - target[1];
        double dz = position[2] - target[2];
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d >= inner && d <= outer) {
            return position.clone();
        }
        if (d == 0.0D) {
            return new double[]{target[0], target[1] + inner, target[2]};
        }
        double s = (d < inner ? inner : outer) / d;
        return new double[]{target[0] + dx * s, target[1] + dy * s, target[2] + dz * s};
    }

    void writeToNBT(NBTTagCompound nbt) {
        nbt.setString(NBT_KIND, kind.name());
        nbt.setDouble(NBT_RANGE, range);
        NBTTagCompound t = new NBTTagCompound();
        target.writeToNBT(t);
        nbt.setTag(NBT_TARGET, t);
    }

    /** The intent written by {@link #writeToNBT}, or {@code null} when {@code nbt} holds none. */
    static MotionIntent readFromNBT(NBTTagCompound nbt) {
        Kind kind;
        try {
            kind = Kind.valueOf(nbt.getString(NBT_KIND));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
        GuidanceTarget target = GuidanceTarget.readFromNBT(nbt.getCompoundTag(NBT_TARGET));
        if (target == null) {
            return null;
        }
        try {
            return kind == Kind.GO_TO ? goTo(target) : hold(target, nbt.getDouble(NBT_RANGE));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    @Override
    public String toString() {
        return kind == Kind.GO_TO ? "GO_TO " + target : "HOLD " + target + " within " + range;
    }
}
