package dev.stannismod.stellurgy.ship.guidance;

import dev.stannismod.stellurgy.space.GalacticCoord;

import java.util.UUID;

/**
 * What guidance may ask of the world around one craft on one tick — and the one thing besides flying it
 * may ask the craft to do: jump. Every answer is in the WORLD frame of the craft's own world, blocks —
 * whatever world or cell the thing asked about is really in.
 *
 * <p>The seam between guidance's laws, which read nothing of the world, and the world they fly in.</p>
 */
public interface Surroundings {

    /**
     * Ask the craft's jump drive to take it to {@code destination}, the way the jump leg needs: the
     * destination armed at the craft's navigation computer as a pilot arms it, and the jump committed as
     * the helm's key commits it — through every advisory the jump gate raises, as a pilot who confirms
     * it goes through it, and declined only by what would have stopped that pilot. Asked on every tick of
     * that leg until the jump carries the craft off; asking again while it winds up is not a second press.
     */
    JumpAnswer jump(JumpDestination destination);

    /** What the craft's drive answered one ask for the jump. */
    final class JumpAnswer {
        private final String declinedBy;
        private final java.util.List<String> advisoriesGoneThrough;

        private JumpAnswer(String declinedBy, java.util.List<String> advisoriesGoneThrough) {
            this.declinedBy = declinedBy;
            this.advisoriesGoneThrough = advisoriesGoneThrough;
        }

        /** The jump is under way — winding up, or committed — and this ask began nothing. */
        public static JumpAnswer underWay() {
            return new JumpAnswer(null, null);
        }

        /**
         * This ask began the jump's wind-up, going through {@code advisories} — the lang keys of the jump
         * gate's advisory objections, in its order; empty when the gate raised none.
         */
        public static JumpAnswer began(java.util.List<String> advisories) {
            if (advisories == null) {
                throw new IllegalArgumentException("a wind-up begun through no advisory goes through an empty list");
            }
            return new JumpAnswer(null, java.util.Collections.unmodifiableList(new java.util.ArrayList<>(advisories)));
        }

        /** The jump is declined this tick, by the lang key {@code by}: the gate's clause, or how a commit failed. */
        public static JumpAnswer declined(String by) {
            if (by == null) {
                throw new IllegalArgumentException("a declined jump names what declined it");
            }
            return new JumpAnswer(by, null);
        }

        /** What declines the jump this tick; {@code null} while it is under way. */
        public String declinedBy() {
            return declinedBy;
        }

        /** Whether this ask began the wind-up. */
        public boolean began() {
            return advisoriesGoneThrough != null;
        }

        /** For an ask that {@link #began}, the advisories it went through; else {@code null}. */
        public java.util.List<String> advisoriesGoneThrough() {
            return advisoriesGoneThrough;
        }

        @Override
        public String toString() {
            return declinedBy != null ? "declined " + declinedBy
                    : began() ? "began through " + advisoriesGoneThrough : "under way";
        }
    }

    /** Where the ship whose durable id is {@code shipId} stands as seen from the craft, or why not. */
    Sighting ship(UUID shipId);

    /**
     * The height of the top face of the highest ground — solid or liquid — anywhere in the square of
     * half-side {@code radius} about the column {@code (x, z)} of the craft's world.
     */
    double groundTop(double x, double z, double radius);

    /**
     * The height above which the craft's own world gives way to space: the line a craft crosses into
     * orbit at. NaN where the world has none — no climb leaves it. Asked of a craft on a planet.
     */
    double orbitLine();

    /** Whether nothing solid stands in the column {@code (x, z)} of the craft's world from {@code fromY} up to {@code toY}. */
    boolean openAbove(double x, double z, double fromY, double toY);

    /**
     * Where the orbit of the body whose surface is the planet world {@code dimension} lies, seen from a
     * craft in space; or, for a body whose cell is farther from the craft's than the next one, where the
     * jump to it is aimed ({@link Sphere#farOff}); or why it cannot be placed. Asked of a craft in space.
     */
    Sphere orbit(int dimension);

    /**
     * Where the descent shell of the body whose surface is the planet world {@code dimension} lies — the
     * sphere a craft inside of descends onto that body — seen from a craft in space, or the jump to it, or
     * why it cannot be placed, as {@link #orbit} answers. Asked of a craft in space.
     */
    Sphere shell(int dimension);

