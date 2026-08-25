package zmaster587.advancedRocketry.test.trace;

/**
 * What an arriving pose told the client, held between the two halves of the deck-pose trace.
 *
 * <p>A plain class rather than a field on either mixin, for a reason Mixin enforces: a mixin may not
 * carry a non-private static, because its members are merged into whatever it was applied to and a
 * shared name would land in two unrelated classes. State that two observation points both need
 * therefore lives outside both.</p>
 *
 * <p>Client thread only — the pose handler and the client tick both run there, and nothing else
 * touches it. Test source set: absent from a released jar.</p>
 */
public final class DeckPoseTraceState {

    private DeckPoseTraceState() {}

    /**
     * What arrived, PER CRAFT — keyed by the interpolator the pose was handed to.
     *
     * <p>It was one global flag until it was read: a shared world holds several craft, one message
     * carries every craft a player watches, and the handler is entered once per craft in it. So a
     * gap for the craft under test could be filled in by a pose for somebody else's, and the very
     * quantity this exists to measure — how often a pose fails to arrive — would read low for a
     * reason having nothing to do with the network. Keyed weakly: an unloading craft takes its
     * entry with it.</p>
     */
    private static final java.util.Map<Object, double[]> ARRIVED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, double[]>());

    /** Record a pose arriving for {@code craftKey} — the interpolator it was handed to. */
    public static void noteArrival(Object craftKey, double posY, double velY,
                                   double qw, double qx, double qy, double qz, double omega) {
        ARRIVED.put(craftKey, new double[]{1d, posY, velY, qw, qx, qy, qz, omega});
    }

    /** {@code [arrived, posY, velY, qw, qx, qy, qz, omega]} for this craft, CLEARING the arrival flag: the question is
     *  always "did one arrive for the tick being reported", never "has one ever arrived". */
    public static double[] takeArrival(Object craftKey) {
        double[] state = ARRIVED.get(craftKey);
        if (state == null) {
            return new double[]{0d, 0d, 0d, 1d, 0d, 0d, 0d, 0d};
        }
        double[] answer = state.clone();
        state[0] = 0d;
        return answer;
    }
}
