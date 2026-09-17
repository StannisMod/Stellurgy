package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest nav status <dim> <x> <y> <z>} — what a navigation computer is aimed
 * at, and what it is holding.
 *
 * <p>Measured 2026-09-17: seven classes read this reply across twenty-four call sites.</p>
 *
 * <h2>The target is a PAIR, and reading either half as the whole is the mistake</h2>
 *
 * <p>{@link #targetDim} is WHICH BODY the ship is aimed at — the durable half. {@link #targetCell()}
 * is where the computer predicts that body will BE on arrival, and it moves while the body orbits.
 * A caller asking whether the destination is somewhere a ship can put down must ask about the body:
 * the predicted cell holds it only at arrival, so reading {@code cell-info} on it now says nothing
 * about what is there.</p>
 *
 * <h2>The offsets beside the absolute positions are components, not a miss</h2>
 *
 * <p>{@link #aimMissNow()} is the miss. {@code targetLocal} and {@code bodyNowLocal} are in-cell
 * components kept so a caller can see WHICH part moved — and they were once differenced as though
 * they were the miss, which worked while a moon moved inside its parent's cell and reported zero on
 * a broken build and a working one alike once the cell began riding the moon. This reader hands
 * back the miss and leaves the components to a caller that names them.</p>
 */
public final class NavStatus {

    /** How this reader reaches the probe. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** Whether the computer is linked to a ship at all. */
    public final boolean linked;
    /** WHICH BODY it is aimed at. See the class note: this is the durable half of the target. */
    public final int targetDim;
    /** Whether the aim resolved to a place the ship can be flown to. */
    public final boolean targetResolved;
    /** Whether that body is a descent target, and whether it is one of the pool's slot worlds. */
    public final boolean targetDescendTarget;
    public final boolean targetSlotWorld;
    /** Whether the drive is armed for the jump. */
    public final boolean armed;
    /** How many crystals the computer holds for the ship, and how many the source crystal carries. */
    public final int shipCrystals;
    public final int sourceCrystals;
    /** The space clock this reading was taken at, and the sync channel the computer talks on. */
    public final long spaceClock;
    public final int channel;

    private final Reply reply;
    private final String raw;

    private NavStatus(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        this.linked = reply.bool("linked", false);
        this.targetDim = reply.integer("targetDim");
        this.targetResolved = reply.bool("targetResolved", false);
        this.targetDescendTarget = reply.bool("targetDescendTarget", false);
        this.targetSlotWorld = reply.bool("targetSlotWorld", false);
        this.armed = reply.bool("armed", false);
        this.shipCrystals = reply.integer("ship");
        this.sourceCrystals = reply.integer("source");
        this.spaceClock = (long) reply.number("spaceClock");
        this.channel = reply.integer("channel");
    }

    /**
     * Read one {@code nav status} reply, or refuse.
     *
     * <p>Refuses the verb's error shapes — no such dimension, no navigation computer at that
     * position — because both leave every field below absent, and absence reads as an unlinked,
     * unarmed computer aimed at nothing, which is a description of a real state.</p>
     */
    public static NavStatus of(String statusReply) {
        String text = String.valueOf(statusReply);
        Reply reply = Reply.of("artest nav status", text);
        if (reply.has("error")) {
            ArrangementFailure.arrangementFailed("`artest nav status` found no navigation computer to"
                    + " report on (" + reply.text("error") + "), so an unlinked, unaimed reading here"
                    + " would be about the reply rather than about a computer: " + text);
        }
        if (!reply.has("linked")) {
            throw new AssertionError("this is not an `artest nav status` answer: it carries no"
                    + " `linked`: " + text);
        }
        return new NavStatus(reply, text);
    }

    /** Ask the computer standing at this position what it is aimed at. */
    public static NavStatus at(Probe probe, int dim, int x, int y, int z) throws Exception {
        return of(probe.exec("artest nav status " + dim + " " + x + " " + y + " " + z));
    }

    /**
     * Where the computer PREDICTS the aimed body will be on arrival, or {@code null} when it is
     * aimed at nothing. See the class note: this is not the body's address now.
     */
    public String targetCell() {
        return reply.text("target");
    }

    /** Whether the computer is aimed at anything at all. */
    public boolean hasTarget() {
        return targetCell() != null;
    }

    /** What kind of body it is aimed at, as the universe registry names the kind. */
    public String targetKind() {
        return reply.text("targetKind");
    }

    /** How far the aim currently misses the body it names, or {@code NaN} when there is no aim. */
    public double aimMissNow() {
        return reply.number("aimMissNow");
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "nav linked=" + linked + " targetDim=" + targetDim + " resolved=" + targetResolved
                + " armed=" + armed + " target=" + targetCell();
    }
}
