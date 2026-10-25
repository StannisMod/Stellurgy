package dev.stannismod.stellurgy.ship.guidance;

import dev.stannismod.stellurgy.space.CellSeam;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.space.ShipEntryController;
import dev.stannismod.stellurgy.space.StandoffRing;
import net.minecraft.nbt.NBTTagCompound;

import java.util.UUID;

/**
 * What a motion intent wants the craft to go to: a THING, never a bare point of space.
 *
 * <p>Three kinds:</p>
 * <ul>
 *   <li><b>A ship</b>, named by its durable id. Where it is is asked afresh on every tick, wherever it
 *       has gone — so a target that moves is followed, and one in a neighbouring cell is reached by
 *       flying across the face. The craft is never sent INTO it: it is kept outside both hulls.</li>
 *   <li><b>A point on a planet's surface</b>, named by the planet's world and a column {@code (x, z)}.
 *       The height is the ground's, read on every tick. Going to one is landing: the craft ends
 *       resting on the ground at that point.</li>
 *   <li><b>A body's orbit</b>, named by the planet world that is the body's surface. In space it is the
 *       sphere about the body at the body's entry ring; the craft is sent to the nearest point of it and
 *       stays there. From the body's own surface it is reached by climbing STRAIGHT UP past the world's
 *       orbit line, where the entry crossing takes the craft into space — the climb is made for, never
 *       arrived at, and is refused while anything solid stands in the column above the craft.</li>
 * </ul>
 *
 * <h2>A target in another world: the legs</h2>
 *
 * <p>A target in another world than the craft's is reached by flying the crossings between, along ONE
 * fixed chain — up out of a planet's world, a jump if the target is in another system, through space,
 * down into a planet's world — and which link of it the craft is on is derived afresh on every tick from
 * where the craft is against where the target is, never stored: the order itself rides every crossing
 * unchanged, and the leg after a crossing is simply what the new world answers.</p>
 * <ul>
 *   <li>On a planet, a target that is not in the planet's world — in space, on another body, in another
 *       system — is the CLIMB: straight up past the world's orbit line, as for the body's own orbit, where
 *       the entry crossing takes the craft into space.</li>
 *   <li>In space, a target in another system — a place in the void outside every system's surroundings
 *       being a system of its own — is the JUMP: the leg names where the jump is aimed, and the flight
 *       computer arms and commits it. A ship is jumped to beside it, the standoff an arrival keeps (the two
 *       hulls and the arrival radius) off its address; a body, at the body, which a jump's arrival stands
 *       off by itself. After the arrival the target is in the craft's system.</li>
 *   <li>In space, a target in a planet's world — a point of its surface, a ship standing there — is the
 *       way INTO THE SHELL: the craft is sent to just inside that body's descent shell. It is never sent
 *       down: entering a body's shell is what descends ANY craft onto it, and that one condition decides
 *       the descent here as it does for a pilot. After the descent the target is in the craft's world.</li>
 *   <li>A ship in the middle of a jump is refused as being in hyperspace.</li>
 * </ul>
 *
 * <p>Immutable.</p>
 */
public final class GuidanceTarget {

    /** Why a target cannot be placed in the world the craft is flying in right now. */
    public enum Unplaced {
        /** The target ship is in hyperspace, mid-jump: it is in no system until its jump ends. */
        IN_HYPERSPACE,
        /**
         * The target ship is in another system and its hull cannot be measured where it is, so no point
         * beside it — outside both hulls — can be named for the jump to come out at.
         */
        JUMP_TARGET_NOT_MEASURED,
        /**
         * The target is nowhere: no craft by that name is in this world, in space, or in any loaded world
         * that is a body's surface; or no body of the universe has that planet world for its surface.
         */
        NOT_FOUND,
        /** The target names the craft itself. */
        IS_THE_CRAFT,
        /**
         * The craft is in space but the target cannot be placed against its cell's frame: a cell whose
         * lattice width is unknown, or a separation past what a block vector can hold; or the universe
         * cannot be asked which system a place is in.
         */
        NO_FRAME,
        /**
         * The CRAFT's own hull cannot be measured, so no distance outside it can be derived. A target
         * ship's hull that cannot be measured is not this: it is approached (see {@link #placeIn}).
         */
        NOT_MEASURED,
        /** The craft's world has no orbit line: no climb, however high, leaves it for space. */
        NO_ORBIT_LINE,
        /** Something solid stands in the column above the craft, between it and its world's orbit line. */
        PATH_BLOCKED
    }

