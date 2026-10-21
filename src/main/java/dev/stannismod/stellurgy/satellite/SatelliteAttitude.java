package dev.stannismod.stellurgy.satellite;

import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.api.satellite.SatelliteOrbit;
import dev.stannismod.stellurgy.space.AbsolutePos;
import dev.stannismod.stellurgy.space.BlockDelta;
import dev.stannismod.stellurgy.universe.SystemBody;
import dev.stannismod.stellurgy.universe.SystemBodyKind;
import dev.stannismod.stellurgy.universe.UniverseRegistry;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Which way a satellite's parts face at a tick, computed and never stored: its power generation toward
 * the nearest star, its dish toward its body. Directions are unit vectors along the cell's axes — the
 * axes of the world the cell is, so the same vectors the sky is drawn along.
 *
 * <p><b>The star is the one the cell's sky shows</b>: the vector from the satellite's point to where the
 * star is at the tick, through the frames, as the sky feed computes it. It moves as the body goes round
 * its star. The satellite's own phase is not applied here — the cell does not draw it.</p>
 */
public final class SatelliteAttitude {

    private final Vector3dc toStar;
    private final Vector3dc toBody;

    private SatelliteAttitude(Vector3dc toStar, Vector3dc toBody) {
        this.toStar = toStar;
        this.toBody = toBody;
    }

    /** Unit vector from the satellite toward the nearest star. */
    public Vector3dc toStar() {
        return toStar;
    }

    /** Unit vector from the satellite toward its body's centre. */
    public Vector3dc toBody() {
        return toBody;
    }

    /**
     * The attitude of a satellite standing {@code offsetBlocks} (cell axes) from a body at {@code body},
     * with the system's stars at {@code stars}, all read at one tick. Empty when there is no star, or the
     * satellite stands at the body's centre: then there is no direction to face.
     */
    public static Optional<SatelliteAttitude> facing(AbsolutePos body, List<AbsolutePos> stars, long[] offsetBlocks) {
        AbsolutePos at = body.plus(offsetBlocks[0], offsetBlocks[1], offsetBlocks[2]);
        BlockDelta nearest = null;
        for (AbsolutePos star : stars) {
            BlockDelta toStar = star.minus(at);
            if (nearest == null || toStar.length() < nearest.length()) {
                nearest = toStar;
            }
        }
        BlockDelta toBody = body.minus(at);
        if (nearest == null || nearest.isZero() || toBody.isZero()) {
            return Optional.empty();
        }
        return Optional.of(new SatelliteAttitude(unit(nearest), unit(toBody)));
    }

    /** The attitude of {@code satellite} at {@code tick}; empty when it has no orbit or its system cannot be read. */
    public static Optional<SatelliteAttitude> of(UniverseRegistry reg, SatelliteBase satellite, long tick) {
        Optional<SatelliteOrbit> orbit = satellite.getOrbit();
        Optional<SystemBody> body = SatellitePlacement.bodyOf(reg, satellite.getDimensionId());
        if (!orbit.isPresent() || !body.isPresent()) {
            return Optional.empty();
        }
        List<AbsolutePos> stars = new ArrayList<>();
        for (SystemBody b : reg.systemBodiesAt(body.get().name())) {
            if (b.kind() == SystemBodyKind.STAR) {
                stars.add(b.absoluteAt(tick));
            }
        }
        return facing(body.get().absoluteAt(tick), stars,
                SatellitePlacement.cellOffsetBlocks(orbit.get().standingOffset()));
    }

    private static Vector3dc unit(BlockDelta d) {
        return new Vector3d(d.dx(), d.dy(), d.dz()).normalize();
    }
}
