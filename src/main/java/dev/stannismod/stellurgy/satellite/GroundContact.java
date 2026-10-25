package dev.stannismod.stellurgy.satellite;

import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.api.satellite.SatelliteOrbit;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.space.BlockDelta;
import dev.stannismod.stellurgy.universe.SystemBody;
import dev.stannismod.stellurgy.universe.UniverseRegistry;
import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;
import net.minecraft.util.math.BlockPos;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Optional;

/**
 * Whether a satellite's dish sees a ground terminal at a tick: the terminal's place on its body, the
 * satellite on its orbit, and the bodies that may stand between them, handed to {@link Downlink}.
 */
public final class GroundContact {

    private GroundContact() {
    }

    /** What the line from a terminal to a satellite comes to. */
    public enum Sight {
        SEES,
        /** Below the terminal's horizon, or behind the satellite's body. */
        HIDDEN,
        /** The satellite is on no orbit: still in a hatch, or a mission. */
        NOT_IN_ORBIT,
        /** The terminal stands on no body's surface, so it has no place to see from; {@link Answer#reason} says why. */
        NO_PLACE
    }

    /** A sight, and for {@link Sight#NO_PLACE} the reason in words a log can be shown. */
    public static final class Answer {
        public final Sight sight;
        public final String reason;

        private Answer(Sight sight, String reason) {
            this.sight = sight;
            this.reason = reason;
        }
    }

    /**
     * What a terminal at {@code pos} of world {@code terminalDim} sees of {@code satellite} at space-clock
     * {@code tick}. The terminal stands where its block falls on its body's turning surface
     * ({@link Downlink#surfacePoint}); the satellite where its orbit puts it. A satellite of another body is
     * placed by where the two bodies stand at that tick, and that body may hide it.
     */
    public static Answer sightOf(UniverseRegistry reg, int terminalDim, BlockPos pos, SatelliteBase satellite, long tick) {
        Optional<SatelliteOrbit> orbit = satellite.getOrbit();
        if (!orbit.isPresent()) {
            return new Answer(Sight.NOT_IN_ORBIT, null);
        }
        DimensionProperties here = DimensionManager.getInstance().getDimensionPropertiesOrNull(terminalDim);
        Optional<SystemBody> ground = here == null ? Optional.empty() : SatellitePlacement.bodyOf(reg, terminalDim);
        if (!ground.isPresent()) {
            return noPlace("the universe names no body for world " + terminalDim);
        }
        double groundRadiusM = radiusM(ground.get());
        if (!(groundRadiusM > 0d) || here.rotationalPeriod <= 0) {
            return noPlace("the body of world " + terminalDim + " has no size or does not turn (radius "
                    + ground.get().radiusEarths() + " Earths, rotational period " + here.rotationalPeriod + ")");
        }
        Vector3d terminal = Downlink.surfacePoint(pos.getX() + 0.5d, pos.getZ() + 0.5d, groundRadiusM,
                SatellitePlacement.rotationOf(here), tick);

        Vector3dc fromItsBody = orbit.get().positionAt(tick);
        if (satellite.getDimensionId() == terminalDim) {
            return answer(Downlink.sees(terminal, fromItsBody, new Vector3d(), 0d));
        }
        Optional<SystemBody> its = SatellitePlacement.bodyOf(reg, satellite.getDimensionId());
        if (!its.isPresent()) {
            return noPlace("the universe names no body for world " + satellite.getDimensionId()
                    + ", which the satellite orbits");
        }
        if (DimensionManager.getInstance().getDimensionPropertiesOrNull(satellite.getDimensionId()) == null) {
            // Its body's turn places the satellite's rows under it (INV-SAT-41); with no properties, no turn.
            return noPlace("world " + satellite.getDimensionId() + ", which the satellite orbits, has no body properties");
        }
        BlockDelta between = its.get().absoluteAt(tick).minus(ground.get().absoluteAt(tick));
        if (between.isSaturated()) {
            return noPlace("the bodies of worlds " + terminalDim + " and " + satellite.getDimensionId()
                    + " are too far apart to place one against the other");
        }
        double perBlock = AstronomicalBodyHelper.METRES_PER_CHART_BLOCK;
        // The cell's axes carried into the body frame: x stays x, the cell's Y is the spin axis, its Z is −y.
        Vector3d itsCentre = new Vector3d(between.dx(), -between.dz(), between.dy()).mul(perBlock);
        Vector3d target = new Vector3d(itsCentre).add(fromItsBody);
        return answer(Downlink.sees(terminal, target, itsCentre, radiusM(its.get())));
    }

    private static Answer answer(boolean sees) {
        return new Answer(sees ? Sight.SEES : Sight.HIDDEN, null);
    }

    private static Answer noPlace(String reason) {
        return new Answer(Sight.NO_PLACE, reason);
    }

    private static double radiusM(SystemBody body) {
        return body.radiusEarths() * AstronomicalBodyHelper.EARTH_RADIUS_METRES;
    }
}
