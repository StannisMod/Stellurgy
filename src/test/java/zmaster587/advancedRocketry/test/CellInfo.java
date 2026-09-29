package zmaster587.advancedRocketry.test;

import java.util.ArrayList;
import java.util.List;

/**
 * One reading of {@code artest space cell-info} — what the universe registry holds at one galactic
 * cell.
 *
 * <h2>The reply carries TWO body lists and confusing them decides everything</h2>
 *
 * <p>{@link #systemBodies} is every body of the SYSTEM the cell belongs to; {@link #cellBodies} is
 * the bodies standing AT this cell. The producer takes deliberate trouble to report both, and says
 * why in its own comment: <i>"is there somewhere to land here" is a question about this cell, and
 * answering it off the system-wide list says yes as long as the system has a planet ANYWHERE in
 * it.</i></p>
 *
 * <p>So this class will not let a caller reach for "the bodies" — there is no such field. It also
 * keeps the two COUNTS the reply carries ({@link #bodiesAtCount}, {@link #systemBodyCount}) separate
 * from the lists, because the counts are what the registry answered and the lists are what it
 * serialised; a disagreement between them is a finding, not a rounding.</p>
 *
 * <h2>{@code dimCell} is only present when the caller asked about a dimension</h2>
 *
 * <p>The verb takes an optional trailing dim id and only then answers where that dimension sits.
 * Absent is reported as {@code null} rather than as an empty key: "I did not ask" and "that
 * dimension is placed nowhere" are different readings, and the second is a real state a first
 * navigation crystal can be pointed at.</p>
 */
public final class CellInfo {

    /** How this reader reaches the probe. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** One body as the registry reported it, in either list. */
    public static final class Body {
        /** The dimension id this body is, or would be, realized as. */
        public final int dim;
        /** The registry's own kind word for it — {@code PLANET}, {@code STAR}, {@code GAS_GIANT}… */
        public final String kind;
        /** The cell this body stands at, as a key. */
        public final String cell;
        /** Whether a descending craft may aim at it. */
        public final boolean descendTarget;
        /**
         * Whether {@link #dim} is one of the space subsystem's SLOT worlds rather than a real
         * planet. Reported without loading the world, so it is safe to ask about a body nobody has
         * decided to visit.
         */
        public final boolean slotWorld;

        private Body(Reply body) {
            this.dim = body.integer("dim");
            this.kind = body.text("kind");
            this.cell = body.text("cell");
            this.descendTarget = body.bool("descendTarget");
            this.slotWorld = body.bool("slotWorld");
        }

        @Override
        public String toString() {
            return kind + " dim=" + dim + " @" + cell
                    + (descendTarget ? " descendable" : "") + (slotWorld ? " (slot world)" : "");
        }
    }

    /** The cell this reading is about, as the registry spells its key. */
    public final String cellKey;
    /** The system anchor this cell resolves to, or {@code null} when it resolves to none. */
    public final String anchor;
    /** How many bodies the registry says stand AT this cell. */
    public final int bodiesAtCount;
    /** How many bodies the registry says the whole SYSTEM has. */
    public final int systemBodyCount;
    /** Whether an authored override is installed at this cell. */
    public final boolean hasOverride;
    /** Every body of the SYSTEM — not of this cell. See the class note. */
    public final List<Body> systemBodies;
    /** The bodies standing AT this cell. See the class note. */
    public final List<Body> cellBodies;
    /**
     * Where the dimension the caller asked about sits, as a cell key — or {@code null} when no dim
     * was asked about, or when the registry places that dimension nowhere.
     */
    public final String dimCell;

    private final String raw;

    private CellInfo(Reply reply, String raw) {
        this.raw = raw;
        this.cellKey = reply.text("cellKey");
        // absence is the answer: the producer writes JSON null when the cell belongs to no
        // system, and "this cell has no anchor" is a reading about the universe.
        this.anchor = reply.textOr("anchor", null);
        this.bodiesAtCount = reply.integer("bodiesAt");
        this.systemBodyCount = reply.integer("systemBodies");
        this.hasOverride = reply.bool("hasOverride");
        this.systemBodies = bodiesOf(reply, "bodies");
        this.cellBodies = bodiesOf(reply, "cellBodies");
        // absence is the answer twice: the field is emitted only when a dim was ASKED about,
        // and it is JSON null when the registry places that dim nowhere.
        this.dimCell = reply.textOr("dimCell", null);
    }

    private static List<Body> bodiesOf(Reply reply, String field) {
        List<Body> found = new ArrayList<>();
        for (String body : reply.objectArray(field)) {
            found.add(new Body(Reply.of("a `cell-info` body", body)));
        }
        return found;
    }

    /**
     * Read one {@code cell-info} reply, or refuse.
     *
     * <p>Refuses anything that is not this verb's answer — the registry being unavailable and the
     * key being malformed are both {@code error} replies, and either would otherwise read as a cell
     * with nothing in it, which is a statement about the UNIVERSE.</p>
     */
    public static CellInfo of(String cellInfoReply) {
        Reply reply = Reply.of("artest space cell-info", String.valueOf(cellInfoReply));
        if (!reply.ok()) {
            throw new AssertionError("`artest space cell-info` did not answer about a cell at all,"
                    + " so an empty body list here would be a claim about the universe rather than"
                    + " about the reply: " + cellInfoReply);
        }
        return new CellInfo(reply, String.valueOf(cellInfoReply));
    }

    /** Ask about the cell at sector {@code sx,sy,sz}. */
    public static CellInfo atSector(Probe probe, long sx, long sy, long sz) throws Exception {
        return of(probe.exec("artest space cell-info " + sx + " " + sy + " " + sz));
    }

    /** The same, also asking where {@code dim} is placed — which fills {@link #dimCell}. */
    public static CellInfo atSector(Probe probe, long sx, long sy, long sz, int dim)
            throws Exception {
        return of(probe.exec("artest space cell-info " + sx + " " + sy + " " + sz + " " + dim));
    }

    /** Ask about the cell named by {@code key}, the form that takes the registry's own spelling. */
    public static CellInfo atKey(Probe probe, String key) throws Exception {
        return of(probe.exec("artest space cell-info " + key));
    }

    /** The same, also asking where {@code dim} is placed. */
    public static CellInfo atKey(Probe probe, String key, int dim) throws Exception {
        return of(probe.exec("artest space cell-info " + key + " " + dim));
    }

    /**
     * Where the asked-about dimension sits, refusing when nothing was asked or nothing was found.
     *
     * <p>Separate from the field so a caller that means to BUILD a cell key out of it cannot carry a
     * null into a command string, where it becomes the four characters {@code null} and the probe
     * answers about a malformed key.</p>
     */
    public String requireDimCell() {
        if (dimCell == null || dimCell.isEmpty()) {
            ArrangementFailure.arrangementFailed("this `cell-info` reading names no cell for the"
                    + " dimension asked about — either no dim was asked about, or the registry"
                    + " places it nowhere, and the two are different findings: " + raw);
        }
        return dimCell;
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "cell " + cellKey + " anchor=" + anchor + " atCell=" + bodiesAtCount
                + " inSystem=" + systemBodyCount + (hasOverride ? " (authored override)" : "")
                + (dimCell == null ? "" : " dimCell=" + dimCell);
    }
}
