package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A player who is BOUND becomes UNBOUND, and the mod can say which of the two he is.
 *
 * <h2>What this pins, and why it is worth a file of its own</h2>
 *
 * <p>The mod holds a player in several independent places. Until this contract existed there was no
 * way to ask what he was bound to and no single way to let him go, and the cost was a real red: an
 * aboard record left by one scenario survived into the next one, which had a CONTROL asserting its
 * absence — the record belonged to a ship on a planet and was perfectly well formed, it had simply
 * never been dropped.</p>
 *
 * <p>The contract has three clauses and each is asserted below:</p>
 * <ol>
 *   <li>a binding that exists is REPORTED as existing;</li>
 *   <li>a release lets it go AND NAMES it — a release nobody can see cannot be told from no
 *       release, which is the state this replaced;</li>
 *   <li>afterwards he is bound to nothing, asked of production rather than assumed.</li>
 * </ol>
 *
 * <h2>Why the bindings are arranged directly</h2>
 *
 * <p>The verbs stamp the SAME state production writes — the aboard record through
 * {@code ShipAboardTag}, the grace through {@code RocketTransferGrace} — not a mock of it. Arranging
 * a binding directly is honest for THIS subject: the contract here is "a bound player becomes
 * unbound", and how he came to be bound is a different mechanic with its own e2es. A test that
 * boarded a ship first would be measuring the boarding.</p>
 *
 * <h2>What this does NOT cover, said plainly</h2>
 *
 * <p>Three of the mod's bindings are not exercised here — the deck hold, the login cell claim and
 * the hyperspace drift run. Each lives in a private per-player map with no way to arrange it that is
 * not a production setter written for a test, and the release path they share with the two below is
 * the same list walked in the same loop. <b>So this file pins the MECHANISM and two of its
 * participants, not all six.</b> Anyone reading a green here should read that sentence too.</p>
 *
 * <p>Server tier: every binding is server state, and {@code ensure-fake} supplies the player. One
 * server for the class: every scenario binds what it asserts on and ends with a release, and the one
 * that needs a player with nothing bound arranges it by releasing first.</p>
 */
public class PlayerReleaseContractTest extends AbstractSharedServerTest {

    /** Any well-formed id: the record names a ship, and nothing here asks the ledger about it. */
    private static final String SHIP = "11111111-2222-3333-4444-555555555555";

    /** The two arrays this contract reads, and the two binding names production puts in them —
     *  spelled once, because a binding named in four places by a literal is four places to
     *  rename. */
    private static final String BOUND = "bound";
    private static final String RELEASED = "released";
    private static final String ABOARD_RECORD = "aboard record";
    private static final String TRANSFER_GRACE = "rocket transfer grace";

    private void ensurePlayer() throws Exception {
        String fake = exec("stellurgytest player ensure-fake 0 8.5 80 8.5");
        assertTrue("the fake player must exist before anything can be bound to him: " + fake,
                Reply.of(fake).ok());
    }

    @Test
    public void aPlayerWithNothingBoundReportsNothingAndReleasesNothing() throws Exception {
        ensurePlayer();
        // The fake player is shared with the sibling scenarios, so "has done nothing" is not a state
        // this scenario can be handed; it ARRANGES "has nothing bound" instead, by letting go of
        // whatever a sibling left. The claims below are about the witness and the release, not about
        // freshness.
        exec("stellurgytest player release");
        // THE CONTROL FOR EVERY OTHER ASSERTION IN THIS FILE. If the witness could not answer
        // "nothing", "he is bound to nothing after a release" would be satisfied by an answer that
        // never changes, and so would every green below.
        String bound = exec("stellurgytest player bindings");
        assertTrue("a player with nothing bound must be REPORTED as bound to nothing: " + bound,
                (Reply.of(bound).arrayLength("bound") == 0));

        // And releasing nothing releases NOTHING — a release that always claims something would
        // make clause 2 vacuous.
        String released = exec("stellurgytest player release");
        assertTrue("releasing an unbound player must report an empty list, not a courtesy one: "
                + released, (Reply.of(released).integer("releasedCount") == 0));
    }

    @Test
    public void anAboardRecordIsReportedThenReleasedThenGone() throws Exception {
        ensurePlayer();
        String bind = exec("stellurgytest player bind-aboard " + SHIP);
        assertTrue("the arrangement must actually stamp the record: " + bind,
                Reply.of(bind).bool("tagged"));

        // Membership of a string ARRAY, asked of the array. As a substring over the whole
        // rendering it was also satisfied by the name appearing in some other field — and
        // `"aboard record incomplete"`, which this probe family writes as a REFUSAL, carries
        // `"aboard record"` inside it.
        String bound = exec("stellurgytest player bindings");
        assertTrue("a stamped aboard record must be REPORTED as a binding — the question 'what is"
                + " this player bound to' is half the contract: " + bound,
                Reply.of(bound).holdsText(BOUND, ABOARD_RECORD));

        String released = exec("stellurgytest player release");
        assertTrue("the release must NAME the aboard record it let go; a release nobody can see is"
                + " indistinguishable from no release: " + released,
                Reply.of(released).holdsText(RELEASED, ABOARD_RECORD));

        // Asked of production twice, two different ways: the release's own view and the aboard
        // record's independent read-only witness. One of them agreeing with itself proves nothing.
        String after = exec("stellurgytest player bindings");
        assertTrue("after a release he must be bound to nothing: " + after,
                (Reply.of(after).arrayLength("bound") == 0));
        String tag = exec("stellurgytest space aboard-tag " + fakeName());
        assertTrue("and the record's own witness must agree that it is gone: " + tag,
                (!Reply.of(tag).bool("tagged")));
    }

