package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest vs ship-frame-check [dim entityId]} — whether the two rotations AR
 * treats as one really are one, measured on demand for a NAMED body.
 *
 * <p>AR moves a body through the substrate's {@code ShipTransform.rotate} and levels the camera and
 * gravity by the attitude QUATERNION, and it assumes the two describe the same rotation. This verb
 * computes both images of the ship's local up and nose and reports how far apart they are, plus the
 * world↔subspace position and rotation round-trip errors.</p>
 *
 * <p>Measured 2026-09-17: two classes read this reply and spell six field names between them.</p>
 *
 * <h2>Every disagreement is a number that CONVERGES ON ZERO, and absence is zero too</h2>
 *
 * <p>The assertions on this reply are all of the form {@code disagreement < 1e-6} — that is what
 * "the two frames are one rotation" means. So a field that is not there passes: a reader answering
 * {@code 0} for an absent {@code upDisagreement} reports perfect consistency, and a reader
 * answering {@code NaN} fails the comparison for a reason that has nothing to do with frames.
 * Every accessor here refuses instead, which is the only reading a caller cannot mistake.</p>
 *
 * <h2>{@code available:false} is TWO different states</h2>
 *
 * <p>The producer answers it when the subject is not aboard a ship — and also when there is no
 * subject at all: no entity under that id, no world for that dim, or (in the no-argument form) no
 * player online. A test whose arrangement is "this crew member is aboard" cannot tell those apart
 * from the reply, so {@link #requireMeasured} names both possibilities in its refusal rather than
 * asserting the first.</p>
 */
public final class ShipFrameCheck {

    /** How this reader reaches the probe. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** Whether the check RAN at all. See the class note: {@code false} is two states, not one. */
    public final boolean available;

    private final Reply reply;
    private final String raw;

    private ShipFrameCheck(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        // The measured reply carries no `available` field; the refusal carries nothing else.
        this.available = !reply.has("available") || reply.bool("available", false);
    }

    /** Read one {@code vs ship-frame-check} reply, or refuse what is not one. */
    public static ShipFrameCheck of(String checkReply) {
        String text = String.valueOf(checkReply);
        Reply reply = Reply.of("artest vs ship-frame-check", text);
        if (!reply.has("available") && !reply.has("upDisagreement")) {
            throw new AssertionError("this is not an `artest vs ship-frame-check` answer: it carries"
                    + " neither the `available:false` refusal nor an `upDisagreement`: " + text);
        }
        return new ShipFrameCheck(reply, text);
    }

    /** Ask about the first player on the server. */
    public static ShipFrameCheck ofFirstPlayer(Probe probe) throws Exception {
        return of(probe.exec("artest vs ship-frame-check"));
    }

    /** Ask about one named body. */
    public static ShipFrameCheck byId(Probe probe, int dim, int entityId) throws Exception {
        return of(probe.exec("artest vs ship-frame-check " + dim + " " + entityId));
    }

    /**
     * This reading, refusing when the check did not run.
     *
     * <p>Names both states the producer collapses into one reply — see the class note. The
     * arrangement it stands for is "the measurement RAN, for this body", which is strictly more
     * than "the number is not a sentinel".</p>
     */
    public ShipFrameCheck requireMeasured(String what) {
        if (!available) {
            ArrangementFailure.arrangementFailed(what + " — the check did not run: either the"
                    + " subject is not aboard a ship, or there is no such subject at all (no entity"
                    + " under that id, no world for that dim, or no player online for the"
                    + " no-argument form). The reply says which of those it is about nothing: "
                    + raw);
        }
        return this;
    }

    /** How far apart the two images of the ship's local UP are. Converges on zero. */
    public double upDisagreement() {
        return measurement("upDisagreement");
    }

    /** The same for its NOSE. */
    public double fwdDisagreement() {
        return measurement("fwdDisagreement");
    }

    /** How far a world position lands from itself after a world→subspace→world round trip. */
    public double posRoundTripError() {
        return measurement("posRoundTripErr");
    }

    /** The same for a world VECTOR through the two rotate directions. */
    public double rotRoundTripError() {
        return measurement("rotRoundTripErr");
    }

    /** The world image of the ship's local up, by the attitude quaternion. */
    public double upQuatX() {
        return measurement("upQuatX");
    }

    public double upQuatY() {
        return measurement("upQuatY");
    }

    public double upQuatZ() {
        return measurement("upQuatZ");
    }

    /** The same up vector, by the substrate's own rotate — the other half of the comparison. */
    public double upRotX() {
        return measurement("upRotX");
    }

    public double upRotY() {
        return measurement("upRotY");
    }

    public double upRotZ() {
        return measurement("upRotZ");
    }

    /** The attitude quaternion itself. */
    public double qw() {
        return measurement("qw");
    }

    public double qx() {
        return measurement("qx");
    }

    public double qy() {
        return measurement("qy");
    }

    public double qz() {
        return measurement("qz");
    }

    private double measurement(String field) {
        requireMeasured(field + " is asked of a check that ran");
        double value = reply.number(field);
        if (Double.isNaN(value)) {
            throw new AssertionError("`artest vs ship-frame-check` carries no `" + field + "` —"
                    + " and every number in this reply is compared against a tolerance, so an"
                    + " absence reads as perfect agreement: " + raw);
        }
        return value;
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return available
                ? "frame check up=" + reply.text("upDisagreement")
                        + " fwd=" + reply.text("fwdDisagreement")
                        + " posRt=" + reply.text("posRoundTripErr")
                        + " rotRt=" + reply.text("rotRoundTripErr")
                : "the ship-frame check did not run";
    }
}
