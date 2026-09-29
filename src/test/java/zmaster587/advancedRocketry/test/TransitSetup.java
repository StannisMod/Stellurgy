package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest space transit-setup-piloted} / {@code transit-setup-empty} — the cell
 * a jump departs from, and the craft standing in it.
 *
 * <p>Measured 2026-09-17, this is the second most widely parsed producer in the tier: eleven classes
 * ran one of the two verbs, and eight of them spelled its field names for themselves.</p>
 *
 * <h2>Two identities, and the setup is where they are taken</h2>
 *
 * <p>The reply names the craft TWICE — {@link #shipId} is the substrate's physics key,
 * {@link #durableId} the craft's own name — and they are separate fields because they are separate
 * things. <b>At the setup reply they currently hold one value</b>: the fixture assembles from the
 * pasted footprint and the flight computer inside it is found, so no second id is minted.
 * {@code VSShortJumpCrossesDirectlyE2ETest} asserts that equality rather than leaving it to a
 * paragraph, which is the only reason it can be relied on here.</p>
 *
 * <p><b>The distinction is real AFTER a crossing.</b> A crossing re-assembles the hull and mints a
 * new physics key; the durable name rides through. So the origin cell answers to either, and the far
 * end only to {@link #durableId}, through {@code vs ship-uuid}. Asking the far end by a physics key
 * taken before the crossing answers {@code found:false} — which is not a missing ship, it is the
 * wrong question.</p>
 *
 * <h2>Why the anchor is read rather than assumed</h2>
 *
 * <p>Every scenario in a class jumps out of the same pool slot and builds at the same anchor, so
 * "the ship at the anchor" is a question with several right answers and the nearest-ship lookup
 * returns the FIRST craft ever assembled there — departed, holding an empty shipyard. Measured twice
 * in independent boots as {@code seatFound:false} on a craft that had just been built. The anchor is
 * here to be PASSED to the departure, never to identify anything.</p>
 */
public final class TransitSetup {

    /** How this reader reaches the probe. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** The dimension the origin cell was materialized into. */
    public final int originDim;
    /** Where in that dimension the fixture was built — the departure's own coordinate. */
    public final int anchorX;
    public final int anchorY;
    public final int anchorZ;
    /**
     * The substrate's physics key for the craft, or {@code null} for the EMPTY setup, which builds
     * no craft at all. Absent is reported as absent: a caller that means to fly something must be
     * able to see that nothing was built.
     */
    public final String shipId;
    /** The craft's durable AR name — the one identity a crossing preserves. {@code null} as above. */
    public final String durableId;

    private final String raw;

    private TransitSetup(Reply reply, String raw) {
        this.raw = raw;
        this.originDim = reply.integer("originDim");
        // absence is the answer for everything below the dimension: the EMPTY setup answers
        // `{"ok":true,"originDim":N}` and nothing else — no anchor, no ids — because there
        // is no craft to name. `of` refuses a reply with no `originDim`, which is the one
        // field every shape of this verb carries.
        this.anchorX = reply.integerOr("anchorX", Integer.MIN_VALUE);
        this.anchorY = reply.integerOr("anchorY", Integer.MIN_VALUE);
        this.anchorZ = reply.integerOr("anchorZ", Integer.MIN_VALUE);
        this.shipId = emptyToNull(reply.textOr("shipId", null));
        this.durableId = emptyToNull(reply.textOr("durableId", null));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * Read one setup reply, or refuse.
     *
     * <p>Three refusals, and all three are ARRANGEMENT failures by type: the reply is not this
     * verb's, the setup reported an error, or it answered no {@code originDim}. A scenario whose
     * fixture never came up has disproved nothing about jumps, and the gate's XML records the type
     * where it cannot record a prose prefix.</p>
     */
    public static TransitSetup of(String setupReply) {
        Reply reply = Reply.of("artest space transit-setup", String.valueOf(setupReply));
        if (!reply.ok()) {
            ArrangementFailure.arrangementFailed("the transit setup did not build its origin cell,"
                    + " so nothing below departs from anywhere: " + setupReply);
        }
        if (!reply.has("originDim")) {
            ArrangementFailure.arrangementFailed("a transit setup must name the dimension it"
                    + " materialized, or the departure has no world to leave: " + setupReply);
        }
        return new TransitSetup(reply, String.valueOf(setupReply));
    }

    /** Run the PILOTED setup — an origin cell holding a craft — and read the answer. */
    public static TransitSetup piloted(Probe probe) throws Exception {
        return of(probe.exec("artest space transit-setup-piloted"));
    }

    /** Run the EMPTY setup — an origin cell with no craft in it — and read the answer. */
    public static TransitSetup empty(Probe probe) throws Exception {
        return of(probe.exec("artest space transit-setup-empty"));
    }

    /**
     * The craft's physics key, refusing when the setup built none.
     *
     * <p>Separate from the field so that a caller which MEANS to fly a craft cannot silently carry a
     * null into a command string, where it becomes the four characters {@code null} and the probe
     * answers about no ship at all.</p>
     */
    public String requireShipId() {
        if (shipId == null) {
            ArrangementFailure.arrangementFailed("this setup built no craft, so it names none — an"
                    + " EMPTY setup cannot be flown: " + raw);
        }
        return shipId;
    }

    /** The craft's durable name, refusing when the setup built none. @see #requireShipId() */
    public String requireDurableId() {
        if (durableId == null) {
            ArrangementFailure.arrangementFailed("this setup built no craft, so it minted no durable"
                    + " name — and that name is the only address that survives a crossing: " + raw);
        }
        return durableId;
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "transit setup dim=" + originDim + " anchor=" + anchorX + "," + anchorY + "," + anchorZ
                + (durableId == null ? " (empty)" : " craft=" + durableId);
    }
}
