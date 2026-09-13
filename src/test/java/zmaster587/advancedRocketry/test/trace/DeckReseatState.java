package zmaster587.advancedRocketry.test.trace;

/**
 * How far a re-seat moved a body: the deck's own step out from under it.
 *
 * <p>Zero on a still ship, one tick of ship motion at the body's radius on a moving one. A relog
 * scenario reads the LARGEST such step over a window — a body that comes back on a moving deck must
 * be carried by the pose pass, not dragged by it, and a step far larger than one tick of ship motion
 * is that drag.</p>
 *
 * <p><b>Where this came from.</b> Production kept it as {@code ShipFrameTravel.lastReseatStep}, a
 * {@code public static volatile} written on every re-seat of every body — and to write it,
 * production computed three deltas it had no other use for. Both the field and the arithmetic are
 * gone; {@link zmaster587.advancedRocketry.test.mixin.MixinShipFrameReseatStep} captures the seat
 * point production DOES need and measures the step against the body's position before the move.</p>
 *
 * <p><b>Why the number is no longer readable as a field.</b> It used to be a latest-value static a
 * test read across the socket, which answers with whatever the last pass on EITHER side left there:
 * it cannot say which pass the step belonged to, nor whether any pass ran during the reader's
 * window at all. The step now leaves on the pass's own record ({@code deck_reseat_pass}, carrying
 * {@code maxStep}), so a reader takes the maximum over the passes that happened inside its own
 * marks, and a window with no passes is an absence rather than a stale number.</p>
 *
 * <p><b>The one piece of state, and what owns it.</b> The redirect fires per BODY, inside the pass;
 * the record is written at the pass's return. The accumulator between them is owned by the THREAD
 * running that pass — client and server both run one, and a shared field would mix them — and it is
 * released by {@link #takePassMax()}, which every pass calls exactly once whether or not it moved
 * anything. Its lifetime is therefore one pass, not the JVM's.</p>
 *
 * <p>Test source set: absent from a released jar.</p>
 */
public final class DeckReseatState {

    private DeckReseatState() {}

    /** The largest step of the pass currently running on THIS thread. Released per pass. */
    private static final ThreadLocal<double[]> PASS_MAX = new ThreadLocal<double[]>() {
        @Override
        protected double[] initialValue() {
            return new double[]{0.0};
        }
    };

    /** Note one re-seat: {@code seat} is where the body is being put, {@code from*} where it was. */
    public static void noteReseat(double[] seat, double fromX, double fromY, double fromZ) {
        if (seat == null) {
            return;
        }
        double dx = seat[0] - fromX;
        double dy = seat[1] - fromY;
        double dz = seat[2] - fromZ;
        double step = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double[] cell = PASS_MAX.get();
        if (step > cell[0]) {
            cell[0] = step;
        }
    }

    /** The largest step since the last call, and reset — read once per pass, by its recorder. */
    public static double takePassMax() {
        double[] cell = PASS_MAX.get();
        double max = cell[0];
        cell[0] = 0.0;
        return max;
    }
}
