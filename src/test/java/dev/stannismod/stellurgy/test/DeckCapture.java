package dev.stannismod.stellurgy.test;

/**
 * One reading of {@code stellurgytest vs deck-capture} — whether a deck holds one body and, when
 * none does, whether one could take it where it stands — read by field name.
 *
 * <p>Measured 2026-09-17: eight classes spell this reply's field names as string literals of their
 * own, sixty times, and fifty of those are one needle — {@code contains("\"alreadyTracked\":true")}.
 * Each copy is a place for the producer's vocabulary to be re-learned, and none can say what the
 * reply meant when a name was missing.</p>
 *
 * <h2>The reply has FOUR shapes and the two failures carry no decision at all</h2>
 *
 * <p>A body the world does not hold answers {@code {"error":"entity not found"}}; a non-living
 * entity answers {@code {"error":"not a living entity"}}. Neither carries a gate, a count or a
 * verdict — so a caller reaching for {@code alreadyTracked} on either gets {@code false}, which is
 * indistinguishable from "no deck holds this body". {@link #of} refuses both as
 * {@link ArrangementFailure}: the subject this reading is about was never there.</p>
 *
 * <p>The two SUCCESS shapes differ by branch, and that is the second thing this class exists for.
 * A HELD body reports the deck's own held point and its live ship-frame position; an UNHELD one
 * reports {@link #firstContactCandidate()} instead. Reading either off the other shape is absence,
 * not a value, so each is an entry point that refuses.</p>
 *
 * <h2>{@code alreadyTracked} is not {@code verdict}</h2>
 *
 * <p>{@link #alreadyTracked} says a deck holds this body. {@link #verdict} is true for a held body
 * and, for an unheld one, says whether a deck could take it where it stands — so "held on the deck"
 * is {@code alreadyTracked}, and a {@code verdict} alone may be about a body still in the air.</p>
 */
public final class DeckCapture {

    /** How this reader reaches the probe. The callers sit under three different bases. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    /** The entity this reading is about, as the reply echoed it back. */
    public final int entityId;
    /** Held, or takeable where it stands. See the class note. */
    public final boolean verdict;
    /** Whether a deck holds this body. */
    public final boolean alreadyTracked;
    /** Whether a ship's world box contains the body — a claim about geometry, not about capture. */
    public final boolean aboardByContainment;
    /** Whether ordinary world terrain is what the body is standing on. */
    public final boolean supportedByWorldTerrain;
    /** Whether the ship frame resolved at all, and how many of its blocks are under the feet. */
    public final boolean shipFrameResolved;
    public final int shipSupportObstacles;
    /** Whether at least one of those obstacles exists — production's own {@code > 0}. */
    public final boolean supportedByShip;
    /** The substrate's gates, as the probe found them. */
    public final boolean riding;
    public final boolean flying;
    public final boolean elytraFlying;

    private final Reply reply;
    private final String raw;

    private DeckCapture(Reply reply, String raw) {
        this.reply = reply;
        this.raw = raw;
        this.entityId = reply.integer("entityId");
        this.verdict = reply.bool("verdict");
        this.alreadyTracked = reply.bool("alreadyTracked");
        this.aboardByContainment = reply.bool("aboardByContainment");
        this.supportedByWorldTerrain = reply.bool("supportedByWorldTerrain");
        this.shipFrameResolved = reply.bool("shipFrameResolved");
        this.shipSupportObstacles = reply.integer("shipSupportObstacles");
        this.supportedByShip = reply.bool("supportedByShip");
        this.riding = reply.bool("isRiding");
        this.flying = reply.bool("isFlying");
        this.elytraFlying = reply.bool("isElytraFlying");
    }

