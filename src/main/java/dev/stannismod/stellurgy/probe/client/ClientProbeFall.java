package dev.stannismod.stellurgy.probe.client;

import dev.stannismod.stellurgy.probe.FallPlacement;
import dev.stannismod.stellurgy.probe.ProbeFall;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

/**
 * The fall a client world has been told about, and where in its sky it stands at a moment. Held by the
 * client world ({@code ClientWorldDrawings}); born empty with it.
 *
 * <h3>Drawn in the sky, not in the world</h3>
 * <p>The client clips everything beyond its far plane — a few hundred blocks — and the fall begins a
 * hundred kilometres up and a thousand away. So it is never placed at its true point: it is placed along its
 * true DIRECTION from the eye, at a distance inside the far plane, and scaled by the ratio of the two so it
 * covers the angle it truly covers. Its chunks and the render distance therefore decide nothing.</p>
 */
public final class ClientProbeFall {

    /** Where a drawn fall stands from an eye: the true direction, and the distance it is drawn at. Immutable. */
    public static final class SkyPoint {
        /** Unit vector from the eye toward the falling body. */
        public final Vec3d direction;
        /** How far from the eye it is drawn, blocks: inside the far plane. */
        public final double drawDistance;
        /** How far from the eye it truly is, blocks. */
        public final double trueDistance;
        /** Ticks since its entry, the frame's fraction included. */
        public final double time;

        SkyPoint(Vec3d direction, double drawDistance, double trueDistance, double time) {
            this.direction = direction;
            this.drawDistance = drawDistance;
            this.trueDistance = trueDistance;
            this.time = time;
        }

        /** The point it is drawn at, relative to the eye. */
        public Vec3d drawnOffset() {
            return direction.scale(drawDistance);
        }

        /** How much a size at its true point is scaled by to cover the same angle where it is drawn. */
        public double scale() {
            return drawDistance / trueDistance;
        }
    }

    private ProbeFall.Fall fall;

    /** The server says a fall is coming down in this world. */
    public void told(ProbeFall.Fall fall) {
        this.fall = fall;
    }

    /** The fall's placement over the world; empty while none was told. */
    public Optional<FallPlacement> placement() {
        return fall == null ? Optional.empty() : Optional.of(fall.placement());
    }

    /**
     * Where the falling body stands in the sky of an eye at {@code eye}, {@code time} ticks into this world's
     * clock, drawn no further than {@code farthest} blocks; empty outside the fall — before its entry, after
     * its landing, or with none told.
     */
    public Optional<SkyPoint> skyPoint(Vec3d eye, double worldTime, double farthest) {
        if (fall == null) {
            return Optional.empty();
        }
        FallPlacement placement = fall.placement();
        double t = worldTime - fall.entryTick;
        if (t < 0d || t > placement.ticks()) {
            return Optional.empty();
        }
        Vec3d offset = placement.at(t).subtract(eye);
        double distance = offset.lengthVector();
        if (!(distance > 0d)) {
            return Optional.empty();
        }
        return Optional.of(new SkyPoint(offset.scale(1d / distance), Math.min(distance, farthest), distance, t));
    }
}
