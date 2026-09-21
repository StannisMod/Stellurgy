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
import zmaster587.advancedRocketry.test.PlayerPosition;
import zmaster587.advancedRocketry.test.Reply;


import zmaster587.advancedRocketry.test.FixtureSite;

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
     * How long one link of the fetch may take — a deadline for a discrete event, the same 200 ticks
     * the position poll it replaces was capped at.
     */
    /**
     * How near its staged spot a bot must be for the BASELINE to hold, in blocks.
     *
     * <p>The TEST'S OWN: both bots are teleported to a known point, so the honest statement is that
     * they are there; two blocks is the settle of a body landing on a platform.</p>
     */
    private static final double BASELINE_NEAR_BLOCKS = 2.0;

    /**
     * How near bot1's pre-fetch position bot2 must land, in blocks, for the fetch to have PLACED
     * him there.
     *
     * <p>The TEST'S OWN, and tighter than the baseline because the fetch is a placement rather than
     * a landing: a block and a half is under a body's width. The CLIENT's rendering and the
     * SERVER's record are both held to it, which is the pair this class exists to compare.</p>
     */
    private static final double FETCHED_TO_BLOCKS = 1.5;

    /**
     * How far bot2 must have MOVED from where he was, in blocks, for the fetch to have done
     * anything at all.
     *
     * <p>The TEST'S OWN sensitivity bar: the two bots are staged far apart, so ten blocks cannot be
     * reached by settling and is easily cleared by a real fetch. Without it a fetch that did
     * nothing would pass every other assertion in the method.</p>
     */
    private static final double FETCH_MOVED_HIM_BLOCKS = 10.0;

    private static final int LINK_BUDGET_TICKS = 200;

    /** Moderator (bot1, op) fetches bot2 from position B to position A. */
    @Test
    public void moderatorFetchTeleportsTargetToSenderPosition() throws Exception {
        // Stage both bots at known, well-separated positions.
        // Use vanilla /tp from the server console — works for any
        // connected player and doesn't need probe machinery.
        int sx = 100, sy = FixtureSite.OPEN_AIR_Y, sz = 100; // bot1 (moderator) destination
        int tx = 200, ty = FixtureSite.OPEN_AIR_Y, tz = 200; // bot2 (target) starting position

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
        PlayerPosition bot1Pre = PlayerPosition.of(this::exec, BOT1_NAME);
        PlayerPosition bot2Pre = PlayerPosition.of(this::exec, BOT2_NAME);
        double bot1PreX = bot1Pre.x;
        double bot1PreZ = bot1Pre.z;
        double bot2PreX = bot2Pre.x;
        double bot2PreZ = bot2Pre.z;
        assertTrue("baseline: bot1 should be near (" + sx + "," + sz + "), got ("
                        + bot1PreX + "," + bot1PreZ + ")",
                Math.abs(bot1PreX - (sx + 0.5)) < BASELINE_NEAR_BLOCKS
                        && Math.abs(bot1PreZ - (sz + 0.5)) < BASELINE_NEAR_BLOCKS);
        assertTrue("baseline: bot2 should be near (" + tx + "," + tz + "), got ("
                        + bot2PreX + "," + bot2PreZ + ")",
                Math.abs(bot2PreX - (tx + 0.5)) < BASELINE_NEAR_BLOCKS
                        && Math.abs(bot2PreZ - (tz + 0.5)) < BASELINE_NEAR_BLOCKS);
        // The two bots MUST be at clearly distinct positions for the
        // moderator-fetch result to be observable.
        assertNotEquals("baseline: bots must start at distinct X coords",
                Math.round(bot1PreX), Math.round(bot2PreX));

        // Op bot1 so /ar fetch (player-equipped verb) is authorised.
        String op = exec("artest player op-named " + BOT1_NAME);
        assertTrue("op-named must succeed for bot1: " + op,
                Reply.of(op).bool("opped"));

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
        String placed = serverLog.awaitField(serverMark, "teleporter_placed", "who", BOT2_NAME,
                "a moderator's /ar fetch must run the transfer on the TARGET: the teleporter places"
                        + " his body", LINK_BUDGET_TICKS);
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
                Math.abs(bot2PostX - bot1PreX) < FETCHED_TO_BLOCKS);
        assertTrue("post-fetch: bot2's CLIENT must render itself at bot1's pre-fetch Z ("
                        + bot1PreZ + "), got " + bot2PostZ,
                Math.abs(bot2PostZ - bot1PreZ) < FETCHED_TO_BLOCKS);
        // And NOT at its prior position any more.
        assertTrue("post-fetch: bot2 must have moved away from its prior X ("
                        + bot2PreX + "), got " + bot2PostX,
                Math.abs(bot2PostX - bot2PreX) > FETCH_MOVED_HIM_BLOCKS);

        // Cross-side oracle: the server agrees about bot2's new position.
        PlayerPosition bot2Post = PlayerPosition.of(this::exec, BOT2_NAME);
        assertTrue("server must agree bot2 sits at bot1's pre-fetch X: " + bot2Post.raw(),
                Math.abs(bot2Post.x - bot1PreX) < FETCHED_TO_BLOCKS);
    }

}
