package dev.stannismod.stellurgy.ship.guidance;

import dev.stannismod.stellurgy.space.CellSeam;
import dev.stannismod.stellurgy.space.GalacticCoord;
import net.minecraft.nbt.NBTTagCompound;

import java.util.UUID;

/**
 * What a motion intent wants the craft to go to: a THING, never a bare point of space.
 *
 * <p>Two kinds:</p>
 * <ul>
 *   <li><b>A ship</b>, named by its durable id. Where it is is asked afresh on every tick, wherever it
 *       has gone — so a target that moves is followed, and one in a neighbouring cell is reached by
 *       flying across the face. The craft is never sent INTO it: it is kept outside both hulls.</li>
 *   <li><b>A point on a planet's surface</b>, named by the planet's world and a column {@code (x, z)}.
 *       The height is the ground's, read on every tick. Going to one is landing: the craft ends
 *       resting on the ground at that point.</li>
 * </ul>
 *
 * <p>A target in another world than the craft's — a planet seen from space, space from a planet, a
 * ship in hyperspace — is a request for a crossing, and is answered as such, never as "unreachable".</p>
 *
 * <p>Immutable.</p>
 */
public final class GuidanceTarget {

    /** Why a target cannot be placed in the world the craft is flying in right now. */
    public enum Unplaced {
        /** The target is in another world; getting to it takes a crossing, not a flight. */
        NEEDS_A_CROSSING,
        /** No craft by that name is in this world, in space, or in any loaded world. */
        NOT_FOUND,
        /** The target names the craft itself. */
        IS_THE_CRAFT,
        /**
         * The craft is in space but the target cannot be placed against its cell's frame: a cell whose
         * lattice width is unknown, or a separation past what a block vector can hold.
         */
        NO_FRAME,
        /**
         * The CRAFT's own hull cannot be measured, so no distance outside it can be derived. A target
         * ship's hull that cannot be measured is not this: it is approached (see {@link #placeIn}).
         */
        NOT_MEASURED
    }

    /**
     * The first whole-block offset from a cell's centre, along one axis, at which the seam carries a
     * craft into the neighbour: {@code CellSeam.shouldCarry} carries an offset strictly past
     * {@code HALF_CELL + CARRY_MARGIN}, and an offset is a pose rounded to the block.
     */
    private static final long FIRST_CARRIED = GalacticCoord.HALF_CELL + CellSeam.CARRY_MARGIN + 1L;

    private static final String NBT_KIND = "kind";
    private static final String KIND_SHIP = "ship";
    private static final String KIND_SURFACE = "surface";
    private static final String NBT_SHIP = "ship";
    private static final String NBT_DIM = "dim";
    private static final String NBT_X = "x";
    private static final String NBT_Z = "z";

    /** The ship aimed at; {@code null} for a surface point. */
    private final UUID ship;
    private final int dimension;
    private final double x;
    private final double z;

    private GuidanceTarget(UUID ship, int dimension, double x, double z) {
        this.ship = ship;
        this.dimension = dimension;
        this.x = x;
        this.z = z;
    }

    /** The ship whose durable id is {@code shipId}. */
    public static GuidanceTarget ship(UUID shipId) {
        if (shipId == null) {
            throw new IllegalArgumentException("a ship target names a ship");
        }
        return new GuidanceTarget(shipId, 0, 0.0D, 0.0D);
    }

    /** The point of the surface of the planet world {@code dimension} above the column {@code (x, z)}. */
    public static GuidanceTarget surface(int dimension, double x, double z) {
        if (Double.isNaN(x) || Double.isInfinite(x) || Double.isNaN(z) || Double.isInfinite(z)) {
            throw new IllegalArgumentException("a surface point is a finite column; got " + x + ", " + z);
        }
        return new GuidanceTarget(null, dimension, x, z);
    }

    /** Whether going to this target means landing on it. */
    boolean isSurface() {
        return ship == null;
    }

    /**
     * Where this target stands, as seen from {@code craft} — world frame of the craft's world, blocks —
     * or why it cannot be placed there.
     *
     * <p>A ship answers its position and the distance the craft's position must keep from it so the
     * two hulls cannot touch: the sum of the radii of the two spheres about the ships' positions that
     * hold their hulls. A surface point answers the position the craft's own position has when its
     * lowest point is pressed half of {@link MotionSlot#TOUCHDOWN} into the highest ground under its
     * footprint there.</p>
     *
     * <p>A ship whose hull cannot be measured is placed all the same, PROVISIONALLY, with no keep-out at
     * all: no hull of it is in the craft's world to keep out of. That is a ship in another space cell's
     * world — then the craft must cross into that cell to reach it, so the aim is the ship's position
     * carried past the seam's carry line on every axis along which its cell lies beyond the craft's
     * ({@link #throughTheSeam}), and flying to it carries the craft over — or a ship of the craft's own
     * world whose record holds no blocks. The craft is never counted as arrived against a provisional
     * placement; once the hull can be measured, at the latest when the craft has crossed into its world,
     * the real keep-out takes over on that tick.</p>
     */
    Placement placeIn(MotionSlot.Craft craft, Surroundings around) {
        if (ship != null) {
            if (ship.equals(craft.id)) {
                return Placement.unplaced(Unplaced.IS_THE_CRAFT);
            }
            Surroundings.Sighting seen = around.ship(ship);
            if (seen.unplaced() != null) {
                return Placement.unplaced(seen.unplaced());
            }
            if (!(craft.hullRadius >= 0.0D)) {
                return Placement.unplaced(Unplaced.NOT_MEASURED);
            }
            if (!seen.measured()) {
                return Placement.provisional(seen.cell() == null ? seen.position()
                        : throughTheSeam(craft.cell, seen.cell(), seen.position()));
            }
            return Placement.at(seen.position(), craft.hullRadius + seen.hullRadius());
        }
        if (craft.cell != null || craft.dimension != dimension) {
            return Placement.unplaced(Unplaced.NEEDS_A_CROSSING);
        }
        if (!(craft.hullRadius >= 0.0D) || Double.isNaN(craft.hullDepthBelow)) {
            return Placement.unplaced(Unplaced.NOT_MEASURED);
        }
        double ground = around.groundTop(x, z, craft.hullRadius);
        // Half the touchdown distance INTO the ground: the craft can then only be near enough its aim to
        // count as down once the ground has stopped it, so a landing is released onto the ground, never
        // dropped onto it from a hover. Over it, the point where the sphere holding the hull just clears
        // the ground: no attitude puts any of the hull into the ground there.
        double clear = ground + craft.hullRadius;
        return Placement.landing(new double[]{x, ground + craft.hullDepthBelow - MotionSlot.TOUCHDOWN / 2.0D, z},
                new double[]{x, clear, z});
    }