    /**
     * Every descent shell in the craft's world — the sphere about each body a craft inside of descends
     * onto, placed as {@link #shell} places one — but the shell of the body whose surface is the planet
     * world {@code entering}, when one is given: the shell the craft is being flown into. Empty on a
     * planet.
     */
    java.util.List<Sphere> shellsBut(java.util.OptionalInt entering);

    /**
     * {@code NOT_FOUND} when no body has the planet world {@code dimension} for its surface, so it cannot
     * be flown to from the craft's world; {@code null} when one has — wherever it is, it is reached through
     * space. Asked of a craft on a planet.
     */
    GuidanceTarget.Unplaced unreachable(int dimension);

    /** One answer of {@link #orbit} or {@link #shell}: a sphere about a body. Immutable. */
    final class Sphere {

        private final double[] centre;
        private final double radius;
        private final JumpDestination elsewhere;
        private final GuidanceTarget.Unplaced unplaced;

        private Sphere(double[] centre, double radius, JumpDestination elsewhere, GuidanceTarget.Unplaced unplaced) {
            this.centre = centre;
            this.radius = radius;
            this.elsewhere = elsewhere;
            this.unplaced = unplaced;
        }

        /** The sphere of radius {@code radius} about the body standing at {@code centre} in the craft's world. */
        public static Sphere about(double[] centre, double radius) {
            if (!(radius > 0.0D)) {
                throw new IllegalArgumentException("a sphere about a body has a positive radius; got " + radius);
            }
            return new Sphere(centre.clone(), radius, null, null);
        }

        /**
         * The body's cell is farther from the craft's than the next one, so nothing about it is placed in
         * the craft's world: reaching it is a jump, aimed at {@code destination}.
         */
        public static Sphere farOff(JumpDestination destination) {
            if (destination == null) {
                throw new IllegalArgumentException("a far-off body is jumped to somewhere");
            }
            return new Sphere(null, Double.NaN, destination, null);
        }

        /** The sphere cannot be placed in the craft's world, for {@code why}. */
        public static Sphere unplaced(GuidanceTarget.Unplaced why) {
            return new Sphere(null, Double.NaN, null, why);
        }

        double[] centre() {
            return centre.clone();
        }

        double radius() {
            return radius;
        }

        /** Where the jump to a far-off body is aimed; {@code null} for a body placed here. */
        JumpDestination elsewhere() {
            return elsewhere;
        }

        GuidanceTarget.Unplaced unplaced() {
            return unplaced;
        }
    }

    /** One answer of {@link #ship}. Immutable. */
    final class Sighting {

        private final double[] position;
        private final double hullRadius;
        private final boolean measured;
        private final GalacticCoord cell;
        private final boolean inSpace;
        private final Integer planet;
        private final GalacticCoord elsewhere;
        private final GuidanceTarget.Unplaced unplaced;
        private final boolean arriving;

        private Sighting(double[] position, double hullRadius, boolean measured, GalacticCoord cell,
                         GuidanceTarget.Unplaced unplaced) {
            this(position, hullRadius, measured, cell, false, null, null, unplaced);
        }

        private Sighting(double[] position, double hullRadius, boolean measured, GalacticCoord cell,
                         boolean inSpace, Integer planet, GalacticCoord elsewhere, GuidanceTarget.Unplaced unplaced) {
            this(position, hullRadius, measured, cell, inSpace, planet, elsewhere, unplaced, false);
        }

        private Sighting(double[] position, double hullRadius, boolean measured, GalacticCoord cell,
                         boolean inSpace, Integer planet, GalacticCoord elsewhere, GuidanceTarget.Unplaced unplaced,
                         boolean arriving) {
            this.position = position;
            this.hullRadius = hullRadius;
            this.measured = measured;
            this.cell = cell;
            this.inSpace = inSpace;
            this.planet = planet;
            this.elsewhere = elsewhere;
            this.unplaced = unplaced;
            this.arriving = arriving;
        }

        /**
         * Seen from a craft on a planet: the ship is in space, settled in a cell. It has no position in the
         * craft's world; reaching it starts with the climb into space.
         */
        public static Sighting inSpace() {
            return new Sighting(null, Double.NaN, false, null, true, null, null, null);
        }