    /**
     * Read one {@code deck-capture} reply, or refuse.
     *
     * <p>Refuses a reply that is not this verb's, and both of its error shapes — see the class
     * note: neither carries the decision every field below describes.</p>
     */
    public static DeckCapture of(String captureReply) {
        String text = String.valueOf(captureReply);
        Reply reply = Reply.of("stellurgytest vs deck-capture", text);
        if (reply.has("error")) {
            ArrangementFailure.arrangementFailed("`stellurgytest vs deck-capture` has no body to decide"
                    + " about (" + reply.text("error") + "), so every gate below would read false"
                    + " for a subject that was never there: " + text);
        }
        if (!reply.has("verdict")) {
            throw new AssertionError("this is not an `stellurgytest vs deck-capture` answer: it carries no"
                    + " `verdict`, so nothing in it is the deck's decision: " + text);
        }
        return new DeckCapture(reply, text);
    }

    /**
     * Whether the probe says this world holds NO such entity — the question a test about a subject
     * surviving (or being removed) is actually asking.
     *
     * <p>Production splits its two failures deliberately: an id the world cannot resolve answers
     * {@code "entity not found"}, while an entity that is present but not living answers
     * {@code "not a living entity"} — so the second is {@code false} here, because the subject does
     * exist. Anything that is neither an error nor a decision is refused, so a {@code false} means
     * "the server answered about this entity" and never "the reply was unreadable".</p>
     */
    public static boolean entityMissing(String captureReply) {
        String text = String.valueOf(captureReply);
        Reply reply = Reply.of("stellurgytest vs deck-capture", text);
        if (reply.has("error")) {
            return "entity not found".equals(reply.text("error"));
        }
        if (!reply.has("verdict")) {
            throw new AssertionError("this is neither an `stellurgytest vs deck-capture` decision nor one"
                    + " of its refusals, so it says nothing about whether the entity exists: "
                    + text);
        }
        return false;
    }

    /** Ask about the FIRST player — the probe's own default subject. */
    public static DeckCapture read(Probe probe) throws Exception {
        return of(probe.exec("stellurgytest vs deck-capture"));
    }

    /** Ask about one named body, which is what a scenario with more than one crew member must do. */
    public static DeckCapture byId(Probe probe, int dim, int entityId) throws Exception {
        return of(probe.exec("stellurgytest vs deck-capture " + dim + " " + entityId));
    }

    /** The ship holding the capture, refusing when nothing holds this body. */
    public String requireAnchorShipId() {
        // absence is the answer: production writes `anchorShipId` as JSON null on the
        // untracked branch, so "no anchor" arrives as a missing value and is refused below.
        String anchor = reply.textOr("anchorShipId", null);
        if (!alreadyTracked || anchor == null || anchor.isEmpty()) {
            throw new AssertionError("nothing holds entity " + entityId + ", so there is no anchor"
                    + " ship to name — an empty id here would travel into the next command as the"
                    + " four characters `null`: " + raw);
        }
        return anchor;
    }

    /** Whether the probe names an anchor at all, for a caller whose subject is its absence. */
    public boolean hasAnchor() {
        // absence is the answer: this verb's whole subject is whether an anchor is named.
        return alreadyTracked && reply.textOr("anchorShipId", null) != null;
    }

    /** Whether THIS capture is held by {@code shipId} — the question a shared world makes necessary. */
    public boolean anchoredOn(String shipId) {
        return shipId != null && hasAnchor() && shipId.equals(reply.text("anchorShipId"));
    }

    /**
     * Fail unless this capture is held by {@code expectedShipId}.
     *
     * <p>Asserted BESIDE a caller's own flag check rather than instead of it: "he is held" and "he
     * is held by this ship" are different claims and a scenario usually means both. The failure
     * prints the whole reply, because the interesting case is not "no anchor" but an anchor that
     * names a craft the reader has to recognise as a neighbour.</p>
     *
     * @param what the scenario's own sentence for what the capture means, used in the failure
     */
    public void requireAnchoredOn(String expectedShipId, String what) {
        if (expectedShipId == null) {
            throw new AssertionError("this assertion cannot mean anything without the scenario's own"
                    + " ship id — it was null, so nothing distinguishes this craft from a"
                    + " neighbour's: " + raw);
        }
        String anchor = requireAnchorShipId();
        if (!expectedShipId.equals(anchor)) {
            throw new AssertionError(what + " — the body is held, but by a DIFFERENT craft than this"
                    + " scenario's. On a world this class shares with its siblings that is the whole"
                    + " failure mode, and every flag in the reply reads the same either way: " + raw);
        }
    }

