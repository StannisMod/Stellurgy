package zmaster587.advancedRocketry.test.client;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.Events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * player-visible side of
 * {@link zmaster587.advancedRocketry.item.ItemSealDetector#onItemUse}.
 *
 * <p>The server-tier
 * {@link zmaster587.advancedRocketry.test.server.SealDetectorDispatchTest}
 * already pins the dispatch matrix (which branch fires per fixture) by
 * driving {@code SealableBlockHandler} predicates directly through a
 * mirroring probe. What it does NOT cover is the player-visible side:
 * does {@code onItemUse} actually deliver the matching
 * {@code msg.sealdetector.&lt;branch&gt;} chat message to the player's
 * client.</p>
 *
 * <p>This e2e pins exactly that — a real {@code EntityPlayerMP} holds an
 * {@code ItemSealDetector}, right-clicks a placed fixture through the real
 * client, and the i18n-RESOLVED reply is read off the client's own chat.</p>
 *
 * <p>Fixtures mirror {@code SealDetectorDispatchTest} (stone /
 * cobblestone &rarr; "sealed", air / leaves / sand &rarr; "notsealmat",
 * stone_slab &rarr; "other"). The {@code notsealblock}, {@code notfullblock}
 * and {@code fluid} branches are out of scope here for the same reason
 * they are out of scope on the server tier — they need deterministic
 * fixtures (config-driven banned block, fluid registry) that aren't
 * available without extra plumbing.</p>
 *
 * <p>Cross-pin: every player-message branch is asserted to equal the
 * {@code seal-detector check} probe's branch field at the same
 * coordinate. Any future drift between production
 * ({@code ItemSealDetector.onItemUse}) and the mirroring probe
 * ({@code TestProbeCommand.handleSealDetector}) makes the cross-pin
 * fail loud — that's the whole point of running them side by side.</p>
 *
 * <h2>The branch is read off production's OWN key, not off the mirror</h2>
 *
 * <p>The mirror is a second copy of the same if-chain, so a defect present in both is invisible to a
 * comparison between them. What names the branch production actually took is the translation KEY it
 * handed to {@code EntityPlayer.sendMessage} — {@code msg.sealdetector.&lt;branch&gt;} — recorded as
 * {@code chat_message_sent}. That is the oracle now; the mirror cross-pin stays beside it, doing the
 * job it was written for (telling the two implementations apart when they drift).</p>
 *
 * <h2>Why this class is the shared-harness pilot</h2>
 *
 * <p>Six scenarios that used to cost six full server+client boots (~11 minutes of which ~99 % was
 * startup) now cost one. It was chosen as the first migration because it is the one that can go
 * WRONG in the interesting way: <b>three of its six methods expect the identical chat line</b>
 * ("Material will not hold a seal"). In a shared world with no chat reset, the leaves scenario finds
 * the air scenario's leftover line the instant it looks, and passes without the production path
 * running at all — a silent false green in three places.</p>
 *
 * <p>That hazard is now closed by a MARK rather than by a clean backlog: each scenario takes the
 * sequence of both event logs immediately before its right-click, and a record read after a mark
 * cannot have been written before it. The leftover line is still in the overlay and no longer
 * reachable by the assertion. The base class's per-scenario chat reset and
 * {@link #aaChatBacklogIsEmptyWhenAScenarioStarts()} stay as they are — they pin the shared reset,
 * which other classes do still read a backlog through — but no branch scenario rests on them any
 * more.</p>
 *
 * <p>Gated by {@code forge.test.client.enabled=true}; auto-skips on
 * headless CI.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ItemSealDetectorPlayerMessagesE2ETest extends AbstractSharedClientE2ETest {

    private static final int Y = Plot.DEFAULT_Y;

    /** Offsets INSIDE this scenario's own plot; the plot itself is what separates scenarios. */
    private static final int FIXTURE_DX = 32;
    private static final int FIXTURE_DZ = 32;
    private static final int PERCH_DZ = FIXTURE_DZ - 2;

    private static final Pattern BRANCH = Pattern.compile("\"branch\":\"([^\"]+)\"");

    /** How long one link of the detector's answer may take. The old chat poll allowed 200 ticks for
     *  the whole round trip; each link gets that budget, and a red now says which one is missing. */
    private static final int LINK_BUDGET_TICKS = 200;

    @Override
    protected String subsystem() {
        return "seal-detector";
    }

    private void forceLoadAround(int x, int z) throws Exception {
        int cx = x >> 4;
        int cz = z >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                exec("artest chunk forceload " + plot().dim + " " + (cx + dx) + " " + (cz + dz));
            }
        }
    }

    private String fieldOf(Pattern p, String src, String label) {
        Matcher m = p.matcher(src);
        assertTrue("expected " + label + " field in: " + src, m.find());
        return m.group(1);
    }

    /** Polls until the CLIENT renders {@code itemId} in the main hand (~10 s cap). */
    private void waitForHeld(String itemId) throws Exception {
        String held = "";
        for (int waited = 0; waited < 200; waited += 5) {
            bot().waitTicks(5);
            held = bot().reportPlayerItems().getAsJsonObject("held").get("id").getAsString();
            if (itemId.equals(held)) return;
        }
        scenario().arrangementFailed("the client never rendered " + itemId
                + " in hand within 200 ticks; held=" + held
                + " — the detector was never in the player's hand, so no branch could dispatch");
    }

    /**
     * Wait until the CLIENT's own event log carries a record matching {@code needle}, or the budget
     * ends; the reply comes back either way so the CALLER asserts and owns the failure message.
     *
     * <p>The server log has {@link Events} for this, offered by the shared base. The client log is a
     * different transport ({@code bot().eventsSince}, no probe command behind it) and the base does
     * not wrap it, so this class carries its own reader. Matching is case-insensitive: a chat line is
     * prose, and its capitalisation belongs to the translation rather than to the contract.</p>
     */
    private String awaitClientRecord(long mark, String type, String needle, int tickBudget)
            throws Exception {
        String reply = "";
        String wanted = needle.toLowerCase(Locale.ROOT);
        for (int waited = 0; waited <= tickBudget; waited += 5) {
            reply = String.valueOf(bot().eventsSince(mark, type));
            if (reply.toLowerCase(Locale.ROOT).contains(wanted)) {
                return reply;
            }
            bot().waitTicks(5);
        }
        return reply;
    }

    /** Stages the fixture in this scenario's plot, stands the player on a stone perch one
     *  block away holding the seal detector, RIGHT-CLICKS the fixture through
     *  the real client ({@code interactBlock} &rarr; CPacketPlayerTryUseItemOnBlock),
     *  and asserts the i18n-RESOLVED reply lands on the player's chat.
     *  Cross-pins the branch against the server-tier seal-detector mirror.
     *
     *  <p>Three links, one per thing that can be broken: the click REACHED the server
     *  ({@code right_click_block} — its absence is the documented reach-check diagnosis and used to
     *  be indistinguishable from a wrong branch), the detector chose and SENT a branch
     *  ({@code chat_message_sent} carrying {@code msg.sealdetector.&lt;branch&gt;}), and the client's HUD
     *  was handed the resolved line ({@code client_chat_received}). The resolved TEXT is asserted on
     *  the client's record only: on the server a {@code TextComponentTranslation} still carries its
     *  key, so matching text there would pin the key twice and the player's own reading never.</p> */
    private void assertSealDetectorBranch(String fixtureBlock, String expected,
                                          String expectedChatText) throws Exception {
        int dim = plot().dim;
        int x = plot().x(FIXTURE_DX);
        int z = plot().z(FIXTURE_DZ);
        int perchZ = plot().z(PERCH_DZ);

        scenario()
                // What the SERVER thinks is at the fixture coordinate, and which branch its own
                // mirror would dispatch there. Between them they separate "the fixture was never
                // placed" from "it was placed and production chose a different branch" — the two
                // readings a bare "the chat line never arrived" cannot tell apart.
                .describeOnFailureWith(
                        "artest block at " + dim + " " + x + " " + Y + " " + z,
                        "artest seal-detector check " + dim + " " + x + " " + Y + " " + z)
                .arranging("place the " + fixtureBlock + " fixture at " + x + "," + Y + "," + z);

        forceLoadAround(x, z);
        String placed = exec("artest place " + dim + " " + x + " " + Y + " " + z + " " + fixtureBlock);
        // Air placement is a no-op for /artest place but force-loads the chunk — accept either
        // "placed":true or a "placed":false echoing that the block was already there.
        scenario().requireArranged("place must not error at " + x + "," + Y + "," + z
                + " with " + fixtureBlock + "; resp=" + placed, !placed.contains("\"error\""));

        scenario().arranging("perch the player two blocks south of the fixture");
        String perch = exec("artest place " + dim + " " + x + " " + Y + " " + perchZ + " minecraft:stone");
        scenario().requireArranged("perch place must not error: " + perch, !perch.contains("\"error\""));

        scenario().arranging("give the seal detector and wait for the CLIENT to render it in hand");
        String give = exec("artest player give-held advancedrocketry:sealdetector");
        scenario().requireArranged("give-held sealdetector must succeed: " + give,
                give.contains("\"ok\":true"));
        exec("tp @a " + (x + 0.5) + " " + (Y + 1) + " " + (z - 1.5));
        waitForHeld("advancedrocketry:sealdetector");

        // Mark both logs at the LAST moment before the stimulus. The arrangement above issues ~13
        // server commands and every one of them echoes a "[Server] FORGE_TEST_DONE <uuid>" line into
        // the player's chat — measured, 13 lines in the backlog on this class's first shared run.
        // The mark is what makes those harmless: a record read after it cannot be a line written
        // before it, so the channel no longer has to be emptied and no server command between here
        // and the click can spoil the reading.
        scenario().measuring("mark both event logs immediately before the right-click");
        Events events = events();
        long mark = events.markInstrumented();
        long clientMark = clientEvents().mark();

        scenario().asserting("the player reads the " + expected + " reply on their own chat");
        bot().interactBlock(x, Y, z);

        events.assertChain(mark, "a right-click on the " + fixtureBlock + " fixture with the seal"
                + " detector must reach the server and be answered", LINK_BUDGET_TICKS,
                "right_click_block", "chat_message_sent");

        // WHICH branch production took, from the key production itself composed — not from the
        // mirror below, which is a second copy of the same if-chain and agrees with it by
        // construction.
        String key = "msg.sealdetector." + expected;
        String sent = events.since(mark, "chat_message_sent");
        assertTrue("the seal detector must answer the " + fixtureBlock + " fixture at " + x + ","
                + Y + "," + z + " with " + key + "; what it actually sent since the click: " + sent,
                sent.contains("\"key\":\"" + key + "\""));

        String seen = awaitClientRecord(clientMark, "client_chat_received", expectedChatText,
                LINK_BUDGET_TICKS);
        Events.assertInstrumentRan(seen, "client_chat_received",
                "the player was shown the seal detector's reply");
        assertTrue("client chat must show '" + expectedChatText + "' for " + fixtureBlock
                + " at " + x + "," + Y + "," + z + "; the server sent " + sent
                + " and the client's chat records since the click are: " + seen,
                seen.toLowerCase(Locale.ROOT).contains(expectedChatText.toLowerCase(Locale.ROOT)));

        scenario().asserting("production dispatch and the server-tier mirror agree on the branch");
        String checkResp = exec("artest seal-detector check " + dim + " " + x + " " + Y + " " + z);
        assertEquals("production dispatch and server-tier mirror must agree on branch for "
                        + fixtureBlock, expected, fieldOf(BRANCH, checkResp, "branch"));
    }

    // ── the reset's own witness, from this side ───────────────────────────────

    /**
     * Named to sort FIRST so it runs before any scenario has written to chat, and again meaningful
     * on every later run of the class: it pins that a scenario is handed an empty backlog by the
     * shared-harness reset.
     *
     * <p><b>Read it for what it is.</b> Two things it is NOT. It is no longer the guard the branch
     * scenarios below stand on — those read their verdict off a MARK on the client's event log, and
     * a leftover line is unreachable to them whatever the backlog holds. And it is not an
     * independent witness of the reset: the base class's own {@code @Before} asserts the identical
     * condition a couple of statements earlier, so a regression in the reset fails there first and
     * this method never reaches its assertion. It stays because the condition is a real contract of
     * the shared harness that other classes DO read a backlog through, and a test naming it here is
     * where a reader looks.</p>
     */
    @Test
    public void aaChatBacklogIsEmptyWhenAScenarioStarts() throws Exception {
        scenario().asserting("a scenario starts with an empty chat backlog");
        com.google.gson.JsonObject chat = bot().reportChat(20);
        scenario().record("lines", chat.get("lines")).record("overlayTicks", chat.get("overlayTicks"));
        assertEquals("a shared-harness scenario must start with no chat lines: " + chat.get("lines"),
                0, chat.get("count").getAsInt());
    }

    // ───────────────────── sealed branch ──────────────────────────────────

    /** Solid ROCK material full-block &rarr; "sealed". */
    @Test
    public void stoneFixtureDispatchesSealedMessageToPlayer() throws Exception {
        assertSealDetectorBranch("minecraft:stone", "sealed", "Should hold a nice seal");
    }

    /** Pins that "sealed" isn't pinned to the singular stone block —
     *  any solid full-block ROCK material should reach the player as
     *  "sealed", per SealableBlockHandler.isBlockSealed's material gate. */
    @Test
    public void cobblestoneFixtureDispatchesSealedMessageToPlayer() throws Exception {
        assertSealDetectorBranch("minecraft:cobblestone", "sealed", "Should hold a nice seal");
    }

    // ───────────────────── notsealmat branch ──────────────────────────────

    /** Material.AIR is on the default materialBanList &rarr; "notsealmat". */
    @Test
    public void airFixtureDispatchesNotSealMatMessageToPlayer() throws Exception {
        assertSealDetectorBranch("minecraft:air", "notsealmat", "Material will not hold a seal");
    }

    /** Material.LEAVES is on the default materialBanList — multi-material
     *  ban-list pin (not just AIR). */
    @Test
    public void leavesFixtureDispatchesNotSealMatMessageToPlayer() throws Exception {
        assertSealDetectorBranch("minecraft:leaves", "notsealmat", "Material will not hold a seal");
    }

    /** Material.SAND is on the default materialBanList — silent removal
     *  from the ban-list would let sand seal rooms (player-visible
     *  regression). */
    @Test
    public void sandFixtureDispatchesNotSealMatMessageToPlayer() throws Exception {
        assertSealDetectorBranch("minecraft:sand", "notsealmat", "Material will not hold a seal");
    }

    // ───────────────────── other branch ───────────────────────────────────

    /** Stone slab: ROCK material (not banned), but half-block bounds &rarr;
     *  isFullBlock=false &rarr; dispatch falls through to "other" (after
     *  short-circuiting on the non-IFluidBlock check). */
    @Test
    public void stoneSlabFixtureDispatchesOtherMessageToPlayer() throws Exception {
        assertSealDetectorBranch("minecraft:stone_slab", "other", "Air will leak through this block");
    }
}
