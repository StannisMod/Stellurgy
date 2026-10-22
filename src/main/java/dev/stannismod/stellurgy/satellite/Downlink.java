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
     * the pole. The block's height is not counted — a terminal stands on the surface.
     */
    public static Vector3d surfacePoint(double x, double z, double radiusM, BodyRotation rotation, long tick) {
        double longitude = rotation.angleAt(tick) + x / radiusM;
        double north = -z / radiusM;
        double cosNorth = Math.cos(north);
        return new Vector3d(cosNorth * Math.cos(longitude), cosNorth * Math.sin(longitude), Math.sin(north))
                .mul(radiusM);
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