    /**
     * Where a craft in the cell {@code from} makes for to reach a ship at {@code position} (the craft's
     * world frame, whose origin is its cell's centre) in the world of the cell {@code to}: the position
     * itself, except along an axis on which {@code to} lies beyond {@code from} in the same lattice,
     * where it is taken at least to {@link #FIRST_CARRIED} on that side. A ship parked just past the face
     * between the two — inside the band in which the seam does not yet carry a craft — would otherwise be
     * an aim reached without crossing, in a world the ship is not in. Across lattices (another zone) the
     * position is answered unchanged: that boundary is a sphere, and this does not plan for it.
     */
    private static double[] throughTheSeam(GalacticCoord from, GalacticCoord to, double[] position) {
        double[] aim = position.clone();
        if (from == null || !java.util.Objects.equals(from.zone(), to.zone())) {
            return aim;
        }
        long[] steps = {Long.signum(to.sectorX() - from.sectorX()), Long.signum(to.sectorY() - from.sectorY()),
                Long.signum(to.sectorZ() - from.sectorZ())};
        for (int i = 0; i < 3; i++) {
            if (steps[i] > 0L) {
                aim[i] = Math.max(aim[i], FIRST_CARRIED);
            } else if (steps[i] < 0L) {
                aim[i] = Math.min(aim[i], -FIRST_CARRIED);
            }
        }
        return aim;
    }

    void writeToNBT(NBTTagCompound nbt) {
        if (ship != null) {
            nbt.setString(NBT_KIND, KIND_SHIP);
            nbt.setString(NBT_SHIP, ship.toString());
        } else {
            nbt.setString(NBT_KIND, KIND_SURFACE);
            nbt.setInteger(NBT_DIM, dimension);
            nbt.setDouble(NBT_X, x);
            nbt.setDouble(NBT_Z, z);
        }
    }

    /** The target written by {@link #writeToNBT}, or {@code null} when {@code nbt} holds none. */
    static GuidanceTarget readFromNBT(NBTTagCompound nbt) {
        String kind = nbt.getString(NBT_KIND);
        try {
            if (KIND_SHIP.equals(kind)) {
                return ship(UUID.fromString(nbt.getString(NBT_SHIP)));
            }
            if (KIND_SURFACE.equals(kind)) {
                return surface(nbt.getInteger(NBT_DIM), nbt.getDouble(NBT_X), nbt.getDouble(NBT_Z));
            }
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        return null;
    }

    @Override
    public String toString() {
        return ship != null ? "ship " + ship : "surface " + dimension + " (" + x + "," + z + ")";
    }

    /** A target placed in the craft's world, or the reason it could not be. */
    static final class Placement {

        private final double[] position;
        private final double keepOut;
        private final boolean provisional;
        private final double[] over;
        private final Unplaced unplaced;

        private Placement(double[] position, double keepOut, boolean provisional, double[] over, Unplaced unplaced) {
            this.position = position;
            this.keepOut = keepOut;
            this.provisional = provisional;
            this.over = over;
            this.unplaced = unplaced;
        }

        static Placement at(double[] position, double keepOut) {
            return new Placement(position.clone(), keepOut, false, null, null);
        }

        /** An aim at {@code position} for a ship whose hull nobody here could measure: no keep-out. */
        static Placement provisional(double[] position) {
            return new Placement(position.clone(), 0.0D, true, null, null);
        }

        /** A landing at {@code position}, let down from {@code over}, the point above it clear of the ground. */
        static Placement landing(double[] position, double[] over) {
            return new Placement(position.clone(), 0.0D, false, over.clone(), null);
        }

        static Placement unplaced(Unplaced why) {
            return new Placement(null, 0.0D, false, null, why);
        }

        /** The point a landing is let down from; {@code null} for anything else. */
        double[] over() {
            return over == null ? null : over.clone();
        }

        /** Whether no hull was measured: the position is only made for, and reaching it is not arrival. */
        boolean provisional() {
            return provisional;
        }

        /** World frame, blocks; {@code null} when the target could not be placed. */
        double[] position() {
            return position == null ? null : position.clone();
        }

        /** How near the craft's position may come to {@link #position} before the hulls could touch. */
        double keepOut() {
            return keepOut;
        }

        Unplaced unplaced() {
            return unplaced;
        }
    }
}
