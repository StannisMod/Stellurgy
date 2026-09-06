package zmaster587.advancedRocketry.test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Asserting that a reading is about the ship the scenario MEANS, and not about a neighbour's.
 *
 * <p>Every harness-tier class that assembles a craft runs in a world it shares with its siblings —
 * one world per class, twelve to fourteen scenarios in the densest ones, each leaving a hull behind
 * and some of them setting {@code vs permaload}. A reading that says "a ship holds this body" is
 * therefore satisfied byte-identically by a body resolved against somebody else's craft, and no
 * amount of tightening the flag itself changes that: the flag is not the part that is ambiguous.</p>
 *
 * <p><b>The disambiguator was already in the reply.</b> {@code ShipFrameTravel.explainHandles} puts
 * {@code anchorShipId} — the id of the ship actually holding the capture — into every
 * {@code artest vs deck-capture} answer. Measured 2026-09-06: 0 of the suite's 103 {@code
 * deck-capture} call sites read it, against 52 that assert on {@code alreadyTracked} /
 * {@code verdict} / {@code hullStand}. So this is not a new capability; it is spending one that was
 * already being thrown away.</p>
 *
 * <p>Kept out of any base class deliberately: the classes that need it sit under three different
 * bases ({@code AbstractSharedVsClientE2ETest}, {@code AbstractSpaceLoginRestoreClientTest}, the
 * harness's own per-method base), and a helper that only some of them can reach is how the same ten
 * lines end up copied three times.</p>
 */
public final class ShipIdentity {

    private ShipIdentity() {
    }

    /** The ship holding the capture described by a {@code deck-capture} reply, or {@code null} when
     *  the reply says nobody holds it. Never absent from a reply: production emits the key with a
     *  null value, so a missing key means the reply is not a deck-capture answer at all. */
    public static String anchorOf(String deckCaptureReply) {
        Matcher m = Pattern.compile("\"anchorShipId\":\"([^\"]*)\"")
                .matcher(String.valueOf(deckCaptureReply));
        return m.find() ? m.group(1) : null;
    }

    /**
     * Fail unless the capture in {@code deckCaptureReply} is held by {@code expectedShipId}.
     *
     * <p>Asserted BESIDE the caller's own flag check rather than instead of it: "he is held" and "he
     * is held by this ship" are different claims and a scenario usually means both. The failure
     * prints the whole reply, because the interesting case is not "no anchor" but an anchor that
     * names a craft the reader has to recognise as a neighbour.</p>
     *
     * @param what the scenario's own sentence for what the capture means, used in the failure
     */
    public static void assertCaptureAnchoredOn(String deckCaptureReply, String expectedShipId,
                                               String what) {
        assertTrue("this assertion cannot mean anything without the scenario's own ship id — it was"
                + " null, so nothing distinguishes this craft from a neighbour's: " + deckCaptureReply,
                expectedShipId != null);
        String anchor = anchorOf(deckCaptureReply);
        assertTrue(what + " — the reply names NO ship holding this body, so \"" + what + "\" cannot"
                + " be read out of it: " + deckCaptureReply, anchor != null && !anchor.isEmpty());
        assertEquals(what + " — the body is held, but by a DIFFERENT craft than this scenario's."
                + " On a world this class shares with its siblings that is the whole failure mode,"
                + " and every flag in the reply reads the same either way: " + deckCaptureReply,
                expectedShipId, anchor);
    }
}
