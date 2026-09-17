package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest space subsystem-status} — whether the space stack is up, and what it
 * is holding.
 *
 * <p>Measured 2026-09-17: twelve classes ask this verb across fifty-three call sites, and the same
 * {@code contains("\"registered\":true")} appears in ten of them. That needle is the reason this
 * reader exists rather than a constant: {@code registered} is a field name FIVE other producers
 * also use — the wear capability, the dimension-registration mixin, the sound registry — so a
 * substring answers it off whatever reply is nearest to hand.</p>
 *
 * <h2>{@code transits} counts two different products</h2>
 *
 * <p>{@link #transits} is every jump in flight; {@link #transitsParked} is the subset carrying a
 * real hull parked in hyperspace rather than a block snapshot to paste at the far end. A restored
 * jump reports the same {@code transits} either way, and the two resume differently — one flies the
 * ship you were in, the other rebuilds a copy of it.</p>
 *
 * <h2>The slot ids are reported because they MOVE</h2>
 *
 * <p>{@link #slotDims()} are minted from whatever dimension ids were free at registration, so a
 * restart can hand the pool a different set: anything that persisted a slot id across a restart is
 * checked against this list rather than assumed. {@link #slotDimsAlsoBodies()} must always be empty
 * — an id owned by both the pool and the universe registry makes the registry describe a planet
 * whose world is empty space — so the reader offers it as its own question.</p>
 */
public final class SubsystemStatus {

    /** How this reader reaches the probe. The callers sit under three different bases. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** Whether the space stack is registered at all — the question ten sites asked by substring. */
    public final boolean registered;
    /** How many slot worlds the pool holds. */
    public final int pool;
    /** How many ships the ledger knows, or {@code -1} when there is no ledger to ask. */
    public final int ledger;
    /** Jumps in flight, and the subset of them carrying a real parked hull. See the class note. */
    public final int transits;
    public final int transitsParked;
    /** Whether an armed save fault is still armed — what says a WORLD autosave has not fired yet. */
    public final boolean saveFaultArmed;

    private final Reply reply;
    private final String raw;

    private SubsystemStatus(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        this.registered = reply.bool("registered", false);
        this.pool = reply.integer("pool");
        this.ledger = reply.integer("ledger");
        this.transits = reply.integer("transits");
        this.transitsParked = reply.integer("transitsParked");
        this.saveFaultArmed = reply.bool("saveFaultArmed", false);
    }

    /**
     * Read one {@code subsystem-status} reply, or refuse.
     *
     * <p>Refuses a reply that is not this verb's. The field this class is mostly asked about —
     * {@code registered} — is spelled by several other producers, so a reply from any of them would
     * otherwise answer here and the answer would look exactly right.</p>
     */
    public static SubsystemStatus of(String statusReply) {
        String text = String.valueOf(statusReply);
        Reply reply = Reply.of("artest space subsystem-status", text);
        if (!reply.has("registered") || !reply.has("pool")) {
            throw new AssertionError("this is not an `artest space subsystem-status` answer: the"
                    + " space stack's status carries `registered` beside `pool`, and `registered`"
                    + " alone is a field five other verbs also write: " + text);
        }
        return new SubsystemStatus(reply, text);
    }

    /** Ask the server what its space stack is doing. */
    public static SubsystemStatus read(Probe probe) throws Exception {
        return of(probe.exec("artest space subsystem-status"));
    }

    /** This reading, refusing when the stack is not registered — nothing below means anything then. */
    public SubsystemStatus requireRegistered(String what) {
        if (!registered) {
            ArrangementFailure.arrangementFailed(what + " — the space subsystem is not registered on"
                    + " this server, so every count below is about a stack that does not exist: "
                    + raw);
        }
        return this;
    }

    /** The slot worlds' dimension ids, in the pool's own order. See the class note. */
    public int[] slotDims() {
        return reply.intArray("slotDims");
    }

    /**
     * Slot ids the universe registry ALSO holds a body for — always empty, and its emptiness is an
     * invariant a caller asserts rather than a list it reads.
     */
    public int[] slotDimsAlsoBodies() {
        return reply.intArray("slotDimsAlsoBodies");
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "subsystem registered=" + registered + " pool=" + pool + " ledger=" + ledger
                + " transits=" + transits + " (" + transitsParked + " parked)";
    }
}
