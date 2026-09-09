package zmaster587.advancedRocketry.test.trace;

/**
 * How far the last re-seat moved a body: the deck's own step out from under it.
 *
 * <p>Zero on a still ship, one tick of ship motion at the body's radius on a moving one. A relog
 * scenario polls the MAXIMUM over a window — a body that comes back on a moving deck must be
 * carried by the pose pass, not dragged by it, and a step far larger than one tick of ship motion
 * is that drag.</p>
 *
 * <p><b>Where this came from.</b> Production kept it as {@code ShipFrameTravel.lastReseatStep}, a
 * {@code public static volatile} written on every re-seat of every body — and to write it,
 * production computed three deltas it had no other use for. Both the field and the arithmetic are
 * gone; {@link zmaster587.advancedRocketry.test.mixin.MixinShipFrameReseatStep} captures the seat
 * point production DOES need and measures the step against the body's position before the move.</p>
 *
 * <p>Latest-value, shared across scenarios on one JVM — as the production field was. Test source
 * set: absent from a released jar.</p>
 */
public final class DeckReseatState {

    private DeckReseatState() {}

    /** The distance of the most recent re-seat, in blocks. */
    public static volatile double lastReseatStep = 0.0;

    /** Note one re-seat: {@code seat} is where the body is being put, {@code from*} where it was. */
    public static void noteReseat(double[] seat, double fromX, double fromY, double fromZ) {
        if (seat == null) {
            return;
        }
        double dx = seat[0] - fromX;
        double dy = seat[1] - fromY;
        double dz = seat[2] - fromZ;
        lastReseatStep = Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
