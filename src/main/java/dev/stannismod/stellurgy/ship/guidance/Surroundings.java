package dev.stannismod.stellurgy.ship.guidance;

import dev.stannismod.stellurgy.space.GalacticCoord;

import java.util.UUID;

/**
 * What guidance may ask of the world around one craft on one tick. Every answer is in the WORLD frame
 * of the craft's own world, blocks — whatever world or cell the thing asked about is really in.
 *
 * <p>The seam between guidance's laws, which read nothing of the world, and the world they fly in.</p>
 */
public interface Surroundings {

    /** Where the ship whose durable id is {@code shipId} stands as seen from the craft, or why not. */
    Sighting ship(UUID shipId);

    /**
     * The height of the top face of the highest ground — solid or liquid — anywhere in the square of
     * half-side {@code radius} about the column {@code (x, z)} of the craft's world.
     */
    double groundTop(double x, double z, double radius);

    /** One answer of {@link #ship}. Immutable. */
    final class Sighting {

        private final double[] position;
        private final double hullRadius;
        private final boolean measured;
        private final GalacticCoord cell;
        private final GuidanceTarget.Unplaced unplaced;

        private Sighting(double[] position, double hullRadius, boolean measured, GalacticCoord cell,
                         GuidanceTarget.Unplaced unplaced) {
            this.position = position;
            this.hullRadius = hullRadius;
            this.measured = measured;
            this.cell = cell;
            this.unplaced = unplaced;
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

        /** The hull's measured reach about {@link #position}; NaN when not {@link #measured}. */
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

        GuidanceTarget.Unplaced unplaced() {
            return unplaced;
        }
    }
}
