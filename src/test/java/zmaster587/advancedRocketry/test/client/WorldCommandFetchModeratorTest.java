package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import zmaster587.advancedRocketry.test.Events;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * True moderator-fetch coverage using two connected
 * Minecraft client bots.
 *
 * <p>{@link WorldCommandFetchTest} closed the resolvable contract surface
 * with self-fetch + unknown-name pins, but the original task framing
 * called for the canonical "moderator fetches another player to their
 * location" pin. That requires TWO connected players — bot1 (the
 * moderator) and bot2 (the target). This test runs both clients in the
 * same JVM via the multi-client variant of
 * {@link RealClientHarness#start(RealDedicatedServerHarness, String)},
 * each with a distinct username.</p>
 *
 * <p>Contract pinned:</p>
 *
 * <ul>
 *   <li><b>Two-player /ar fetch.</b> Bot1 (op) at position A, bot2 at
 *       position B. {@code /ar fetch bot2-name} (issued as bot1)
 *       resolves bot2 via {@code World.getPlayerEntityByName}, transfers
 *       bot2 to bot1's dim, and sets bot2's coords to bot1's. Post-fetch
 *       bot2's coords must be (≈) bot1's pre-fetch coords. Pins the
 *       full positive path that the single-client harness can't reach.</li>
 * </ul>
 *
 * <p><b>Resource cost</b>: ~3-4 minutes of wall time + ~7 GB RAM
 * (server JVM + 2 × client JVM + 2 × LWJGL/GL context). Run with
 * {@code maxParallelForks=1} for this test class — concurrent multi-
 * client tests will exhaust display/RAM on a typical dev box.</p>
 */
public class WorldCommandFetchModeratorTest {

    /** Distinct usernames — the server's PlayerList keys on these and
     *  rejects duplicates as "already connected", so bot1 ≠ bot2 must
     *  hold for both to be online simultaneously. */
    private static final String BOT1_NAME = "ModBot1";
    private static final String BOT2_NAME = "ModBot2";

    private static final Pattern PLAYER_POS_X = Pattern.compile("\"playerPosX\":(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)");
    private static final Pattern PLAYER_POS_Z = Pattern.compile("\"playerPosZ\":(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)");

    private RealDedicatedServerHarness server;
    private RealClientHarness bot1Harness;
    private RealClientHarness bot2Harness;

    @Before
    public void startAll() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -D" + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED
                        + "=true to enable",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue(
                "Client harness disabled — set -D" + AbstractClientE2ETest.PROP_CLIENT_ENABLED
                        + "=true to enable",
                Boolean.parseBoolean(System.getProperty(
                        AbstractClientE2ETest.PROP_CLIENT_ENABLED, "false")));

        server = RealDedicatedServerHarness.start();
        try {
            // Start clients sequentially — each takes ~60-90s for JVM +
            // GL handshake + world join. Starting them in parallel risks
            // RealClientHarness.start()'s internal control-socket-accept
            // racing on the same ServerSocket; the sequential path is
            // straightforward.
            bot1Harness = RealClientHarness.start(server, BOT1_NAME);
            bot2Harness = RealClientHarness.start(server, BOT2_NAME);
        } catch (Exception startupException) {
            try {
                if (bot2Harness != null) bot2Harness.close();
            } catch (Exception cleanup) { startupException.addSuppressed(cleanup); }
            try {
                if (bot1Harness != null) bot1Harness.close();
            } catch (Exception cleanup) { startupException.addSuppressed(cleanup); }
            try {
                server.close();
            } catch (Exception cleanup) { startupException.addSuppressed(cleanup); }
            server = null; bot1Harness = null; bot2Harness = null;
            throw startupException;
        }
    }

    @After
    public void stopAll() throws Exception {
        Exception deferred = null;
        if (bot2Harness != null) {
            try { bot2Harness.close(); } catch (Exception e) { deferred = e; }
            bot2Harness = null;
        }
        if (bot1Harness != null) {
            try { bot1Harness.close(); } catch (Exception e) {
                if (deferred == null) deferred = e; else deferred.addSuppressed(e);
            }
            bot1Harness = null;
        }
        if (server != null) {
            try { server.close(); } catch (Exception e) {
                if (deferred == null) deferred = e; else deferred.addSuppressed(e);
            }
            server = null;
        }
        if (deferred != null) throw deferred;
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", server.client().execute(cmd));
    }

    // ── the three event logs ──────────────────────────────────────────────────
    //
    // A moderator fetch is a chain that crosses sides: the server hands the TARGET's body to a
    // teleporter, and the TARGET's own client is respawned and repositioned. There is one server log
    // and one client log per JVM, so this class reads two of the three. It runs its own harnesses
    // rather than the shared base's, so the client side reaches {@link ClientEvents} directly.

    /** The SERVER's ordered event log; the step ticks the TARGET's client between reads. */
    private Events serverEvents() {
        return new Events(this::exec, bot2Harness.bot()::waitTicks);
    }

    /** The TARGET client's own ordered event log — the side the contract is stated on. */
    private Events bot2Events() {
        return ClientEvents.of(bot2Harness.bot());
    }

    /**
     * Wait for a record of {@code type} that CARRIES {@code needle}, failing with the whole chain
     * that DID happen.
     */
    private String awaitRecordCarrying(Events events, long mark, String type, String needle,
                                       String what) throws Exception {
        String reply = "";
        for (int waited = 0; waited <= LINK_BUDGET_TICKS; waited += 10) {
            reply = events.since(mark, type);
            if (Events.countRecords(reply, needle) > 0) {
                return reply;
            }
            bot2Harness.bot().waitTicks(10);
        }
        throw new AssertionError(what + " — no `" + type + "` carrying " + needle + " was recorded"
                + " within " + LINK_BUDGET_TICKS + " ticks. What DID happen since the mark: "
                + Events.typesOf(events.since(mark)) + " | raw: " + reply);
    }

    /**
     * How long one link of the fetch may take — a deadline for a discrete event, the same 200 ticks
     * the position poll it replaces was capped at.
     */
    private static final int LINK_BUDGET_TICKS = 200;

    /** Moderator (bot1, op) fetches bot2 from position B to position A. */
    @Test
    public void moderatorFetchTeleportsTargetToSenderPosition() throws Exception {
        // Stage both bots at known, well-separated positions.
        // Use vanilla /tp from the server console — works for any
        // connected player and doesn't need probe machinery.
        int sx = 100, sy = 80, sz = 100; // bot1 (moderator) destination
        int tx = 200, ty = 80, tz = 200; // bot2 (target) starting position

        // Place stone to stand on so /tp doesn't drop into the void.
        exec("artest place 0 " + sx + " " + (sy - 1) + " " + sz + " minecraft:stone");
        exec("artest place 0 " + tx + " " + (ty - 1) + " " + tz + " minecraft:stone");

        exec("tp " + BOT1_NAME + " " + (sx + 0.5) + " " + sy + " " + (sz + 0.5));
        exec("tp " + BOT2_NAME + " " + (tx + 0.5) + " " + ty + " " + (tz + 0.5));

        // Give the clients a few ticks to acknowledge their new positions
        // before we sample them.
        bot1Harness.bot().waitTicks(5);
        bot2Harness.bot().waitTicks(5);

        // Sanity-check pre-state: bots are at distinct positions.
        String bot1Pre = exec("artest player position-of " + BOT1_NAME);
        String bot2Pre = exec("artest player position-of " + BOT2_NAME);
        double bot1PreX = extractDouble(bot1Pre, PLAYER_POS_X);
        double bot1PreZ = extractDouble(bot1Pre, PLAYER_POS_Z);
        double bot2PreX = extractDouble(bot2Pre, PLAYER_POS_X);
        double bot2PreZ = extractDouble(bot2Pre, PLAYER_POS_Z);
        assertTrue("baseline: bot1 should be near (" + sx + "," + sz + "), got ("
                        + bot1PreX + "," + bot1PreZ + ")",
                Math.abs(bot1PreX - (sx + 0.5)) < 2.0
                        && Math.abs(bot1PreZ - (sz + 0.5)) < 2.0);
        assertTrue("baseline: bot2 should be near (" + tx + "," + tz + "), got ("
                        + bot2PreX + "," + bot2PreZ + ")",
                Math.abs(bot2PreX - (tx + 0.5)) < 2.0
                        && Math.abs(bot2PreZ - (tz + 0.5)) < 2.0);
        // The two bots MUST be at clearly distinct positions for the
        // moderator-fetch result to be observable.
        assertNotEquals("baseline: bots must start at distinct X coords",
                Math.round(bot1PreX), Math.round(bot2PreX));

        // Op bot1 so /ar fetch (player-equipped verb) is authorised.
        String op = exec("artest player op-named " + BOT1_NAME);
        assertTrue("op-named must succeed for bot1: " + op,
                op.contains("\"opped\":true"));

        // BOTH marks before the stimulus: the fetch is one command and its two halves (the server
        // placing the body, the target's client applying the move) are milliseconds apart, so a
        // reader that marked afterwards could not tell "already done" from "never happened".
        Events serverLog = serverEvents();
        long serverMark = serverLog.markInstrumented();
        Events targetLog = bot2Events();
        long targetMark = targetLog.mark();

        // The moderator (bot1) TYPES /ar fetch bot2 in the real client chat —
        // CPacketChatMessage, real player sender, production command path.
        bot1Harness.bot().sendChat("/ar fetch " + BOT2_NAME);

        // THE CHAIN, one link per side. On the server the command hands bot2's body to a
        // BasicTeleporter, which is the single line that decides where in the destination world he
        // lands; on bot2's own client the server's reposition is APPLIED. The old form re-read
        // bot2's rendered position on a tick budget and reported "got NaN" for a command that was
        // refused, a target the server could not resolve and a slow round trip alike.
        String placed = awaitRecordCarrying(serverLog, serverMark, "teleporter_placed",
                "\"who\":\"" + BOT2_NAME + "\"",
                "a moderator's /ar fetch must run the transfer on the TARGET: the teleporter places"
                        + " his body");
        targetLog.await(targetMark, "client_pos_look_applied",
                "the fetched player's OWN client must apply the move — that is what he sees on"
                        + " screen, and it is the contract this test is named for",
                LINK_BUDGET_TICKS);

        // The TARGET's client must end up rendering itself at the moderator's
        // pre-fetch position — that's what bot2's player sees on screen. The teleporter places him
        // on the CENTRE of bot1's block (moveToBlockPosAndAngles, i.e. floor + 0.5 on X/Z), so the
        // tolerance is sub-block rather than exact.
        com.google.gson.JsonObject state = bot2Harness.bot().reportState();
        double bot2PostX = state.get("playerX").getAsDouble();
        double bot2PostZ = state.get("playerZ").getAsDouble();
        assertTrue("post-fetch: bot2's CLIENT must render itself at bot1's pre-fetch X ("
                        + bot1PreX + "), got " + bot2PostX
                        + " — the server's own placement record: " + placed,
                Math.abs(bot2PostX - bot1PreX) < 1.5);
        assertTrue("post-fetch: bot2's CLIENT must render itself at bot1's pre-fetch Z ("
                        + bot1PreZ + "), got " + bot2PostZ,
                Math.abs(bot2PostZ - bot1PreZ) < 1.5);
        // And NOT at its prior position any more.
        assertTrue("post-fetch: bot2 must have moved away from its prior X ("
                        + bot2PreX + "), got " + bot2PostX,
                Math.abs(bot2PostX - bot2PreX) > 10.0);

        // Cross-side oracle: the server agrees about bot2's new position.
        String bot2Post = exec("artest player position-of " + BOT2_NAME);
        assertTrue("server must agree bot2 sits at bot1's pre-fetch X: " + bot2Post,
                Math.abs(extractDouble(bot2Post, PLAYER_POS_X) - bot1PreX) < 1.5);
    }

    private static double extractDouble(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern not found in: " + src, m.find());
        return Double.parseDouble(m.group(1));
    }
}
