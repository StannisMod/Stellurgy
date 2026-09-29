package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest space ledger-get <shipUuid>} — what the production ship ledger holds
 * about one craft.
 *
 * <p>Measured 2026-09-17: seven classes ask this verb; five read fields out of the reply and spell
 * {@code found}, {@code cell}, {@code state}, {@code slotDim}, {@code slotBound},
 * {@code slotCell} and {@code slotWorldLoaded} between them. The ledger is the server's record of
 * where a ship IS, so every login- and restart-restore test in the tier reads it, and the reply is
 * the only thing that can tell "the record says SETTLED at cell K" from "there is no record".</p>
 *
 * <h2>{@code found:false} is the whole reply</h2>
 *
 * <p>A ship the ledger does not know answers {@code {"found":false}} — no cell, no state, no slot.
 * A caller reaching for {@code state} on that gets absence, and absence compared against
 * {@code "SETTLED"} is an inequality: the test then reports that the ship is in the WRONG STATE,
 * which is a claim about the ledger having an entry. So {@link #cellKey()} and the rest refuse when
 * nothing was found, and {@link #found} is the question that answers.</p>
 *
 * <h2>The ENTRY's cell and the SLOT's cell are two different addresses</h2>
 *
 * <p>{@link #cellKey()} is where the ledger says the ship is. {@link #slotCell()} is the cell the
 * slot world it was given is BOUND to, and the two agreeing is a property under test rather than an
 * assumption — a ship whose entry moved without its slot following is exactly the seam these tests
 * exist for. The producer writes the empty string for an unbound slot, so that accessor refuses
 * instead of handing back {@code ""}.</p>
 *
 * <h2>{@code slotDim} is the word {@code null} when no slot is bound</h2>
 *
 * <p>Not absent and not a number: the probe writes {@code null} there for
 * {@code UNBOUND_SLOT}, deliberately, because the sentinel is not a dimension id and handing it to
 * a dimension lookup would answer the right thing for the wrong reason. {@link #slotDim()} refuses
 * that, and {@link #slotBound} is the question.</p>
 */
public final class LedgerEntry {

    /** How this reader reaches the probe. The callers sit under four different bases. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** What the producer writes in {@code slotDim} when no slot is bound. See the class note. */
    private static final String UNBOUND = "null";

    /** Whether the ledger holds an entry for the ship at all. */
    public final boolean found;
    /** Whether a slot world is bound to the entry's cell right now. */
    public final boolean slotBound;
    /** Whether that slot world is loaded. {@code false} for an unbound slot, which is not a world. */
    public final boolean slotWorldLoaded;

    private final Reply reply;
    private final String raw;

    private LedgerEntry(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        this.found = reply.bool("found");
        // Read only under `found`. The producer's not-found shape is `{"found":false}` and
        // carries neither field, so a default here would answer "no slot is bound" about a
        // ship the ledger has never heard of — two different findings under one false.
        this.slotBound = found && reply.bool("slotBound");
        this.slotWorldLoaded = found && reply.bool("slotWorldLoaded");
    }

    /**
     * Read one {@code ledger-get} reply, or refuse.
     *
     * <p>Refuses the two error shapes. {@code production ledger not live} says the space stack is
     * not up — which read as this reply is a ship the ledger has no record of, i.e. the single
     * strongest claim these tests make; and {@code bad uuid} says the CALLER passed something that
     * is not a ship id, which read the same way is a ship that was never registered.</p>
     */
    public static LedgerEntry of(String ledgerReply) {
        String text = String.valueOf(ledgerReply);
        Reply reply = Reply.of("artest space ledger-get", text);
        if (reply.has("error")) {
            ArrangementFailure.arrangementFailed("`artest space ledger-get` could not answer ("
                    + reply.text("error") + "), so this is not a reading of the ledger — and read as"
                    + " one it is a ship the ledger has never heard of: " + text);
        }
        if (!reply.has("found")) {
            throw new AssertionError("this is not an `artest space ledger-get` answer: it carries no"
                    + " `found`: " + text);
        }
        return new LedgerEntry(reply, text);
    }

    /** Ask the production ledger about one ship. */
    public static LedgerEntry forShip(Probe probe, String shipId) throws Exception {
        return of(probe.exec("artest space ledger-get " + shipId));
    }

    /** This reading, refusing when the ledger holds no entry for the ship. */
    public LedgerEntry requireFound(String what) {
        if (!found) {
            ArrangementFailure.arrangementFailed(what + " — the production ledger holds no entry for"
                    + " that ship, so it has no cell and no state: " + raw);
        }
        return this;
    }

    /**
     * Where the ledger says the ship is, as the producer's own cell key.
     *
     * <p>Refuses an EMPTY key as well as an absent one: two call sites checked for both, and an
     * empty string compares unequal to every real address, so it reads as a ship recorded
     * somewhere else rather than as a record with no address in it.</p>
     */
    public String cellKey() {
        String value = entry("cell");
        if (value == null || value.isEmpty()) {
            ArrangementFailure.arrangementFailed("the ledger's entry for this ship carries no cell"
                    + " key, so there is no address to compare against: " + raw);
        }
        return value;
    }

    /** What the ledger says it is doing — {@code SETTLED}, and the other states of its own enum. */
    public String state() {
        return entry("state");
    }

    /** Whether the entry's state is {@code expected}, asked of the FIELD. */
    public boolean stateIs(String expected) {
        return found && expected.equals(reply.text("state"));
    }

    /** The dimension id of the slot world bound to the entry's cell. Refuses an unbound slot. */
    public int slotDim() {
        requireEntry("slotDim");
        // absence is the answer: an unbound slot is reported as the producer's own sentinel,
        // and the refusal below names it rather than reading it as a dimension.
        String value = reply.textOr("slotDim", null);
        if (value == null || UNBOUND.equals(value)) {
            ArrangementFailure.arrangementFailed("no slot world is bound to this ship's cell, so"
                    + " there is no slot dimension — the probe answers the four characters \"null\""
                    + " there, which is not a dimension id: " + raw);
        }
        return reply.integer("slotDim");
    }

    /**
     * The cell the bound slot world is bound TO, which is not necessarily the entry's own cell.
     * See the class note. Refuses when nothing is bound.
     */
    public String slotCell() {
        requireEntry("slotCell");
        // absence is the answer, as in slotDim above: nothing bound, nothing to name.
        String value = reply.textOr("slotCell", null);
        if (value == null || value.isEmpty()) {
            ArrangementFailure.arrangementFailed("no slot world is bound to this ship's cell, so"
                    + " there is no slot cell key — the probe answers the empty string there: "
                    + raw);
        }
        return value;
    }

    private String entry(String field) {
        requireEntry(field);
        return reply.text(field);
    }

    private void requireEntry(String field) {
        if (!found) {
            throw new AssertionError("the production ledger holds no entry for this ship, so `"
                    + field + "` is not something it has — an absence here reads as a ship recorded"
                    + " in the wrong place: " + raw);
        }
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return found
                ? "ledger " + reply.reported("state") + " at " + reply.reported("cell")
                        + " slot=" + reply.reported("slotDim") + " (" + reply.reported("slotCell") + ")"
                : "ledger holds no entry for this ship";
    }
}
