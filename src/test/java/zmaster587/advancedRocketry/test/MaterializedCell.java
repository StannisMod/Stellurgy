package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest space occupy <sx> <sy> <sz>} — the slot world a galactic cell was
 * just materialized into.
 *
 * <p>Measured 2026-09-17: five classes ask this verb, four read fields out of the reply, and between
 * them they spell {@code ok}, {@code slotDim}, {@code worldLoaded}, {@code exhausted} and
 * {@code cellKey}.</p>
 *
 * <h2>The REFUSAL carries no slot, and a missing slot reads as the overworld</h2>
 *
 * <p>A pool with nothing left answers {@code {"ok":false,"exhausted":true}} — no {@code slotDim} at
 * all. {@code Reply.integerOr(…, 0)} then answers {@code 0}, which is a real dimension id and the
 * one every fixture is standing in; a caller that goes on to force-tick or load "the cell's world"
 * is operating on the overworld while its message says the cell. {@link #slotDim()} refuses the
 * exhausted reply, and {@link #exhausted} is the question it is the answer to.</p>
 *
 * <h2>{@code worldLoaded} is the point of the verb, and it is NOT {@code ok}</h2>
 *
 * <p>The manager's own bookkeeping says "this cell is live in slot N"; {@code worldLoaded} says
 * whether a {@code WorldServer} for that id actually exists. When the two disagree, every consumer
 * that resolves a ship's world through the binding gets a dimension id with nothing behind it —
 * which is why the producer reports both, and why this reader keeps them apart.</p>
 *
 * <h2>{@code worldLoaded} is also written by {@code space cell-slot}</h2>
 *
 * <p>A different verb, read in its own class. A substring search for {@code worldLoaded} answers off
 * whichever of the two replies is nearest to hand, so this reader is for {@code space occupy} and
 * refuses a reply carrying no {@code ok}.</p>
 */
public final class MaterializedCell {

    /** How this reader reaches the probe. The callers sit under three different bases. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** Whether the cell was materialized at all. */
    public final boolean ok;
    /** Whether the pool had no slot left to give. See the class note. */
    public final boolean exhausted;

    private final Reply reply;
    private final String raw;

    private MaterializedCell(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        this.ok = reply.ok();
        // absence is the answer: the producer writes `exhausted` ONLY beside `ok:false`, so a
        // reply that does not carry it is a pool that had a slot to give.
        this.exhausted = reply.boolOr("exhausted", false);
    }

    /**
     * Read one {@code space occupy} reply, or refuse.
     *
     * <p>Refuses {@code {"error":"space subsystem not registered"}}: that reply carries no
     * {@code ok} and no {@code exhausted}, so read as this one it is a cell that simply failed to
     * materialize — which is a statement about the pool, and the stack is not even up.</p>
     */
    public static MaterializedCell of(String occupyReply) {
        String text = String.valueOf(occupyReply);
        Reply reply = Reply.of("artest space occupy", text);
        if (reply.has("error")) {
            ArrangementFailure.arrangementFailed("`artest space occupy` could not run ("
                    + reply.text("error") + "), so no cell was materialized and nothing here is a"
                    + " reading of a slot: " + text);
        }
        if (!reply.has("ok")) {
            throw new AssertionError("this is not an `artest space occupy` answer: it carries no"
                    + " `ok`, and `space cell-slot` writes a `worldLoaded` of its own: " + text);
        }
        return new MaterializedCell(reply, text);
    }

    /** Materialize one cell, given as {@code <sx> <sy> <sz>}. */
    public static MaterializedCell at(Probe probe, String cell) throws Exception {
        return of(probe.exec("artest space occupy " + cell));
    }

    /** This reading, refusing when the cell was not materialized. */
    public MaterializedCell requireMaterialized(String what) {
        if (!ok) {
            ArrangementFailure.arrangementFailed(what + " — the cell was not materialized"
                    + (exhausted ? " (the slot pool is exhausted)" : "") + ": " + raw);
        }
        return this;
    }

    /** The dimension id of the slot world the cell is live in. Refuses a refusal. */
    public int slotDim() {
        if (!ok || !reply.has("slotDim")) {
            ArrangementFailure.arrangementFailed("this cell was not materialized"
                    + (exhausted ? " (the slot pool is exhausted)" : "") + ", so it has no slot"
                    + " dimension — and a default of 0 here is the OVERWORLD: " + raw);
        }
        return reply.integer("slotDim");
    }

    /**
     * Whether a {@code WorldServer} for that slot really exists. See the class note: this is not
     * {@link #ok}, and the two disagreeing is the defect the field was added for.
     */
    public boolean worldLoaded() {
        if (!ok) {
            throw new AssertionError("this cell was not materialized, so there is no slot world to"
                    + " ask about — and `false` here would read as a binding whose world went away: "
                    + raw);
        }
        return reply.bool("worldLoaded");
    }

    /** The cell key the manager materialized, as the producer's own address. */
    public String cellKey() {
        requireMaterialized("cellKey");
        return reply.text("cellKey");
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return ok
                ? "cell " + reply.reported("cellKey") + " live in slot " + reply.reported("slotDim")
                        + " world=" + reply.reported("worldLoaded")
                : exhausted ? "the slot pool is exhausted" : "the cell was not materialized";
    }
}
