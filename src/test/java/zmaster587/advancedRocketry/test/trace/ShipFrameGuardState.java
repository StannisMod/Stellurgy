package zmaster587.advancedRocketry.test.trace;

/**
 * What the external-move guard measured on its most recent pass on THIS side.
 *
 * <p>A plain class rather than a field on either mixin, for the reason Mixin enforces: a mixin may
 * not carry a non-private static, because its members are merged into whatever it was applied to and
 * a shared name would land in two unrelated classes. State that two observation points both need
 * therefore lives outside both — the same arrangement, and the same reason, as
 * {@link DeckPoseTraceState}.</p>
 *
 * <p><b>Why a holder at all, when the guard already writes a record.</b> The per-tick deck-pose
 * trace asks its question about a CRAFT, at the craft's own tick, and the guard's step and allowance
 * belong in that row: "a step and the allowance it was judged against, side by side" is the
 * comparison whose absence made three tuning attempts guesswork. Joining two record streams by tick
 * would put them in two rows. So the guard's record — which is the durable, per-body, windowable
 * answer — is written as well, and this holds the latest for the one reader that needs it inline.</p>
 *
 * <p>What it is NOT: a substitute for the record. A reader asking "what did the guard do during my
 * window", "to which body", or "how many times" must read {@code deck_guard_pass}; this answers only
 * "what was the last one", which is exactly as much as the production statics it replaces answered,
 * and it says so.</p>
 *
 * <p>Per side, because an integrated game runs both in one JVM and the client's guard and the
 * server's are different subjects. Test source set: absent from a released jar.</p>
 */
public final class ShipFrameGuardState {

    private ShipFrameGuardState() {}

    /** {@code [frameStep, allowed, carrySeen]} of the last pass, per side. Index 0 = client. */
    private static final double[][] LAST = {{0.0, -1.0, -1.0}, {0.0, -1.0, -1.0}};

    /** Record a guard pass. {@code remote} selects the side, as {@code world.isRemote} does. */
    public static void note(boolean remote, double frameStep, double allowed, double carrySeen) {
        LAST[remote ? 0 : 1] = new double[]{frameStep, allowed, carrySeen};
    }

    /** {@code [frameStep, allowed, carrySeen]} of the last pass on this side — never null; the
     *  initial {@code -1} allowances say "no pass has happened", which is the same sentinel the
     *  statics this replaces started at. */
    public static double[] last(boolean remote) {
        return LAST[remote ? 0 : 1];
    }
}
