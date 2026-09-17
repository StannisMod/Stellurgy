package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest space transit-status} — what the jump subsystem is doing right now.
 *
 * <p>Measured 2026-09-17: ten classes read this reply's fields, four of them spelling more than one
 * name for themselves. It is also the reply most often pasted whole into a failure message, and that
 * use is left alone — printing a reply is not reading it.</p>
 *
 * <h2>The two mechanisms are reported SEPARATELY, in every state</h2>
 *
 * <p>{@link #inTransit} counts hyperspace flights; {@link #crossing} counts direct cell-to-cell
 * settles. The producer emits both always, so that "neither is running" is a pair of zeros rather
 * than a missing field — a test that wants to know WHICH mechanism its chosen speed selected reads
 * these, instead of inferring it from how long the jump took.</p>
 *
 * <h2>{@code crewDim} is the subsystem's answer, {@code hyperDim} is the world's id</h2>
 *
 * <p>{@link #crewDim} is where the crew of the in-flight ship BELONGS while it is parked, and it is
 * {@code -1} once the jump is over, or for a transit restored from a snapshot, which has no physical
 * ship anywhere. {@link #hyperDim} is the raw id of the shared parking world. A crew-side test
 * compares the CLIENT's dimension against these rather than hard-coding an id that is minted per
 * boot.</p>
 *
 * <h2>{@code reseating} tells a crew that was never seated from one that ran out of retries</h2>
 *
 * <p>Greater than zero means the arrival loop is still trying and the caller simply stopped ticking;
 * zero with an unseated crew means it either succeeded or gave up — and the arrival leg gives up
 * without a word, so nothing else distinguishes the two.</p>
 */
public final class TransitStatus {

    /** How this reader reaches the probe. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** How many HYPERSPACE flights are in the air. */
    public final int inTransit;
    /** How many DIRECT cell-to-cell crossings are settling. See the class note. */
    public final int crossing;
    /** The dimension the jump is aimed at. */
    public final int targetDim;
    /** Where the subsystem thinks the parked ship is. */
    public final long poseX;
    public final long poseY;
    public final long poseZ;
    /** The parked ship's own Y, and how far it sits from the pose above. */
    public final long shipY;
    public final long poseDist;
    /** Where the in-flight crew BELONGS, or {@code -1}. See the class note. */
    public final int crewDim;
    /** The raw id of the shared hyperspace parking world. */
    public final int hyperDim;
    /** How many arrived ships are still retrying their crew re-seat. See the class note. */
    public final int reseating;
    /**
     * Where the ships in the parking world actually are, as the probe's own point-free text.
     *
     * <p>Deliberately a STRING and not a parsed list: the producer builds it out of
     * {@code queryableShipPositions} for a human reading a failure, and a test that needs a ship's
     * position by identity has {@code vs ship-info} for exactly that.</p>
     */
    public final String ships;

    private final String raw;

    private TransitStatus(Reply reply, String raw) {
        this.raw = raw;
        this.inTransit = reply.integerOr("inTransit", -1);
        this.crossing = reply.integerOr("crossing", -1);
        this.targetDim = reply.integerOr("targetDim", Integer.MIN_VALUE);
        this.poseX = (long) reply.numberOr("poseX", Double.NaN);
        this.poseY = (long) reply.numberOr("poseY", Double.NaN);
        this.poseZ = (long) reply.numberOr("poseZ", Double.NaN);
        this.shipY = (long) reply.numberOr("shipY", Double.NaN);
        this.poseDist = (long) reply.numberOr("poseDist", Double.NaN);
        this.crewDim = reply.integerOr("crewDim", Integer.MIN_VALUE);
        this.hyperDim = reply.integerOr("hyperDim", Integer.MIN_VALUE);
        this.reseating = reply.integerOr("reseating", -1);
        this.ships = reply.textOr("ships", "");
    }

    /**
     * Read one {@code transit-status} reply, or refuse.
     *
     * <p>Refuses the {@code error} reply the probe sends when the transit stack was never set up:
     * that would otherwise read as {@code inTransit} absent, and an absent count is the same shape
     * as a jump that has landed.</p>
     */
    public static TransitStatus of(String statusReply) {
        Reply reply = Reply.of("artest space transit-status", String.valueOf(statusReply));
        if (!reply.bool("ok", false)) {
            ArrangementFailure.arrangementFailed("the transit stack does not answer about its own"
                    + " status, so a zero in-flight count here would be a claim about the jump"
                    + " rather than about the reply: " + statusReply);
        }
        return new TransitStatus(reply, String.valueOf(statusReply));
    }

    /** Ask the subsystem what it is doing. */
    public static TransitStatus read(Probe probe) throws Exception {
        return of(probe.exec("artest space transit-status"));
    }

    /**
     * Advance the transit by {@code ticks} and read what it reports afterwards.
     *
     * <p>An ACCELERATOR and not the thing that moves a jump: the server ticks a transit like any
     * other subsystem. It repeats the SAME tick — it does not change what a tick does — and it is
     * here because a long leg is faster to compress than to wait out.</p>
     */
    public static TransitStatus tick(Probe probe, int ticks) throws Exception {
        return of(probe.exec("artest space transit-tick " + ticks));
    }

    /** Whether anything at all is in flight, by either mechanism. */
    public boolean anythingInFlight() {
        return inTransit > 0 || crossing > 0;
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "transit inTransit=" + inTransit + " crossing=" + crossing + " targetDim=" + targetDim
                + " crewDim=" + crewDim + " hyperDim=" + hyperDim + " reseating=" + reseating;
    }
}
