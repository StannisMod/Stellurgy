package zmaster587.advancedRocketry.test.trace;

/**
 * What an arriving pose told the client, held BY THE INTERPOLATOR it was handed to — implemented on
 * the two VS interpolators by {@code MixinClientDeckPoseTrace}, read by
 * {@code MixinClientDeckPoseTickTrace}.
 *
 * <p>Per craft by construction: one message carries every craft a player watches and the handler is
 * entered once per craft, so an arrival kept anywhere shared would let one craft's pose answer for
 * another's silence — the very quantity the trace exists to measure. It lived in a weak map keyed by
 * the interpolator until 2026-09-23; the interpolator is the owner the key was standing in for.</p>
 *
 * <p>Test source set: absent from a released jar.</p>
 */
public interface PoseArrival {

    /** {@code [arrived, posY, velY, qw, qx, qy, qz, omega]} for this craft, CLEARING the arrival
     *  flag: the question is always "did one arrive for the tick being reported", never "has one
     *  ever arrived". */
    double[] arTest$takeArrival();

    /** The reading for a craft that has never had a pose delivered. */
    static double[] none() {
        return new double[]{0d, 0d, 0d, 1d, 0d, 0d, 0d, 0d};
    }
}