    @Test
    public void theTransferGraceIsAlsoABindingAndIsReleasedWithTheRest() throws Exception {
        ensurePlayer();
        String bind = exec("stellurgytest player bind-grace");
        assertTrue("the arrangement must actually open the window: " + bind,
                Reply.of(bind).bool("active"));

        String bound = exec("stellurgytest player bindings");
        assertTrue("the post-transfer grace is a binding like any other — it SUPPRESSES the suit"
                + " check, so a player carrying one into his next situation is measured against a"
                + " gate that was told to stand down: " + bound,
                Reply.of(bound).holdsText(BOUND, TRANSFER_GRACE));

        String released = exec("stellurgytest player release");
        assertTrue("the release must name the grace: " + released,
                Reply.of(released).holdsText(RELEASED, TRANSFER_GRACE));

        String after = exec("stellurgytest player bindings");
        assertTrue("after a release he must be bound to nothing: " + after,
                (Reply.of(after).arrayLength("bound") == 0));
    }

    @Test
    public void twoBindingsAtOnceAreBothReportedAndBothReleased() throws Exception {
        ensurePlayer();
        exec("stellurgytest player bind-aboard " + SHIP);
        exec("stellurgytest player bind-grace");

        String bound = exec("stellurgytest player bindings");
        assertTrue("both bindings must be reported, not the first one found: " + bound,
                Reply.of(bound).holdsText(BOUND, ABOARD_RECORD)
                        && Reply.of(bound).holdsText(BOUND, TRANSFER_GRACE));

        String released = exec("stellurgytest player release");
        assertTrue("a release stops at nothing: both must be named: " + released,
                Reply.of(released).holdsText(RELEASED, ABOARD_RECORD)
                        && Reply.of(released).holdsText(RELEASED, TRANSFER_GRACE));
        assertFalse("and the report must not be a stale echo of the question — it is what each"
                        + " owner said it actually let go: " + released,
                (Reply.of(released).integer("releasedCount") == 0));

        String after = exec("stellurgytest player bindings");
        assertTrue("after a release he must be bound to nothing: " + after,
                (Reply.of(after).arrayLength("bound") == 0));
    }

    /**
     * A player coming back to a ship the server no longer knows is put down at his spawn point, and
     * the login releases EVERYTHING that bound him to a ship or a cell — not only the aboard record
     * the orphan branch also clears by itself.
     *
     * <p>That is why the transfer grace is the binding asked about: the aboard record would read
     * released whether or not the login called the release, so it cannot tell the two apart; the
     * grace is let go by the release and by nothing else on that path. The login is the real
     * handler — the event a join fires once the save file has been read, posted for this player.</p>
     *
     * <p>red-witnessed: with the orphan branch of {@code SpaceEventHandler#onPlayerLoadFromFile} at
     * {@code .playerRelease().toTheWorld(player)} releasing nothing instead of calling
     * {@code playerRelease().toTheWorld}: "a player orphaned from
     * a ship the server no longer knows must come back bound to nothing … {\"bound\":[\"rocket transfer
     * grace\"]}", 2026-09-28.</p>
     */
    @Test
    public void aReturningPlayerWhoseShipIsGoneIsReleasedFromEverything() throws Exception {
        ensurePlayer();
        // A record that says he was out in space, on a ship this server has never heard of — the
        // SHIP_UNKNOWN orphan branch, and a grace still open when he comes back.
        exec("stellurgytest player bind-aboard " + SHIP + " spaceborne");
        exec("stellurgytest player bind-grace");
        // THE CONTROL: both are held before the login, asked of the same witness as after it.
        String before = exec("stellurgytest player bindings");
        assertTrue("before the login he must be bound to his ship AND inside his transfer grace: "
                        + before,
                Reply.of(before).holdsText(BOUND, ABOARD_RECORD)
                        && Reply.of(before).holdsText(BOUND, TRANSFER_GRACE));

        String login = exec("stellurgytest player load-from-file");
        assertTrue("the login event must be posted: " + login, Reply.of(login).ok());

        String after = exec("stellurgytest player bindings");
        assertTrue("a player orphaned from a ship the server no longer knows must come back bound to"
                + " nothing — every binding to a ship or a cell released by the login: " + after,
                (Reply.of(after).arrayLength(BOUND) == 0));
    }

    /**
     * The name {@code ensure-fake} creates the player under, read off the probe rather than guessed
     * ({@code TestProbeCommand}'s {@code GameProfile}). The aboard-tag witness takes a name, so a
     * wrong one here would make that assertion fail for a reason that has nothing to do with the
     * release.
     */
    private String fakeName() {
        return "StellurgyTestFakePlayer";
    }
}
