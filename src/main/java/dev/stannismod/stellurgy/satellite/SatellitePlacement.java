package dev.stannismod.stellurgy.satellite;

import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.api.satellite.SatelliteOrbit;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.environment.BodyRotation;
import dev.stannismod.stellurgy.space.DescentShell;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.space.SpaceSubsystem;
import dev.stannismod.stellurgy.universe.SystemBody;
import dev.stannismod.stellurgy.universe.UniverseRegistry;
import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;
import org.joml.Vector3dc;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * Where a satellite goes and where it stands: the orbits a body admits, the orbit a release puts it on,
 * and the point of the body's cell it stands at.
 *
 * <p><b>The cell's frame flies the orbit</b>, so a satellite stands still in the cell, as a parked ship
 * does: its point is its place on the orbit at release, at its true distance from the body.</p>
 */
public final class SatellitePlacement {

    private SatellitePlacement() {
    }

    /**
     * The altitudes, metres above the surface, an orbit about a body can be held at. The lowest is the
     * body's descent shell — below it there is no cell to stand in, only the world under the shell. The
     * highest is its sphere of influence: beyond it a satellite is no longer in the body's frame.
     */
    public static final class AltitudeBounds {
        public final double lowestM;
        public final double highestM;
        /** The altitude a release goes to when none is set: the body's orbit line. */
        public final double orbitLineM;

        AltitudeBounds(double lowestM, double highestM, double orbitLineM) {
            this.lowestM = lowestM;
            this.highestM = highestM;
            this.orbitLineM = orbitLineM;
        }

        public boolean admits(double altitudeM) {
            return altitudeM >= lowestM && altitudeM <= highestM;
        }
    }

    /** The body a world stands for, as the universe names it; empty for a world that is no body there. */
    public static Optional<SystemBody> bodyOf(UniverseRegistry reg, int dimId) {
        if (reg == null) {
            return Optional.empty();
        }
        Optional<GalacticCoord> cell = reg.coordForPlanet(dimId);
        if (!cell.isPresent()) {
            return Optional.empty();
        }
        for (SystemBody body : reg.bodiesAt(cell.get())) {
            if (body.dimId() == dimId) {
                return Optional.of(body);
            }
        }
        return Optional.empty();
    }

    /**
     * An answer, or the reason there is none: what failed to resolve, in words a log and a player can be
     * shown. A refusal is never a value standing in for one.
     */
    public static final class Answer<T> {
        private final T value;
        private final String refusal;

        private Answer(T value, String refusal) {
            this.value = value;
            this.refusal = refusal;
        }

        static <T> Answer<T> of(T value) {
            return new Answer<>(value, null);
        }

        static <T> Answer<T> refused(String why) {
            return new Answer<>(null, why);
        }

        public boolean isPresent() {
            return value != null;
        }

        /** @throws IllegalStateException on a refusal */
        public T get() {
            if (value == null) {
                throw new IllegalStateException("no answer: " + refusal);
            }
            return value;
        }

        /** Why there is no answer; {@code null} when there is one. */
        public String refusal() {
            return refusal;
        }
    }

    /**
     * The altitudes an orbit about {@code body} can be held at, at {@code tick}; refused when the body has
     * no size or no mass to orbit, no sphere the universe can state, or its world no orbit line.
     */
    public static Answer<AltitudeBounds> boundsAbout(UniverseRegistry reg, SystemBody body,
                                                     DimensionProperties props, long tick) {
        double radiusM = radiusM(body);
        if (!(radiusM > 0d) || !(body.massEarths() > 0d)) {
            return Answer.refused("the body has no radius or no mass to orbit (radius " + body.radiusEarths()
                    + ", mass " + body.massEarths() + " Earths)");
        }
        OptionalLong sphereBlocks = SpaceSubsystem.zoneSphereRadiusOf(reg, body.name(), tick);
        if (!sphereBlocks.isPresent()) {
            return Answer.refused("the universe states no sphere of influence about the body at " + body.name());
        }
        if (!props.orbitLine().isPresent()) {
            return Answer.refused("world " + props.getId() + " states no orbit line");
        }
        // The shell read in metres, as the world below it reads it: in whole chart blocks it is rounded to a
        // 250 m step, which put the body's own derived orbit line a few metres under its own floor.
        double lowest = DescentShell.orbitLineWorldY(body.radiusEarths());
        double highest = sphereBlocks.getAsLong() * (double) AstronomicalBodyHelper.METRES_PER_CHART_BLOCK - radiusM;
        // A world block is a metre and the line is counted from Y 0, so the line's Y is its altitude.
        return Answer.of(new AltitudeBounds(lowest, highest, props.orbitLine().getAsInt()));
    }

