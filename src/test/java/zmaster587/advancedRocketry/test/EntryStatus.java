package zmaster587.advancedRocketry.test;

/**
 * One reading of {@code artest space entry-status} — where a craft stands in the space-entry ledger.
 *
 * <h2>The reply has TWO shapes and the second one is an ANSWER, not a failure</h2>
 *
 * <p>Asked by id ({@code entry-status id <shipId>}) it reports whether the ledger has a row for that
 * craft — {@link #found} — and only then does it carry a cell, a state and a slot. <b>A
 * {@code found:false} is a legitimate reading about the world</b>: the craft has not entered, or has
 * left. Every field below is therefore reported as ABSENT rather than as a default when no row was
 * returned, and {@link #requireFound} is how a caller that needs the row says so.</p>
 *
 * <p>Asked without an id it reports the ledger as a whole — {@link #ships} and {@link #pending} —
 * and describes the FIRST row of the snapshot, which is a different question with the same field
 * names. A caller that means one particular craft asks by id; that is what
 * {@link #forShip(Probe, String)} is.</p>
 *
 * <h2>The cell address is a KEY and three local offsets, and they are not interchangeable</h2>
 *
 * <p>{@link #cellKey} names the cell; {@link #lx}/{@link #ly}/{@link #lz} are the craft's offsets
 * INSIDE it. A test that compares a key against another key is asking about which cell; one that
 * compares offsets is asking where in a cell — and the second is meaningless across two different
 * cells.</p>
 */
public final class EntryStatus {

    /** How this reader reaches the probe. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** How many rows the entry ledger holds. */
    public final int ships;
    /** How many craft the controller is still carrying INTO a cell, or {@code -1} if unreported. */
    public final int pending;
    /**
     * Whether the ledger had a row for the craft that was asked about.
     *
     * <p>For the whole-ledger form this is whether the snapshot was non-empty, and the fields below
     * then describe its FIRST row — see the class note.</p>
     */
    public final boolean found;
    /** The craft the row belongs to, or {@code null} when there is no row. */
    public final String shipId;
    /** The ledger state — {@code SETTLED}, {@code ENTERING}… — or {@code null} with no row. */
    public final String state;
    /** The cell the craft is in, as a key, or {@code null} with no row. */
    public final String cellKey;
    /** The craft's offsets INSIDE that cell. {@link Long#MIN_VALUE} with no row. */
    public final long lx;
    public final long ly;
    public final long lz;
    /** The slot dimension the cell is materialized as, or {@link Integer#MIN_VALUE} with no row. */
    public final int slotDim;
    /** Whether that slot is bound to this cell. */
    public final boolean slotBound;

    private final String raw;

    private EntryStatus(Reply reply, String raw) {
        this.raw = raw;
        this.ships = reply.integerOr("ships", -1);
        this.pending = reply.integerOr("pending", -1);
        this.found = reply.bool("found", false);
        this.shipId = reply.text("shipId");
        this.state = reply.text("state");
        this.cellKey = reply.text("cellKey");
        this.lx = (long) reply.numberOr("lx", Double.NaN);
        this.ly = (long) reply.numberOr("ly", Double.NaN);
        this.lz = (long) reply.numberOr("lz", Double.NaN);
        this.slotDim = reply.integerOr("slotDim", Integer.MIN_VALUE);
        this.slotBound = reply.bool("slotBound", false);
    }

    /**
     * Read one {@code entry-status} reply, or refuse.
     *
     * <p>Refuses the {@code error} replies — no space subsystem on this server, a malformed id —
     * because either would otherwise read as {@code found:false}, which is a statement about the
     * CRAFT. (Until 2026-09-18 there was a third: the verb gated on a probe static that only
     * {@code entry-setup} filled, so a live ledger holding the row still answered "entry not set
     * up". The verb reads the live stack now, and this refusal is about the world again.)</p>
     */
    public static EntryStatus of(String statusReply) {
        Reply reply = Reply.of("artest space entry-status", String.valueOf(statusReply));
        if (!reply.bool("ok", false)) {
            ArrangementFailure.arrangementFailed("the entry ledger does not answer about its own"
                    + " status, so a `found:false` here would be a claim about the craft rather than"
                    + " about the reply: " + statusReply);
        }
        return new EntryStatus(reply, String.valueOf(statusReply));
    }

    /** Ask the ledger about ONE craft, by the durable name it entered under. */
    public static EntryStatus forShip(Probe probe, String shipId) throws Exception {
        return of(probe.exec("artest space entry-status id " + shipId));
    }

    /** Ask about the ledger as a whole — see the class note on what the row fields then describe. */
    public static EntryStatus wholeLedger(Probe probe) throws Exception {
        return of(probe.exec("artest space entry-status"));
    }

    /** Whether this row says the craft has SETTLED in its cell. */
    public boolean settled() {
        return "SETTLED".equals(state);
    }

    /**
     * This reading, refusing when the ledger holds no row for the craft.
     *
     * <p>Separate from {@link #found} because both are legitimate: a test WAITING for an entry polls
     * the flag, and a test that has already established the entry and now wants the row calls this.
     * The second must not silently read a cell key of {@code null} into a command string.</p>
     */
    public EntryStatus requireFound(String what) {
        if (!found) {
            ArrangementFailure.arrangementFailed(what + " — the entry ledger holds no row for it."
                    + " The ledger has " + ships + " row(s) and " + pending + " craft still"
                    + " entering: " + raw);
        }
        return this;
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        if (!found) {
            return "no entry row (ledger holds " + ships + ", " + pending + " entering)";
        }
        return "entry " + shipId + " " + state + " @" + cellKey + " +" + lx + "," + ly + "," + lz
                + " slotDim=" + slotDim + (slotBound ? "" : " (slot NOT bound)");
    }
}