    /**
     * Every loaded hull whose world box contains this body.
     *
     * <p>Its SIZE is the part that matters: one entry means the unnamed support reading beside it is
     * unambiguous, two mean it is a coin toss that reads as a clean number either way.</p>
     */
    public String[] containingShipIds() {
        // `textArray` answers an EMPTY array for a field the reply does not carry and never
        // answers null, so the null branch that used to stand here could not be reached from any
        // caller. An absent field and "no hull contains him" are told apart by `has`, not by a
        // second empty array manufactured one line later.
        return reply.textArray("containingShipIds");
    }

    /**
     * The ship that would take this body on first contact, refusing when the body is already held.
     *
     * <p>Production only computes it on the UNTRACKED branch, so asking it of a tracked body is a
     * question about the wrong shape rather than an answer of "none".</p>
     */
    public String firstContactCandidate() {
        if (alreadyTracked) {
            throw new AssertionError("entity " + entityId + " is already held, so the probe reports"
                    + " no first-contact candidate — that field belongs to the untracked branch: "
                    + raw);
        }
        // absence is the answer: the untracked branch writes the candidate as JSON null when
        // nothing would take the body, and "nobody would" is what this verb reports.
        return reply.textOr("firstContactCandidate", null);
    }

    /**
     * The BODY's live position in its capture's ship frame — the only reading of "did it move along
     * the deck" that the deck's own motion cannot contaminate.
     */
    public double bodyShipFrameX() {
        return bodyShipFrame("bodyShipFrameX");
    }

    public double bodyShipFrameY() {
        return bodyShipFrame("bodyShipFrameY");
    }

    public double bodyShipFrameZ() {
        return bodyShipFrame("bodyShipFrameZ");
    }

    /**
     * The body's live ship-frame Y, or {@code NaN} when nothing resolves it.
     *
     * <p>For a DIAGNOSTIC taken repeatedly inside an arc, where one unresolved sample must not end
     * the scenario. {@code NaN} announces itself — it cannot be read as a height, and it propagates
     * through any arithmetic a caller does with it — which is why this shape is allowed here and a
     * zero would not be. An assertion about where the body IS uses {@link #bodyShipFrameY()}.</p>
     */
    public double bodyShipFrameYOrNaN() {
        // absence is the answer: see the javadoc — an unresolved sample inside an arc must not
        // end the scenario, and NaN cannot be misread as a height.
        return alreadyTracked ? reply.number("bodyShipFrameY") : Double.NaN;
    }

    private double bodyShipFrame(String field) {
        requireTracked(field);
        if (!reply.has(field)) {
            throw new AssertionError("entity " + entityId + " is held, but its position could not be"
                    + " mapped into the ship frame, so `" + field + "` is absent rather than zero: "
                    + raw);
        }
        return reply.number(field);
    }

    /**
     * The deck's OWN held point in the ship frame — bookkeeping that only moves when the deck commits
     * one, which is what tells a still body from one being carried by something else.
     */
    public double capturedShipFrameX() {
        return capturedShipFrame("shipFrameX");
    }

    public double capturedShipFrameY() {
        return capturedShipFrame("shipFrameY");
    }

    public double capturedShipFrameZ() {
        return capturedShipFrame("shipFrameZ");
    }

    private double capturedShipFrame(String field) {
        requireTracked(field);
        return reply.number(field);
    }

    private void requireTracked(String field) {
        if (!alreadyTracked) {
            throw new AssertionError("nothing holds entity " + entityId + ", so `" + field + "` is"
                    + " not a reading about its capture — there is none: " + raw);
        }
    }

    /** The reply exactly as the probe sent it, for a message that has to show the whole answer. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "deck-capture entity=" + entityId + " verdict=" + verdict
                + " tracked=" + alreadyTracked + " obstacles=" + shipSupportObstacles;
    }
}