        /**
         * The ship is in the planet world {@code dimension}, which is not the craft's world, and some body
         * has that world for its surface. It has no position in the craft's world; reaching it goes through
         * space — and a jump, when that body's cell is beyond the next one — and down onto that body.
         */
        public static Sighting onPlanet(int dimension) {
            return new Sighting(null, Double.NaN, false, null, false, dimension, null, null);
        }

        /**
         * Seen from a craft in space: the ship is in space, settled at {@code address}, in a cell farther
         * from the craft's than the next one, and its hull reaches {@code hullRadius} about its position
         * there, as it last measured itself. It has no position in the craft's world; reaching it takes a
         * jump.
         */
        public static Sighting farOff(GalacticCoord address, double hullRadius) {
            if (address == null) {
                throw new IllegalArgumentException("a far-off ship is settled somewhere");
            }
            if (!(hullRadius >= 0.0D)) {
                throw new IllegalArgumentException("a far-off ship is jumped to beside its measured hull; got "
                        + hullRadius + " (one not measured yet is arriving)");
            }
            return new Sighting(null, hullRadius, true, null, false, null, address, null);
        }

        /**
         * The ship is on its way to a place it can be aimed at: in hyperspace, mid-jump — it is nowhere
         * until the jump ends — or come into space beyond the next cell and not yet measured there, which
         * it is on its own first tick in its cell. Reaching it starts when it gets there.
         */
        public static Sighting arriving() {
            return new Sighting(null, Double.NaN, false, null, false, null, null, null, true);
        }

        /**
         * The ship stands at {@code position} in the craft's own world, and the smallest sphere about that
         * point holding its whole hull has radius {@code hullRadius}.
         */
        public static Sighting at(double[] position, double hullRadius) {
            return new Sighting(position.clone(), hullRadius, true, null, null);
        }

        /**
         * The ship stands at {@code position}, placed through the frames, but in the world of ANOTHER space
         * cell, {@code cell}: its hull is not in the craft's world, so nothing here measures it.
         */
        public static Sighting inAnotherCell(double[] position, GalacticCoord cell) {
            if (cell == null) {
                throw new IllegalArgumentException("a ship in another cell names that cell");
            }
            return new Sighting(position.clone(), Double.NaN, false, cell, null);
        }

        /**
         * The ship stands at {@code position} in the craft's own world, but its substrate record holds an
         * empty block set, so there is no hull to measure: nothing of it can be touched there either.
         * That is a ship a crossing has just cut out of this world: the seam carry is asked from its own
         * flight computer's tick ({@code TileAdvancedFlightComputer#update} →
         * {@code CellCrossingController#requestCarry} → {@code VSIntegration#crossShip}, which cuts the
         * blocks), and its record stays registered, blockless, until the substrate's sweep at the end of
         * that world tick ({@code WorldServerShipManager#tick}) — so a craft whose computer ticks after it
         * in the same world tick sees this.
         */
        public static Sighting withoutBlocks(double[] position) {
            return new Sighting(position.clone(), Double.NaN, false, null, null);
        }

        /** The ship cannot be placed in the craft's world, for {@code why}. */
        public static Sighting unplaced(GuidanceTarget.Unplaced why) {
            return new Sighting(null, Double.NaN, false, null, why);
        }

        double[] position() {
            return position.clone();
        }

        /** The hull's measured reach about {@link #position} — about its address, for a far-off ship; NaN
         *  when not {@link #measured}. */
        double hullRadius() {
            return hullRadius;
        }

        boolean measured() {
            return measured;
        }

        /** The cell whose world the ship is in when that is not the craft's; {@code null} otherwise. */
        GalacticCoord cell() {
            return cell;
        }

        /** Whether the ship is in space while the craft is on a planet ({@link #inSpace()}). */
        boolean isInSpace() {
            return inSpace;
        }

        /** The planet world the ship is in when that is another world than the craft's; {@code null} otherwise. */
        Integer planet() {
            return planet;
        }

        /** Where the ship is settled when that is a cell beyond the next one ({@link #farOff}); else {@code null}. */
        GalacticCoord elsewhere() {
            return elsewhere;
        }

        /** Whether the ship is on its way to a place it can be aimed at ({@link #arriving}). */
        boolean isArriving() {
            return arriving;
        }

        GuidanceTarget.Unplaced unplaced() {
            return unplaced;
        }
    }
}