    /**
     * How far past its world's orbit line a climbing craft is aimed, blocks: half the room the entry
     * line is kept below the physics substrate's altitude clamp
     * ({@link ShipEntryController#PHYSICS_CLAMP_ENTRY_MARGIN}), so the aim is reachable whatever the clamp,
     * and the line is crossed that far before the aim, while the approach is still closing.
     */
    static final double CLIMB_PAST_THE_LINE = ShipEntryController.PHYSICS_CLAMP_ENTRY_MARGIN / 2.0D;

    /**
     * How far inside a body's descent shell a craft bound for its world is aimed, blocks: the arrival
     * radius — so the shell is crossed while the craft still closes at the close-in law's pace
     * ({@code POSITION_GAIN} per block left), not in the slow tail of it — plus two blocks, more than the
     * √3 by which the position the descent condition measures (the craft's ledger position, each axis
     * rounded to the block) can lie off the craft's pose.
     */
    static final double INTO_THE_SHELL = MotionSlot.ARRIVAL_RADIUS + 2.0D;

    /**
     * How far beyond the standoff a jump to a ship comes out, blocks: three arrival radii — past the
     * arrival radius about the aim, past the √2 a ring point's whole-block rounding can bring it nearer,
     * and past the 1.25 to 1.7 blocks a hull pasted at a point was measured to lie off it once its physics
     * stepped (2026-10-25, logs/t616b-gate.log) — so the craft comes out OUTSIDE the arrival radius and
     * its arrival is flown, never declared on the tick it comes out at rest wherever it was put.
     */
    static final double OUT_OF_THE_JUMP_PAST_THE_STANDOFF = 3.0D * MotionSlot.ARRIVAL_RADIUS;

    /**
     * The first whole-block offset from a cell's centre, along one axis, at which the seam carries a
     * craft into the neighbour: {@code CellSeam.shouldCarry} carries an offset strictly past
     * {@code HALF_CELL + CARRY_MARGIN}, and an offset is a pose rounded to the block.
     */
    private static final long FIRST_CARRIED = GalacticCoord.HALF_CELL + CellSeam.CARRY_MARGIN + 1L;

    private static final String NBT_KIND = "kind";
    private static final String KIND_SHIP = "ship";
    private static final String KIND_SURFACE = "surface";
    private static final String KIND_ORBIT = "orbit";
    private static final String NBT_SHIP = "ship";
    private static final String NBT_DIM = "dim";
    private static final String NBT_X = "x";
    private static final String NBT_Z = "z";

    /** The kinds of thing a target names. */
    private enum Kind {
        SHIP,
        SURFACE,
        ORBIT
    }

    private final Kind kind;
    /** The ship aimed at; {@code null} unless a ship is. */
    private final UUID ship;
    /** The planet world of a surface point, or of the body whose orbit is aimed at. */
    private final int dimension;
    private final double x;
    private final double z;

    private GuidanceTarget(Kind kind, UUID ship, int dimension, double x, double z) {
        this.kind = kind;
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
        return new GuidanceTarget(Kind.SHIP, shipId, 0, 0.0D, 0.0D);
    }

    /** The point of the surface of the planet world {@code dimension} above the column {@code (x, z)}. */
    public static GuidanceTarget surface(int dimension, double x, double z) {
        if (Double.isNaN(x) || Double.isInfinite(x) || Double.isNaN(z) || Double.isInfinite(z)) {
            throw new IllegalArgumentException("a surface point is a finite column; got " + x + ", " + z);
        }
        return new GuidanceTarget(Kind.SURFACE, null, dimension, x, z);
    }

    /** The orbit of the body whose surface is the planet world {@code dimension}. */
    public static GuidanceTarget orbit(int dimension) {
        return new GuidanceTarget(Kind.ORBIT, null, dimension, 0.0D, 0.0D);
    }

