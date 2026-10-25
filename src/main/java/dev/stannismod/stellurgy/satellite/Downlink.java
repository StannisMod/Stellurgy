package dev.stannismod.stellurgy.satellite;

import dev.stannismod.stellurgy.environment.BodyRotation;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * When a satellite's dish can see a ground terminal: the terminal's place on its turning body, and the
 * line of sight from it. Pure — every input is handed in.
 *
 * <p>Positions are in a body-centred frame whose axes do not turn with the body, z along the spin axis —
 * the frame {@link dev.stannismod.stellurgy.api.satellite.SatelliteOrbit} computes a satellite in.</p>
 */
public final class Downlink {

    private Downlink() {
    }

    /**
     * Where the block at world {@code x}, {@code z} of a body's world stands at {@code tick}, metres from
     * the body's centre. A block is a metre on the sphere: {@code x} runs east along the equator from
     * meridian 0, then {@code z} runs along that meridian, north being −z, so a far enough walk goes over
     * the pole. East is the way the body turns — clockwise about +z ({@link BodyRotation}) — so it lowers the
     * frame angle, and north is the pole the turn looks anticlockwise from, −z: east, north and up stay a
     * right-handed triple, as the world's own x, −z and y are. The block's height is not counted — a terminal
     * stands on the surface.
     */
    public static Vector3d surfacePoint(double x, double z, double radiusM, BodyRotation rotation, long tick) {
        double longitude = rotation.angleAt(tick) - x / radiusM;
        double north = -z / radiusM;
        double cosNorth = Math.cos(north);
        return new Vector3d(cosNorth * Math.cos(longitude), cosNorth * Math.sin(longitude), -Math.sin(north))
                .mul(radiusM);
    }

    /**
     * The place on a turning body's surface straight under {@code position} at {@code tick} — the inverse of
     * {@link #surfacePoint}: a satellite over a block's place is under the same north and east that block
     * stands at. {@code position} is in the same frame, from the body's centre, and not at it.
     */
    public static GroundPoint under(Vector3dc position, BodyRotation rotation, long tick) {
        double north = Math.asin(-position.z() / position.length());
        double east = rotation.angleAt(tick) - Math.atan2(position.y(), position.x());
        return new GroundPoint(north, east);
    }

    /** A place on a body's surface: radians north of the equator and east of meridian 0. Immutable. */
    public static final class GroundPoint {
        public final double northRad;
        /** Taken into {@code [-π, π)}. */
        public final double eastRad;

        public GroundPoint(double northRad, double eastRad) {
            this.northRad = northRad;
            double turn = 2d * Math.PI;
            this.eastRad = eastRad - turn * Math.floor((eastRad + Math.PI) / turn);
        }
    }

    /**
     * Whether a terminal at {@code terminal}, on the surface of a body centred at the origin, sees
     * {@code target}: the target is not below its horizon — a target on it is seen — and the straight line
     * to it does not pass through {@code hiding}, a further body about {@code hidingCentre}. Pass a
     * {@code hidingRadiusM} of zero when no other body stands in the way.
     */
    public static boolean sees(Vector3dc terminal, Vector3dc target, Vector3dc hidingCentre, double hidingRadiusM) {
        Vector3d line = new Vector3d(target).sub(terminal);
        if (line.dot(terminal) < 0d) {
            return false;
        }
        return hidingRadiusM <= 0d || !passesThrough(terminal, line, hidingCentre, hidingRadiusM);
    }

    /** Whether the segment from {@code from} along {@code line} comes nearer {@code centre} than {@code radiusM}. */
    private static boolean passesThrough(Vector3dc from, Vector3dc line, Vector3dc centre, double radiusM) {
        Vector3d toCentre = new Vector3d(centre).sub(from);
        double along = line.lengthSquared() == 0d ? 0d
                : Math.max(0d, Math.min(1d, toCentre.dot(line) / line.lengthSquared()));
        Vector3d nearest = new Vector3d(line).mul(along).sub(toCentre);
        return nearest.lengthSquared() < radiusM * radiusM;
    }
}
