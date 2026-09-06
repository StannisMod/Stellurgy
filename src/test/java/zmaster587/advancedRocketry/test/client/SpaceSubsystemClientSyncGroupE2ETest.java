package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import zmaster587.advancedRocketry.test.Events;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What a REAL client is told about the space subsystem, and whether it believes the right thing.
 * Three scenarios, one client.
 *
 * <p>All three observe something that happens ONLY on the client — a world rebuilt for a slot dim, a
 * sky feed applied by a packet handler, a clock baseline accepted — and all three are falsifiable in
 * the same way: delete the client jar and there is nothing left to observe, so none of them can pass
 * server-side. The waits read the CLIENT's own event log; the one remaining read of a production
 * static is the sky feed's CONTENTS, which no event carries.</p>
 *
 * <h2>Why these three share one harness</h2>
 *
 * <p>Measured 2026-08-07 at 8 forks, from the result XML: 86.1 + 119.0 + 138.0 s across three client
 * boots — <b>5.7 minutes</b> for three reads.</p>
 *
 * <p>The subsystem state each scenario builds (a pool slot, a cell, a settled ledger entry, a POI)
 * is minted fresh per call and addressed by the id the probe returned, so nothing here answers with
 * a neighbour's object. The one genuinely global thing any of them touches — the space clock — is
 * put back in a {@code finally}, and the scenario that moves the player into a slot dimension
 * relies on the shared reset to bring him home, which now asserts the world the client renders
 * rather than trusting the teleport.</p>
 *
 * <p>Source classes, merged verbatim (method names preserved so CI history greps):
 * {@code SystemBodiesClientSyncE2ETest}, {@code SlotDimClientEntryE2ETest},
 * {@code TheClientKnowsTheSpaceClockE2ETest}.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SpaceSubsystemClientSyncGroupE2ETest extends AbstractSharedClientE2ETest {

    private static final Pattern FIRST_DIM = Pattern.compile("\"dims\":\\[(-?\\d+)");
    private static final Pattern PLAYER_NAME = Pattern.compile("\"player\":\"([^\"]+)\"");
    /** The slot the settle actually bound the cell to — the one place that decides it. */
    private static final Pattern BOUND_DIM = Pattern.compile("\"slotDim\":(-?\\d+)");
    private static final String CLIENT_BODIES_CLASS =
            "zmaster587.advancedRocketry.network.PacketSystemBodiesSync";
    private static final String CLOCK = "zmaster587.advancedRocketry.space.SpaceClockSync";

    /** How far the server's clock is jumped. Far past anything a sync period could account for. */
    private static final long JUMP_TICKS = 1_000_000L;
    /** Two full sync periods plus slack, so a missed phase is not a failure — a DEADLINE for the
     *  jumped baseline to arrive, no longer a window the test sat out in full. */
    private static final int SYNC_WAIT_TICKS = 520;
    /**
     * How far the two sides may stand apart. One sync period is 200 ticks and the client keeps
     * counting on its own between baselines, so the honest bound is a period plus the round trip —
     * not zero. Six orders of magnitude below the jump above.
     */
    private static final long ALLOWED_SPLIT_TICKS = 600L;

    @Override
    protected String subsystem() {
        return "space-client-sync";
    }

    private String botName() throws Exception {
        String health = exec("artest player health");
        Matcher m = PLAYER_NAME.matcher(health);
        scenario().requireArranged("player health must echo the player name: " + health, m.find());
        return m.group(1);
    }

    // ── the CLIENT's own event log ────────────────────────────────────────────
    //
    // Every one of these three scenarios waits for something that happens ON THE CLIENT — a world
    // rebuilt for a slot dim, a sky feed applied, a clock baseline accepted — so they read the base's
    // {@link #clientEvents()} rather than {@link #events()}, which is the server's log.

    /**
     * Wait for a record of {@code type} that CARRIES {@code needle}, failing with the whole chain
     * that DID happen.
     *
     * <p>{@code needle} must end at a field boundary ({@code "dim":42,}): a payload's numbers are
     * not delimited on the right, so a needle without the comma matches every value it is a prefix
     * of.</p>
     */
    private String awaitRecordCarrying(Events events, long mark, String type, String needle,
                                       String what, int tickBudget) throws Exception {
        String reply = "";
        for (int waited = 0; waited <= tickBudget; waited += 5) {
            reply = events.since(mark, type);
            if (Events.countRecords(reply, needle) > 0) {
                return reply;
            }
            bot().waitTicks(5);
        }
        throw new AssertionError(what + " — no `" + type + "` carrying " + needle + " was recorded"
                + " within " + tickBudget + " ticks. What DID happen since the mark: "
                + Events.typesOf(events.since(mark)) + " | raw: " + reply);
    }

    /**
     * Did any {@code system_bodies_received} record in this reply carry a feed for {@code slotDim}?
     *
     * <p>{@code slotDims} is a comma-separated list of the dims the packet's body half carries an
     * entry for, so membership is asked of the list and never of a substring: dim 5 must not answer
     * for dim 55.</p>
     */
    private static boolean carriesFeedFor(String sinceReply, int slotDim) {
        Matcher m = Pattern.compile("\"slotDims\":\"([^\"]*)\"").matcher(String.valueOf(sinceReply));
        while (m.find()) {
            if (("," + m.group(1) + ",").contains("," + slotDim + ",")) {
                return true;
            }
        }
        return false;
    }

    /** Wait for a sky feed naming {@code slotDim} to be APPLIED on this client. */
    private String awaitBodiesFor(Events events, long mark, int slotDim, String what,
                                  int tickBudget) throws Exception {
        String reply = "";
        for (int waited = 0; waited <= tickBudget; waited += 5) {
            reply = events.since(mark, "system_bodies_received");
            if (carriesFeedFor(reply, slotDim)) {
                return reply;
            }
            bot().waitTicks(5);
        }
        throw new AssertionError(what + " — no `system_bodies_received` naming slot dim " + slotDim
                + " was applied within " + tickBudget + " ticks. What DID happen since the mark: "
                + Events.typesOf(events.since(mark)) + " | raw: " + reply);
    }

    /**
     * Wait for a space-clock baseline of at least {@code min} to be ACCEPTED by this client — the
     * value is the seam's own argument, so this is the arrival of the jumped clock and not a sample
     * of what the client answers afterwards.
     */
    private String awaitClockBaselineAtLeast(Events events, long mark, long min, String what,
                                             int tickBudget) throws Exception {
        String reply = "";
        for (int waited = 0; waited <= tickBudget; waited += 5) {
            reply = events.since(mark, "space_clock_synced");
            Matcher m = Pattern.compile("\"serverTick\":(-?\\d+)").matcher(reply);
            while (m.find()) {
                if (Long.parseLong(m.group(1)) >= min) {
                    return reply;
                }
            }
            bot().waitTicks(5);
        }
        throw new AssertionError(what + " — no `space_clock_synced` carrying a serverTick of at least"
                + " " + min + " arrived within " + tickBudget + " ticks. Baselines since the mark: "
                + reply);
    }

    /**
     * How long one link may take here. Each of these is a packet's round trip plus the client's own
     * handling of it — a deadline for a discrete event, never a guess at how long a value settles.
     */
    private static final int LINK_BUDGET_TICKS = 400;

    // ── slot-dim entry ────────────────────────────────────────────────────────

    /**
     * From {@code SlotDimClientEntryE2ETest}. A REAL client enters a space-subsystem slot dimension
     * without dim-registration errors — the client half of the slot-dim registration sync
     * ({@code PacketSlotDimSync}).
     *
     * <p>Slot dims are registered server-side only, at pool registration; before the sync existed no
     * client had ever been inside one, and on a dedicated server a transfer into one would respawn
     * the client into a Forge dimension it never registered. This drives the full production chain
     * with the pool registered WHILE the client is online (the runtime-growth broadcast path):
     * register pool &rarr; broadcast sync &rarr; bind a cell world &rarr; transfer the real player
     * through {@code PlayerList.transferPlayerToDimension} &rarr; the client's OWN world must be the
     * slot dim and keep rendering.</p>
     */
    @Test
    public void aRealClientEntersASlotDimAndKeepsRendering() throws Exception {
        scenario().arranging("register a pool slot while the client is online, and bind a cell");
        String botName = botName();
        String reg = exec("artest space pool-register 1");
        Matcher dimM = FIRST_DIM.matcher(reg);
        scenario().requireArranged("pool-register must return the new dim id: " + reg, dimM.find());
        int slotDim = Integer.parseInt(dimM.group(1));
        scenario().record("slotDim", slotDim);
        exec("artest space load " + slotDim + " e2ecell");

        // Move the REAL player through the production transfer FIRST — an empty loaded slot is
        // auto-unloaded by Forge at tick end and its unsaved edits are DISCARDED (the documented
        // slot lifecycle), so the floor can only be placed once a player holds the world loaded.
        scenario().arranging("transfer the player in high, place a floor, then step onto it");
        Events clientLog = clientEvents();
        long entryMark = clientLog.mark();
        String enter = exec("artest space enter " + botName + " " + slotDim + " 0.5 200 0.5");
        scenario().requireArranged("space enter must succeed: " + enter, enter.contains("\"ok\":true"));
        // The client's own world is now the slot dim — the far side of the transfer, and the proof
        // that the registration sync landed (a client that never registered the dim could not build
        // a WorldClient for it). Waited for as a LINK: the respawn is a discrete event, and a client
        // torn down and rebuilt between two samples shows one change or none.
        awaitRecordCarrying(clientLog, entryMark, "client_dimension_changed", "\"dim\":" + slotDim + ",",
                "a player transferred into a pool slot must be respawned into it on his own client",
                LINK_BUDGET_TICKS);
        // …and it has the platform's chunk. That is the second link the old ten ticks were paying
        // for, and it is the one whose absence used to read as "he fell": movement is client-driven,
        // so a client without the blocks simulates a fall whatever the server thinks. Raised as an
        // ARRANGEMENT rather than as the subject — a chunk that never arrives (or whose record was
        // evicted from its 256-deep ring by the rest of the world load) is a fixture that did not
        // come up, not this scenario's contract failing.
        try {
            awaitRecordCarrying(clientLog, entryMark, "chunk_data_applied", "\"cx\":0,\"cz\":0,",
                    "the client must actually HAVE the chunk the platform is built in",
                    LINK_BUDGET_TICKS);
        } catch (AssertionError arrangement) {
            scenario().arrangementFailed(arrangement.getMessage());
        }
        exec("artest space set-block " + slotDim + " 0 64 0");

        long repositionMark = clientLog.mark();
        String reposition = exec("artest space enter " + botName + " " + slotDim + " 0.5 66 0.5");
        scenario().requireArranged("repositioning onto the platform must succeed: " + reposition,
                reposition.contains("\"ok\":true"));
        // A same-dim reposition is a server-side position write, and its far side is the client
        // APPLYING the correction — the link the forty ticks were budgeting for.
        clientLog.await(repositionMark, "client_pos_look_applied",
                "the client must apply the server's reposition onto the platform before its own"
                        + " rendered position is read", LINK_BUDGET_TICKS);

        scenario().asserting("the world the CLIENT renders is the slot dim, and it keeps running");
        JsonObject clientWorld = bot().reportWeather();
        assertTrue("client must have a world after the transfer",
                clientWorld.get("worldReady").getAsBoolean());
        assertEquals("the client's own world must be the slot dim (registration sync landed)",
                slotDim, clientWorld.get("dim").getAsInt());

        // …the client SETTLES standing on the platform (not void-falling / not frozen). STILL A
        // POLL, and it stays one: a body coming to rest is client-simulated physics converging on a
        // height, not a link production commits — there is nothing to record. Its two gating links
        // (the world, the chunk) were asserted above, so a red here now means "it had the chunk and
        // still fell" rather than "something in the arrangement never arrived".
        double clientY = Double.NaN;
        boolean settled = false;
        for (int i = 0; i < 60 && !settled; i++) {
            bot().waitTicks(5);
            clientY = bot().reportState().get("playerY").getAsDouble();
            settled = clientY > 63.5 && clientY < 68.0;
        }
        JsonObject clientBlock = bot().blockState(0, 64, 0);
        String serverView = exec("artest player health");
        assertTrue("client-rendered Y must settle at the platform (~65), got " + clientY
                + "; client block(0,64,0)=" + clientBlock + "; server player: " + serverView, settled);

        // "Keeps rendering" is a NEGATIVE over a window, so the window stays — expiry is the pass.
        // What changes is the evidence: the end-state read below says where he IS, and the event log
        // says he was never moved out and back in between the two reads, which a pair of samples
        // cannot distinguish from a client that left the world and returned.
        long holdMark = clientLog.mark();
        bot().waitTicks(40);
        String changes = clientLog.since(holdMark, "client_dimension_changed");
        Events.assertInstrumentRan(changes, "client_dimension_changed",
                "the client was never respawned out of the slot dim during the hold");
        assertEquals("a client that arrived in a slot dim must STAY there; a dimension change"
                        + " during the hold is it being thrown out: " + changes,
                0, Events.countRecords(changes, "\"via\":"));
        assertEquals("the client must still be in the slot dim two seconds later",
                slotDim, bot().reportWeather().get("dim").getAsInt());

        String post = exec("artest player health");
        assertTrue("server must still see the player: " + post, post.contains("\"player\":\""));
    }

    // ── system bodies broadcast ───────────────────────────────────────────────

    /**
     * From {@code SystemBodiesClientSyncE2ETest}. A REAL separate-JVM client receives the server's
     * per-slot system-body broadcast and stores it in {@code PacketSystemBodiesSync.CLIENT_BODIES} —
     * the client half of the {@code SystemBodiesProducer} render feed (the data {@code BoundarySky}
     * draws). The billboard APPEARANCE is {@code BoundarySkyRendersInSlotCellE2ETest}'s.
     *
     * <p><b>The client is put INSIDE the cell's slot world before it is asked what it received.</b>
     * A sky is per-dimension and a player renders exactly one world, so the server sends each player
     * only the dimension he is in. Standing the subject where the bodies are is therefore not a
     * workaround — it is the arrangement a real pilot is in, and the control leg below is what says
     * so.</p>
     */
    @Test
    public void aRealClientReceivesTheSettledShipsCellBodies() throws Exception {
        try {
            scenario().arranging("install the space stack and register a descend-target POI");
            String setup = exec("artest space entry-setup 1");
            Matcher dimM = FIRST_DIM.matcher(setup);
            scenario().requireArranged("entry-setup must return a slot dim: " + setup, dimM.find());

            // A descend-target PLANET at cell (0,5000,0), local (1000,500,-300). sy=5000 dodges the
            // fallback stars (all at sy=sz=0), so bodiesAt returns ONLY this POI.
            String poi = exec("artest space add-poi 0 5000 0 1000 500 -300 PLANET 0 7");
            scenario().requireArranged("add-poi must register a descend target: " + poi,
                    poi.contains("\"ok\":true") && poi.contains("\"descendTarget\":true"));

            // The dimension under test is the one the subsystem ACTUALLY bound the cell to, read
            // back from the settle. It is not the test's to choose: slot ids are minted per boot,
            // and a number picked here would only be a guess at the binding.
            String settle = exec("artest space ledger-settle 0 5000 0 " + dimM.group(1));
            scenario().requireArranged("ledger-settle must succeed: " + settle,
                    settle.contains("\"ok\":true"));
            Matcher boundM = BOUND_DIM.matcher(settle);
            scenario().requireArranged("the settle must report which slot the cell was bound to: "
                    + settle, boundM.find());
            int slotDim = Integer.parseInt(boundM.group(1));
            scenario().record("slotDim", slotDim);

            // CONTROL, and it runs FIRST, while the player is still OUTSIDE the cell: a sky he is
            // not in is a sky he is not sent. Without this leg the assertion below is satisfied
            // just as well by a build that broadcasts every live cell to everybody.
            //
            // Read off the client's record of every sky feed it APPLIED, not off the store the last
            // one left behind. The old form kept the last of eight samples and was a valid negative
            // only because nothing ever clears that store for a player outside a slot world — a sky
            // sent and then replaced inside the window was invisible to it. Each arrival is now its
            // own record and an absence is over all of them.
            scenario().measuring("what a player OUTSIDE the cell is sent (the control)");
            Events clientLog = clientEvents();
            long controlMark = clientLog.mark();
            bot().waitTicks(40);
            String outside = clientLog.since(controlMark, "system_bodies_received");
            scenario().record("skyFeedsWhileOutside", outside);
            assertTrue("a player who is not in the cell's world must not be sent its sky; the client"
                            + " applied a feed naming slot dim " + slotDim + ": " + outside
                            + " — (this instrument's liveness is established by the positive leg"
                            + " below, which waits for a record from the very same seam and reds by"
                            + " name if it never wove)",
                    !carriesFeedFor(outside, slotDim));

            scenario().arranging("put the player where a pilot in that cell would be");
            long insideMark = clientLog.mark();
            String enter = exec("artest space enter " + botName() + " " + slotDim + " 0.5 200 0.5");
            scenario().requireArranged("space enter must succeed: " + enter,
                    enter.contains("\"ok\":true"));

            scenario().asserting("the client is sent the cell's sky, and its contents survive intact");
            // THE LINK: the per-player broadcast reached this client and its handler applied the
            // payload. A red here names the arrival that never happened instead of the shape of a
            // private map's toString.
            String received = awaitBodiesFor(clientLog, insideMark, slotDim,
                    "a pilot standing in a cell must be sent that cell's sky", LINK_BUDGET_TICKS);
            scenario().record("skyFeedInside", received);

            // The CONTENTS are still read from the client's own store: `system_bodies_received`
            // carries the counts and which slot dims arrived, not the bodies themselves, so the
            // three pins below have no event to move onto. They are what says the descend target
            // survived the wire — the reason this scenario exists — and they stay.
            JsonObject sf = bot().readStaticField(CLIENT_BODIES_CLASS, "CLIENT_BODIES");
            String value = sf.get("isNull").getAsBoolean() ? "" : sf.get("value").getAsString();
            assertTrue("client CLIENT_BODIES must carry the slot dim's bodies, got: " + value
                            + " (the arrival itself was recorded: " + received + ")",
                    value.contains(slotDim + "=[") && value.contains("RenderBody{"));
            assertTrue("descend-target flag survived to the client: " + value,
                    value.contains("descend=true"));
            assertTrue("planet dim survived: " + value, value.contains("dim=0"));
            assertTrue("ship->body direction survived: " + value, value.contains("dir=1000,500,-300"));
        } finally {
            try {
                exec("artest space entry-clear");
            } catch (Exception ignored) {
                // Teardown only: the next scenario's own arrangement mints fresh ids regardless.
            }
        }
    }

    // ── the space clock ───────────────────────────────────────────────────────

    /**
     * From {@code TheClientKnowsTheSpaceClockE2ETest}. The space clock is readable on both logical
     * sides and answers the same value on each, to within the sync period — no caller needs to know
     * which side it is on.
     *
     * <p>Before this shipped, a client asking the space subsystem what time it was got a constant
     * {@code 0}: {@code SpaceSubsystem.spaceClock()} resolved a {@code MinecraftServer} that does
     * not exist in a client JVM.</p>
     *
     * <h2>What makes this able to fail</h2>
     * <p>A clock that merely LOOKS right at rest proves nothing: in a fresh world every counter is
     * small, so "the client's number is close to the server's" is satisfiable by two unrelated small
     * numbers. So the server's clock is JUMPED a million ticks and the client is required to follow
     * it. On a build with no sync the client's answer does not move at all, and the two claims below
     * separate by six orders of magnitude rather than by rounding.</p>
     */
    @Test
    public void theClientsSpaceClockFollowsTheServers() throws Exception {
        scenario().asserting("a joined client has been told the clock at all");
        bot().waitForWorld();

        // The load-bearing claim first: a build that never sends the baseline can only answer
        // "false" here.
        assertEquals("a joined client must have been told the space clock, or it cannot answer the"
                + " same value the server does. \"false\" means the baseline never arrived, which is"
                + " the whole mechanism missing.",
                "true", clientHasSync());

        scenario().measuring("both clocks before the jump");
        long serverBefore = serverClock();
        long clientBefore = clientClock();
        scenario().record("serverBefore", serverBefore).record("clientBefore", clientBefore);

        try {
            // The mark is taken BEFORE the clock is moved: the periodic re-sync is phase-smeared per
            // player, so a baseline can land at any tick, and a reader that marked afterwards could
            // not tell "the jumped baseline already arrived" from "it never did".
            Events clientLog = clientEvents();
            long syncMark = clientLog.mark();

            String moved = exec("artest space set-clock " + (serverBefore + JUMP_TICKS));
            scenario().requireArranged("the server clock must move: " + moved,
                    moved.contains("\"ok\":true"));

            // The link, not a budget: the client ACCEPTED a baseline carrying the jumped value.
            // The threshold is half the jump, the same discriminator the assertion below uses — a
            // client that is merely counting its own ticks never records one. A baseline with the
            // OLD value can still land between the mark and the server's write, which is why this
            // waits for a record above the threshold rather than for the first record of the type.
            String baselines = awaitClockBaselineAtLeast(clientLog, syncMark,
                    serverBefore + JUMP_TICKS / 2L,
                    "the server jumped its space clock, so the client must be sent a baseline that"
                            + " carries the jump — the whole sync mechanism is that one packet",
                    SYNC_WAIT_TICKS);
            scenario().record("clockBaselines", baselines);

            scenario().asserting("the client's clock FOLLOWED the server's, and the two agree");
            long serverAfter = serverClock();
            long clientAfter = clientClock();
            scenario().record("serverAfter", serverAfter).record("clientAfter", clientAfter);

            // THE DISCRIMINATOR. A client that is not being synced keeps counting its own ticks and
            // moves by a few hundred; one that is synced moves by the jump.
            long clientMoved = clientAfter - clientBefore;
            assertTrue("the client's space clock must FOLLOW the server's, not merely tick along"
                            + " beside it: the server jumped " + JUMP_TICKS + " ticks and the client"
                            + " moved " + clientMoved + " (before=" + clientBefore + " after="
                            + clientAfter + "; server before=" + serverBefore + " after="
                            + serverAfter + ")",
                    clientMoved > JUMP_TICKS / 2L);

            long split = Math.abs(serverAfter - clientAfter);
            assertTrue("the two sides must answer the same clock to within a sync period: server="
                            + serverAfter + " client=" + clientAfter + " split=" + split
                            + " (allowed " + ALLOWED_SPLIT_TICKS + ")",
                    split <= ALLOWED_SPLIT_TICKS);
        } finally {
            // Put the world's clock back: this harness server is not this scenario's private
            // property, and on a shared one the next scenario inherits whatever is left.
            exec("artest space set-clock " + serverBefore);
        }
    }

    private long clientClock() throws Exception {
        JsonObject answer = bot().invokeStaticInt(CLOCK, "now");
        return Long.parseLong(answer.get("returned").getAsString().trim());
    }

    private String clientHasSync() throws Exception {
        return bot().invokeStaticInt(CLOCK, "hasSync").get("returned").getAsString().trim();
    }

    private long serverClock() throws Exception {
        String frame = exec("artest space frame 0 0 0");
        Matcher m = Pattern.compile("\"clock\":(-?\\d+)").matcher(frame);
        assertTrue("the probe reports no server clock: " + frame, m.find());
        return Long.parseLong(m.group(1));
    }
}