    /** Whether going to this target means landing on it. */
    boolean isSurface() {
        return kind == Kind.SURFACE;
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
     *
     * <p>A target in another world is placed as the leg toward it (the class comment): from a planet the
     * climb ({@link #climb}); from space, in another system the jump ({@link #jumpBeside}, or the body's
     * own jump), in a planet's world the way into the shell of the body whose world it is in
     * ({@link #intoTheShell}).</p>
     */
    Placement placeIn(MotionSlot.Craft craft, Surroundings around) {
        if (kind == Kind.ORBIT) {
            return orbitIn(craft, around);
        }
        if (kind == Kind.SHIP) {
            if (ship.equals(craft.id)) {
                return Placement.unplaced(Unplaced.IS_THE_CRAFT);
            }
            Surroundings.Sighting seen = around.ship(ship);
            if (seen.unplaced() != null) {
                return Placement.unplaced(seen.unplaced());
            }
            if (seen.elsewhere() != null) {
                return craft.cell != null ? jumpBeside(seen, craft) : climb(craft, around);
            }
            if (seen.isInSpace()) {
                return climb(craft, around);
            }
            if (seen.planet() != null) {
                return craft.cell != null ? intoTheShell(seen.planet(), craft, around) : climb(craft, around);
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
        if (craft.cell != null) {
            return intoTheShell(dimension, craft, around);
        }
        if (craft.dimension != dimension) {
            return outOfThisWorldFor(dimension, craft, around);
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
     * Where this orbit stands as seen from {@code craft}. In space: the sphere {@link Surroundings#orbit}
     * answers, which the craft is sent to the nearest point of — or, for a body of another system, the
     * jump to it. On a planet — the body's own, or another — the climb ({@link #climb}).
     */
    private Placement orbitIn(MotionSlot.Craft craft, Surroundings around) {
        if (craft.cell != null) {
            Surroundings.Sphere seen = around.orbit(dimension);
            if (seen.unplaced() != null) {
                return Placement.unplaced(seen.unplaced());
            }
            if (seen.elsewhere() != null) {
                return Placement.jump(seen.elsewhere());
            }
            return Placement.sphere(seen.centre(), seen.radius());
        }
        if (craft.dimension != dimension) {
            return outOfThisWorldFor(dimension, craft, around);
        }
        return climb(craft, around);
    }

    /**
     * From a planet, the way to the body whose surface is the planet world {@code target}: the climb
     * ({@link #climb}) — in the craft's system or another, the way to it starts in space — unless no body
     * has that world for its surface.
     */
    private static Placement outOfThisWorldFor(int target, MotionSlot.Craft craft, Surroundings around) {
        Unplaced why = around.unreachable(target);
        return why != null ? Placement.unplaced(why) : climb(craft, around);
    }

    /**
     * From space, the JUMP to a ship of another system: aimed beside it, at a point of its own cell off its
     * address on a bearing the craft's name picks ({@link StandoffRing#pointAround}) — the standoff a GO_TO
     * a ship arrives at (the two hulls' radii plus the arrival radius) and
     * {@link #OUT_OF_THE_JUMP_PAST_THE_STANDOFF} beyond, rounded up to the whole block an address holds — so
     * the craft comes out of the jump near the ship, never inside its hull, and flies the last of the way.
     * The craft's own hull and the ship's must be measured for that; when either cannot be, no such point
     * can be named.
     */
    private static Placement jumpBeside(Surroundings.Sighting seen, MotionSlot.Craft craft) {
        if (!(craft.hullRadius >= 0.0D)) {
            return Placement.unplaced(Unplaced.NOT_MEASURED);
        }
        if (!seen.measured()) {
            return Placement.unplaced(Unplaced.JUMP_TARGET_NOT_MEASURED);
        }
        long ring = (long) Math.ceil(craft.hullRadius + seen.hullRadius() + MotionSlot.ARRIVAL_RADIUS
                + OUT_OF_THE_JUMP_PAST_THE_STANDOFF);
        return Placement.jump(JumpDestination.toPlace(
                StandoffRing.pointAround(seen.elsewhere(), ring, craft.id.hashCode())));
    }

    /**
     * The CLIMB out of a planet's world: the point {@link #CLIMB_PAST_THE_LINE} above the world's orbit
     * line straight over the craft — re-placed over the column the craft is in on every tick, so the climb
     * is vertical — made for, never arrived at, since the entry crossing takes the craft on before it gets
     * there; unplaced while the world has no orbit line, or something solid stands in the column between
     * the craft and that point.
     */
    private static Placement climb(MotionSlot.Craft craft, Surroundings around) {
        double line = around.orbitLine();
        if (Double.isNaN(line)) {
            return Placement.unplaced(Unplaced.NO_ORBIT_LINE);
        }
        double top = line + CLIMB_PAST_THE_LINE;
        if (!around.openAbove(craft.position[0], craft.position[2], craft.position[1], top)) {
            return Placement.unplaced(Unplaced.PATH_BLOCKED);
        }
        return Placement.beforeACrossing(new double[]{craft.position[0], top, craft.position[2]},
                MotionSlot.Leg.CLIMB_TO_SPACE);
    }

    /**
     * From space, the way INTO THE SHELL of the body whose surface is the planet world {@code target}: the
     * point {@link #INTO_THE_SHELL} inside the shell {@link Surroundings#shell} answers, nearest the craft —
     * made for, never arrived at, since inside the shell the descent takes the craft on. A craft already
     * that deep is held where it is, for the descent to take it. For a body of another system, the jump to
     * it.
     */
    private static Placement intoTheShell(int target, MotionSlot.Craft craft, Surroundings around) {
        Surroundings.Sphere shell = around.shell(target);
        if (shell.unplaced() != null) {
            return Placement.unplaced(shell.unplaced());
        }
        if (shell.elsewhere() != null) {
            return Placement.jump(shell.elsewhere());
        }
        double[] c = shell.centre();
        double inside = Math.max(0.0D, shell.radius() - INTO_THE_SHELL);
        double dx = craft.position[0] - c[0];
        double dy = craft.position[1] - c[1];
        double dz = craft.position[2] - c[2];
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double[] aim = d <= inside ? craft.position.clone()
                : new double[]{c[0] + dx * inside / d, c[1] + dy * inside / d, c[2] + dz * inside / d};
        return Placement.intoTheShellOf(target, aim);
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
        if (kind == Kind.SHIP) {
            nbt.setString(NBT_KIND, KIND_SHIP);
            nbt.setString(NBT_SHIP, ship.toString());
        } else if (kind == Kind.ORBIT) {
            nbt.setString(NBT_KIND, KIND_ORBIT);
            nbt.setInteger(NBT_DIM, dimension);
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
            if (KIND_ORBIT.equals(kind)) {
                return orbit(nbt.getInteger(NBT_DIM));
            }
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        return null;
    }

    @Override
    public String toString() {
        switch (kind) {
            case SHIP:
                return "ship " + ship;
            case ORBIT:
                return "orbit " + dimension;
            default:
                return "surface " + dimension + " (" + x + "," + z + ")";
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof GuidanceTarget)) {
            return false;
        }
        GuidanceTarget t = (GuidanceTarget) o;
        return kind == t.kind && java.util.Objects.equals(ship, t.ship) && dimension == t.dimension
                && Double.compare(x, t.x) == 0 && Double.compare(z, t.z) == 0;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(kind, ship, dimension, x, z);
    }

    /** A target placed in the craft's world, or the reason it could not be. */
    static final class Placement {

        private final double[] position;
        private final double keepOut;
        private final boolean hullsApart;
        private final boolean provisional;
        private final double[] over;
        private final MotionSlot.Leg leg;
        private final java.util.OptionalInt entering;
        private final JumpDestination jump;
        private final Unplaced unplaced;

        private Placement(double[] position, double keepOut, boolean hullsApart, boolean provisional, double[] over,
                          MotionSlot.Leg leg, java.util.OptionalInt entering, JumpDestination jump,
                          Unplaced unplaced) {
            this.position = position;
            this.keepOut = keepOut;
            this.hullsApart = hullsApart;
            this.provisional = provisional;
            this.over = over;
            this.leg = leg;
            this.entering = entering;
            this.jump = jump;
            this.unplaced = unplaced;
        }

        /** A ship at {@code position} whose hull and the craft's touch nearer than {@code keepOut}. */
        static Placement at(double[] position, double keepOut) {
            return new Placement(position.clone(), keepOut, true, false, null, MotionSlot.Leg.TO_THE_TARGET,
                    java.util.OptionalInt.empty(), null, null);
        }

        /** The sphere of radius {@code radius} about {@code centre}: the place itself, no hull to keep from. */
        static Placement sphere(double[] centre, double radius) {
            return new Placement(centre.clone(), radius, false, false, null, MotionSlot.Leg.TO_THE_TARGET,
                    java.util.OptionalInt.empty(), null, null);
        }

        /** An aim at {@code position} for a ship whose hull nobody here could measure: no keep-out. */
        static Placement provisional(double[] position) {
            return new Placement(position.clone(), 0.0D, false, true, null, MotionSlot.Leg.TO_THE_TARGET,
                    java.util.OptionalInt.empty(), null, null);
        }

        /** An aim at {@code position} on the leg {@code leg}, which a crossing takes the craft on from before it
         *  gets there. */
        static Placement beforeACrossing(double[] position, MotionSlot.Leg leg) {
            return new Placement(position.clone(), 0.0D, false, true, null, leg, java.util.OptionalInt.empty(), null,
                    null);
        }

        /** An aim at {@code position} inside the descent shell of the body whose surface is the planet world
         *  {@code body}, which the descent takes the craft on from before it gets there. */
        static Placement intoTheShellOf(int body, double[] position) {
            return new Placement(position.clone(), 0.0D, false, true, null, MotionSlot.Leg.INTO_THE_SHELL,
                    java.util.OptionalInt.of(body), null, null);
        }

        /** The jump to another system, aimed at {@code destination}: nothing in the craft's world to fly to. */
        static Placement jump(JumpDestination destination) {
            return new Placement(null, 0.0D, false, true, null, MotionSlot.Leg.JUMP, java.util.OptionalInt.empty(),
                    destination, null);
        }

        /** A landing at {@code position}, let down from {@code over}, the point above it clear of the ground. */
        static Placement landing(double[] position, double[] over) {
            return new Placement(position.clone(), 0.0D, false, false, over.clone(), MotionSlot.Leg.TO_THE_TARGET,
                    java.util.OptionalInt.empty(), null, null);
        }

        static Placement unplaced(Unplaced why) {
            return new Placement(null, 0.0D, false, false, null, null, java.util.OptionalInt.empty(), null, why);
        }

        /** The leg of the way to the target this placement flies; {@code null} when it could not be placed. */
        MotionSlot.Leg leg() {
            return leg;
        }

        /** The planet world of the body whose descent shell this leg flies the craft into; empty otherwise. */
        java.util.OptionalInt entering() {
            return entering;
        }

        /** Where the jump this leg needs is aimed; {@code null} unless the leg is the jump. */
        JumpDestination jump() {
            return jump;
        }

        /** The point a landing is let down from; {@code null} for anything else. */
        double[] over() {
            return over == null ? null : over.clone();
        }

        /**
         * Whether the position is only MADE FOR, and reaching it is not arrival: no hull was measured there,
         * or a crossing takes the craft on before it gets there.
         */
        boolean provisional() {
            return provisional;
        }

        /** World frame, blocks; {@code null} when the target could not be placed, and on the jump. */
        double[] position() {
            return position == null ? null : position.clone();
        }

        /**
         * How near the craft's position may come to {@link #position}: before the hulls could touch, for a
         * ship; the radius of the sphere itself, for an orbit.
         */
        double keepOut() {
            return keepOut;
        }

        /**
         * Whether {@link #keepOut} keeps two hulls apart, so that an arrival anywhere within its radius must
         * still leave them apart and the arrival clearance is added past it; an orbit's sphere IS the place.
         */
        boolean hullsApart() {
            return hullsApart;
        }

        Unplaced unplaced() {
            return unplaced;
        }
    }
}
