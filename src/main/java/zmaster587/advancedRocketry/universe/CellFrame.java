package zmaster587.advancedRocketry.universe;

import net.minecraft.nbt.NBTTagCompound;

import zmaster587.advancedRocketry.space.AbsolutePos;
import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * The coordinate system a cell's contents live in — its ORIGIN as a function of world time. Every
 * cell that has a primary body rides that body; only a cell with none stands still.
 *
 * <p>A cell's origin is the position of its PRIMARY: the one real body the cell belongs to. That
 * position is the system anchor (which does not move) displaced by the primary's own orbital law, so
 * a frame is exactly two things: a static base and an ephemeris. A cell with no primary is VOID and
 * its frame is {@link #staticAt static} at {@code sector * CELL} — a degenerate frame, not an
 * exemption.</p>
 *
 * <p><b>Frames NEST.</b> A moon's cell rides the moon, and the moon rides its planet, so the moon's
 * frame is {@link #within its planet's} displaced by the moon's own law about it. Nesting is what
 * makes a craft parked beside a moon keep station for free: the craft does not move, its cell does —
 * the same thing that already held for a planet, one level down. A one-level frame could not express
 * it, and a craft beside a moon drifted 42 descent shells in a day.</p>
 *
 * <p>Immutable value type.</p>
 */
public final class CellFrame {

    private final CellFrame parent;
    private final AbsolutePos base;
    private final BodyEphemeris law;

    private CellFrame(CellFrame parent, AbsolutePos base, BodyEphemeris law) {
        this.parent = parent;
        this.base = base == null ? AbsolutePos.ORIGIN : base;
        this.law = law == null ? BodyEphemeris.STATIC : law;
    }

    /** The frame of a cell that does not move: origin {@code sector * CELL}, at every tick. */
    public static CellFrame staticAt(GalacticCoord name) {
        return new CellFrame(null, AbsolutePos.ofCellName(name), BodyEphemeris.STATIC);
    }

    /** A frame whose origin is {@code base} displaced by {@code law} — the primary's own motion. */
    public static CellFrame of(AbsolutePos base, BodyEphemeris law) {
        return new CellFrame(null, base, law);
    }

    /**
     * A frame nested INSIDE {@code parent}: its origin is the parent's origin at that tick, displaced
     * by {@code law} — this body's own motion about the body the parent frame is centred on.
     *
     * <p>The parent's origin is taken AT THE TICK, not once: the whole difference between this and
     * {@link #of(AbsolutePos, BodyEphemeris)} is that the thing being displaced from is itself
     * moving. Building a moon's frame with {@code of(parentOriginAtSomeTick, moonLaw)} compiles, reads
     * the same, and pins the moon to wherever its planet happened to be at that one instant.</p>
     */
    public static CellFrame within(CellFrame parent, BodyEphemeris law) {
        if (parent == null) {
            throw new NullPointerException("parent");
        }
        return new CellFrame(parent, parent.base, law);
    }

    /** Where this frame's origin is, absolutely, at world tick {@code tick}. */
    public AbsolutePos originAt(long tick) {
        return (parent == null ? base : parent.originAt(tick)).plus(law.offsetAt(tick));
    }

    /**
     * The static base this frame is displaced from — the system anchor, in absolute blocks. The same
     * anchor for every level of a nest: what a nested frame adds is motion, never a second origin.
     */
    public AbsolutePos base() {
        return base;
    }

    /** The frame this one is nested in, or {@code null} when it stands on a static base. */
    public CellFrame parent() {
        return parent;
    }

    /** The primary's displacement law. {@link BodyEphemeris#STATIC} for a void cell. */
    public BodyEphemeris law() {
        return law;
    }

    /** {@code true} iff this frame's origin is the same at every tick (a void cell, or a star). */
    public boolean isStatic() {
        return law.isStatic() && (parent == null || parent.isStatic());
    }

    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound sub = new NBTTagCompound();
        if (parent != null) {
            // Recursive, and it has to be: a nested frame's origin is its parent's AT THE TICK, so a
            // read-back that dropped the parent would keep the law and lose what it displaces from —
            // a moon pinned to its system's anchor, orbiting a planet that is not there.
            NBTTagCompound parentTag = new NBTTagCompound();
            parent.writeToNBT(parentTag);
            sub.setTag("parent", parentTag.getCompoundTag("frame"));
        }
        // The base is written as a sector triple plus an in-cell offset, for the same reason the type
        // holds one: a single block absolute cannot express the coordinates the sector grid can name.
        sub.setLong("bsx", base.sectorX());
        sub.setLong("bsy", base.sectorY());
        sub.setLong("bsz", base.sectorZ());
        sub.setLong("blx", base.localX());
        sub.setLong("bly", base.localY());
        sub.setLong("blz", base.localZ());
        law.writeToNBT(sub); // nested sub-tag "ephemeris"
        nbt.setTag("frame", sub);
    }

    /**
     * Read a frame written by {@link #writeToNBT}. When the sub-tag is absent the caller's
     * {@code name} supplies a static frame — that is the honest default for a body whose frame was
     * never recorded, and it is what a void cell means.
     */
    public static CellFrame readFromNBT(NBTTagCompound nbt, GalacticCoord name) {
        if (nbt == null || !nbt.hasKey("frame")) {
            return staticAt(name);
        }
        return fromTag(nbt.getCompoundTag("frame"));
    }

    /** One level of a written frame, and its parent chain. */
    private static CellFrame fromTag(NBTTagCompound sub) {
        CellFrame parent = sub.hasKey("parent") ? fromTag(sub.getCompoundTag("parent")) : null;
        return new CellFrame(parent, AbsolutePos.ofSectorLocal(
                sub.getLong("bsx"), sub.getLong("bsy"), sub.getLong("bsz"),
                sub.getLong("blx"), sub.getLong("bly"), sub.getLong("blz")),
                BodyEphemeris.readFromNBT(sub));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CellFrame)) {
            return false;
        }
        CellFrame other = (CellFrame) o;
        return base.equals(other.base) && law.equals(other.law)
                && (parent == null ? other.parent == null : parent.equals(other.parent));
    }

    @Override
    public int hashCode() {
        return 31 * (31 * base.hashCode() + law.hashCode()) + (parent == null ? 0 : parent.hashCode());
    }

    @Override
    public String toString() {
        return "CellFrame[" + (parent == null ? "base=" + base : "within=" + parent)
                + ", law=" + law + "]";
    }
}