    /**
     * The orbit a satellite released now about {@code props}' body is put on: at {@code statedAltitudeM}
     * above the surface when one is stated, else at the body's orbit line, inclined by
     * {@code inclinationRad}. Refused, with the reason, when the universe names no body for the world, the
     * body admits no orbit, its world does not turn, or the altitude is outside what it admits.
     */
    public static Answer<SatelliteOrbit> orbitAbout(UniverseRegistry reg, DimensionProperties props,
                                                    OptionalDouble statedAltitudeM, double inclinationRad,
                                                    long tick) {
        Optional<SystemBody> body = bodyOf(reg, props.getId());
        if (!body.isPresent()) {
            return Answer.refused("the universe names no body for world " + props.getId());
        }
        Answer<AltitudeBounds> bounds = boundsAbout(reg, body.get(), props, tick);
        if (!bounds.isPresent()) {
            return Answer.refused(bounds.refusal());
        }
        if (props.rotationalPeriod <= 0) {
            return Answer.refused("world " + props.getId() + " does not turn (rotational period "
                    + props.rotationalPeriod + ")");
        }
        double altitudeM = statedAltitudeM.isPresent() ? statedAltitudeM.getAsDouble() : bounds.get().orbitLineM;
        if (!bounds.get().admits(altitudeM)) {
            return Answer.refused("an altitude of " + Math.round(altitudeM / 1000d) + " km is outside the "
                    + Math.round(bounds.get().lowestM / 1000d) + "–" + Math.round(bounds.get().highestM / 1000d)
                    + " km the body admits");
        }
        double mu = SatelliteOrbit.EARTH_MU_M3_S2 * body.get().massEarths();
        return Answer.of(SatelliteOrbit.releasedAt(radiusM(body.get()) + altitudeM, inclinationRad, mu,
                rotationOf(props), tick));
    }

    /**
     * How {@code props}' body turns: its rotational period, meridian 0 along the frame's x axis at tick
     * zero — the convention every body-fixed reading shares.
     */
    public static BodyRotation rotationOf(DimensionProperties props) {
        return BodyRotation.of(props.rotationalPeriod, 0d);
    }

    /**
     * The point of the cell a satellite stands at: its body's address displaced by its place on the orbit
     * at release. Empty for a satellite with no orbit, or whose body the universe does not name, or whose
     * body's cell has no lattice to carry the displacement on.
     */
    public static Optional<GalacticCoord> standingPoint(UniverseRegistry reg, SatelliteBase satellite, long tick) {
        Optional<SatelliteOrbit> orbit = satellite.getOrbit();
        if (!orbit.isPresent()) {
            return Optional.empty();
        }
        Optional<SystemBody> body = bodyOf(reg, satellite.getDimensionId());
        if (!body.isPresent()) {
            return Optional.empty();
        }
        GalacticCoord address = body.get().addressAt(tick);
        if (!address.knowsItsLattice()) {
            return Optional.empty();
        }
        long[] offset = cellOffsetBlocks(orbit.get().standingOffset());
        return Optional.of(address.plusLocal(offset[0], offset[1], offset[2]));
    }

    /**
     * A body-centred displacement in metres — z along the spin axis — as chart blocks along the cell's
     * axes, where Y is up: x stays x, the spin axis becomes Y, and y becomes −Z, which keeps the turn
     * proper (a right-handed frame stays right-handed).
     */
    public static long[] cellOffsetBlocks(Vector3dc metres) {
        double perBlock = AstronomicalBodyHelper.METRES_PER_CHART_BLOCK;
        return new long[]{Math.round(metres.x() / perBlock), Math.round(metres.z() / perBlock),
                Math.round(-metres.y() / perBlock)};
    }

    private static double radiusM(SystemBody body) {
        return body.radiusEarths() * AstronomicalBodyHelper.EARTH_RADIUS_METRES;
    }
}
