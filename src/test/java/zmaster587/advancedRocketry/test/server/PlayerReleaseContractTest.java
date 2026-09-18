package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

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
 * <p>Server tier: every binding is server state, and {@code ensure-fake} supplies the player.</p>
 */
public class PlayerReleaseContractTest {

    /** Any well-formed id: the record names a ship, and nothing here asks the ledger about it. */
    private static final String SHIP = "11111111-2222-3333-4444-555555555555";

    private Path workDir;
    private RealDedicatedServerHarness harness;

    @Before
    public void startServer() throws Exception {
        Assume.assumeTrue("Server harness disabled",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        workDir = Files.createTempDirectory("forge-server-player-release-");
        harness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);
    }

    @After
    public void stopServer() throws Exception {
        if (harness != null) {
            harness.close();
        }
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", harness.client().execute(cmd));
    }

    private void ensurePlayer() throws Exception {
        String fake = exec("artest player ensure-fake 0 8.5 80 8.5");
        assertTrue("the fake player must exist before anything can be bound to him: " + fake,
                Reply.of(fake).ok());
    }

    @Test
    public void aFreshPlayerIsBoundToNothing() throws Exception {
        ensurePlayer();
        // THE CONTROL FOR EVERY OTHER ASSERTION IN THIS FILE. If a fresh player already reported
        // bindings, "he is bound to nothing after a release" would be satisfied by an answer that
        // never changes, and so would every green below.
        String bound = exec("artest player bindings");
        assertTrue("a player who has done nothing must be bound to nothing: " + bound,
                (Reply.of(bound).arrayLength("bound") == 0));

        // And releasing nothing releases NOTHING — a release that always claims something would
        // make clause 2 vacuous.
        String released = exec("artest player release");
        assertTrue("releasing an unbound player must report an empty list, not a courtesy one: "
                + released, (Reply.of(released).integerOr("releasedCount", Integer.MIN_VALUE) == 0));
    }

    @Test
    public void anAboardRecordIsReportedThenReleasedThenGone() throws Exception {
        ensurePlayer();
        String bind = exec("artest player bind-aboard " + SHIP);
        assertTrue("the arrangement must actually stamp the record: " + bind,
                Reply.of(bind).bool("tagged", false));

        String bound = exec("artest player bindings");
        assertTrue("a stamped aboard record must be REPORTED as a binding — the question 'what is"
                + " this player bound to' is half the contract: " + bound,
                bound.contains("\"aboard record\""));

        String released = exec("artest player release");
        assertTrue("the release must NAME the aboard record it let go; a release nobody can see is"
                + " indistinguishable from no release: " + released,
                released.contains("\"aboard record\""));

        // Asked of production twice, two different ways: the release's own view and the aboard
        // record's independent read-only witness. One of them agreeing with itself proves nothing.
        String after = exec("artest player bindings");
        assertTrue("after a release he must be bound to nothing: " + after,
                (Reply.of(after).arrayLength("bound") == 0));
        String tag = exec("artest space aboard-tag " + fakeName());
        assertTrue("and the record's own witness must agree that it is gone: " + tag,
                (!Reply.of(tag).bool("tagged", true)));
    }

    @Test
    public void theTransferGraceIsAlsoABindingAndIsReleasedWithTheRest() throws Exception {
        ensurePlayer();
        String bind = exec("artest player bind-grace");
        assertTrue("the arrangement must actually open the window: " + bind,
                Reply.of(bind).bool("active", false));

        String bound = exec("artest player bindings");
        assertTrue("the post-transfer grace is a binding like any other — it SUPPRESSES the suit"
                + " check, so a player carrying one into his next situation is measured against a"
                + " gate that was told to stand down: " + bound,
                bound.contains("\"rocket transfer grace\""));

        String released = exec("artest player release");
        assertTrue("the release must name the grace: " + released,
                released.contains("\"rocket transfer grace\""));

        String after = exec("artest player bindings");
        assertTrue("after a release he must be bound to nothing: " + after,
                (Reply.of(after).arrayLength("bound") == 0));
    }

    @Test
    public void twoBindingsAtOnceAreBothReportedAndBothReleased() throws Exception {
        ensurePlayer();
        exec("artest player bind-aboard " + SHIP);
        exec("artest player bind-grace");

        String bound = exec("artest player bindings");
        assertTrue("both bindings must be reported, not the first one found: " + bound,
                bound.contains("\"aboard record\"") && bound.contains("\"rocket transfer grace\""));

        String released = exec("artest player release");
        assertTrue("a release stops at nothing: both must be named: " + released,
                released.contains("\"aboard record\"")
                        && released.contains("\"rocket transfer grace\""));
        assertFalse("and the report must not be a stale echo of the question — it is what each"
                        + " owner said it actually let go: " + released,
                (Reply.of(released).integerOr("releasedCount", Integer.MIN_VALUE) == 0));

        String after = exec("artest player bindings");
        assertTrue("after a release he must be bound to nothing: " + after,
                (Reply.of(after).arrayLength("bound") == 0));
    }

    /**
     * The name {@code ensure-fake} creates the player under, read off the probe rather than guessed
     * ({@code TestProbeCommand}'s {@code GameProfile}). The aboard-tag witness takes a name, so a
     * wrong one here would make that assertion fail for a reason that has nothing to do with the
     * release.
     */
    private String fakeName() {
        return "ARTestFakePlayer";
    }
}
