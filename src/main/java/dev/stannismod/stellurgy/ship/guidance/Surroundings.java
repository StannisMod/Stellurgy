package dev.stannismod.stellurgy.ship.guidance;

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
        private final GuidanceTarget.Unplaced unplaced;

        private Sighting(double[] position, double hullRadius, GuidanceTarget.Unplaced unplaced) {
            this.position = position;
            this.hullRadius = hullRadius;
            this.unplaced = unplaced;
        }

        /**
         * The ship stands at {@code position}, and the smallest sphere about that point holding its whole
         * hull has radius {@code hullRadius}.
         */
        public static Sighting at(double[] position, double hullRadius) {
            return new Sighting(position.clone(), hullRadius, null);
        }

        /** The ship cannot be placed in the craft's world, for {@code why}. */
        public static Sighting unplaced(GuidanceTarget.Unplaced why) {
            return new Sighting(null, Double.NaN, why);
        }

        double[] position() {
            return position.clone();
        }

        double hullRadius() {
            return hullRadius;
        }

        GuidanceTarget.Unplaced unplaced() {
            return unplaced;
        }
    }
}
