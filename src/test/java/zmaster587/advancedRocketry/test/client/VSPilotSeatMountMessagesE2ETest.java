package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.SeatMount;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.Events;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertTrue;

/**
 * A pilot seat answers the player instead of failing silently. Three refusal/notice contracts of
 * the seat's right-click, all observed from the REAL client (the click goes through the real
 * interaction path; the message is read as a CHAIN — the server decided to tell him, and the
 * client's HUD was handed the resolved line):
 *
 * <ol>
 *   <li><b>Unassembled notice</b>: sitting on the seat of a craft that is NOT assembled seats the
 *       player AND tells him, on the action bar, that the ship must be assembled to fly — the
 *       controls are otherwise silently dead and the player has no way to know why.</li>
 *   <li><b>Self-click no-op</b>: clicking one's own occupied seat is silent — no message, no
 *       dismount.</li>
 *   <li><b>Occupied refusal</b>: clicking a seat whose mount already carries a DIFFERENT passenger
 *       does not mount and answers with a message NAMING the occupant. The occupied refusal wins
 *       over the unassembled notice — exactly one message per click.</li>
 * </ol>
 *
 * <p>Runs on a bare world seat (no ship assembly): these contracts live at the seat itself, and
 * the world frame is the one place the harness can land a real right-click. No VS required.</p>
 *
 * <h2>Why the messages are read off the LOGS and not off the overlay</h2>
 *
 * <p>The action bar is a FADING value: the client counts it down and it is gone about four seconds
 * later, so polling {@code reportChat(1).overlay} races the very thing it watches — a late reader
 * sees an empty bar and reports a message that was shown as one that was never sent, and a negative
 * leg ("no NEW message") passes whenever the would-be message lands after the sample. Both sides of
 * every notice here are events instead, and they answer different questions: {@code
 * status_message_sent} / {@code action_bar_queued} say the SERVER decided to tell him and with which
 * translation key, {@code client_chat_received} says the client's HUD was handed the resolved line.
 * Together they separate "it was never sent" from "it was sent and never arrived", which the overlay
 * poll could not do at all.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSPilotSeatMountMessagesE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-pilot-seat-messages";
    }

    private static final int SEAT_X = 4200, SEAT_Y = FixtureSite.OPEN_AIR_Y, SEAT_Z = 4200;
    /** Leg 4's own seat, clear of leg 3's NPC occupant so neither leg has to be torn down. */
    private static final int LINKED_X = SEAT_X + 2, LINKED_Z = SEAT_Z + 2;
    private static final String OCCUPANT_NAME = "occupantName";

    /** The translation keys the seat answers with. A key is the message's IDENTITY — the lang file
     *  and any resource pack are keyed on it — where the rendered English is one translation of it. */
    private static final String KEY_NOT_ASSEMBLED = "msg.pilotseat.notassembled";
    private static final String KEY_OCCUPIED = "msg.pilotseat.occupied";

    /**
     * How long one link of a notice may take. The unassembled notice is deliberately DEFERRED ten
     * server ticks past the mount packet's tracker flush, and the client then has to be told; this is
     * a deadline for a discrete event, not a guess at how long a value settles.
     */
    private static final int NOTICE_BUDGET_TICKS = 200;

    /**
     * How long a click's absence of a message is allowed to take to become provable. The two ways a
     * seat message can be produced are both immediate at click time ({@code action_bar_queued}) or
     * ten ticks behind it ({@code status_message_sent}), so a window this wide covers both with room
     * to spare — an absence read earlier than the message would have arrived proves nothing.
     */
    private static final int SILENCE_WINDOW_TICKS = 60;

    @Test
    public void theSeatAnswersTheClickerInsteadOfFailingSilently() throws Exception {
        // ---- Arrange: a platform with a single loose (unassembled, unlinked) pilot seat. -------
        exec("artest chunk warmup 0 " + ((SEAT_X - 8) >> 4) + " " + ((SEAT_Z - 8) >> 4)
                + " " + ((SEAT_X + 8) >> 4) + " " + ((SEAT_Z + 8) >> 4));
        exec("artest fill 0 " + (SEAT_X - 3) + " " + (SEAT_Y - 1) + " " + (SEAT_Z - 3)
                + " " + (SEAT_X + 3) + " " + (SEAT_Y - 1) + " " + (SEAT_Z + 3)
                + " minecraft:obsidian");
        exec("artest fill 0 " + (SEAT_X - 3) + " " + SEAT_Y + " " + (SEAT_Z - 3)
                + " " + (SEAT_X + 3) + " " + (SEAT_Y + 4) + " " + (SEAT_Z + 3) + " minecraft:air");
        String place = exec("artest fill 0 " + SEAT_X + " " + SEAT_Y + " " + SEAT_Z
                + " " + SEAT_X + " " + SEAT_Y + " " + SEAT_Z + " advancedrocketry:pilotSeat");
        scenario().requireArranged("placing the pilot seat failed: " + place,
                place.contains("\"ok\":true"));

        standBesideTheSeat();
        emptyTheHand();

        Events events = events();

        // ---- 1) Unassembled notice: the click seats him AND explains the dead controls. --------
        // Both marks BEFORE the click, so nothing between the stimulus and the reader can be missed.
        long noticeMark = events.markInstrumented();
        long noticeClientMark = clientEvents().mark();
        bot().interactBlock(SEAT_X, SEAT_Y, SEAT_Z);
        // The chain production commits, in ITS OWN source order: Forge fires the right-click before
        // the block sees it (PlayerInteractionManager fires RightClickBlock, then onBlockActivated),
        // the seat mounts him, and only then — after the mount — does it decide he must be told, and
        // deliberately DEFERS the notice ten ticks so vanilla's "press X to dismount" hint cannot
        // overwrite it. The delivery is the last link and cannot precede the queueing.
        events.assertChain(noticeMark, "a right-click on a LOOSE pilot seat must reach the server,"
                        + " seat the clicker, and queue him the notice that his controls are dead"
                        + " until the craft is assembled", NOTICE_BUDGET_TICKS,
                "right_click_block", "mount", "action_bar_queued", "status_message_sent");
        String queued = events.since(noticeMark, "action_bar_queued");
        assertTrue("the notice the seat queues must be the \"not assembled\" one — the key IS the"
                        + " message's identity, and the lang file is keyed on it: " + queued,
                queued.contains("\"key\":\"" + KEY_NOT_ASSEMBLED + "\""));

        // The server said it; now the CLIENT must have been handed the resolved line. This is the
        // half that separates "never sent" from "sent and never arrived" — and it is read off the
        // client's own record rather than the overlay, which has faded by the time anyone asks.
        String shown = awaitClientChat(noticeClientMark, "not assembled", NOTICE_BUDGET_TICKS,
                "sitting on an UNASSEMBLED craft's pilot seat must answer the player on his action"
                        + " bar — the server queued " + KEY_NOT_ASSEMBLED + " and the client's HUD"
                        + " must be handed it");
        assertTrue("the notice the client was handed must say the ship is not assembled: " + shown,
                shown.toLowerCase(Locale.ROOT).contains("not assembled"));

        // The client's own view of the seating. There is no client-side mount record, so this stays
        // a bounded poll — but the SERVER mount is already on the chain above, so an expiry here is
        // a replication statement ("the server seated him and the client never followed"), never an
        // open question about whether the click worked.
        JsonObject riding = awaitRiding(30, true);
        assertTrue("the client must RENDER the pilot on the seat the server already mounted him to"
                + " (see the chain above): " + riding, isRiding(riding));

        // ---- 2) Self-click: silent no-op (no new message, still seated). -----------------------
        // The old form waited for the previous overlay to fade and then read overlayTicks fifteen
        // ticks after the click — which is BEFORE the ten-tick deferred notice could even have been
        // delivered, so the leg went green on timing whenever a message was on its way. An absence
        // asked of the log has a stated deadline and a positive precondition.
        long selfMark = events.markInstrumented();
        bot().interactBlock(SEAT_X, SEAT_Y, SEAT_Z);
        events.await(selfMark, "right_click_block", "the self-click must REACH the server — an"
                + " absence of messages below means nothing if the click was dropped on the reach"
                + " check before the block ever saw it", NOTICE_BUDGET_TICKS);
        bot().waitTicks(SILENCE_WINDOW_TICKS);
        String selfQueued = events.since(selfMark, "action_bar_queued");
        String selfSent = events.since(selfMark, "status_message_sent");
        Events.assertInstrumentRan(selfQueued, "action_bar_events",
                "clicking one's OWN occupied seat queues no action-bar notice");
        Events.assertInstrumentRan(selfSent, "status_message_events",
                "clicking one's OWN occupied seat sends no status message");
        assertTrue("clicking one's OWN occupied seat must be a silent no-op — nothing may be queued"
                        + " for him within " + SILENCE_WINDOW_TICKS + " ticks of the click: "
                        + selfQueued, Events.countRecords(selfQueued, "who", BOT) == 0);
        assertTrue("...and nothing may be sent to him either: " + selfSent,
                Events.countRecords(selfSent, "who", BOT) == 0);
        assertTrue("a self-click must not unseat the pilot", isRiding(bot().reportRidingEntity()));

        // ---- 3) Occupied refusal: no mount, one message naming the occupant. -------------------
        dismountAndConfirm(events);
        String occupy = exec("artest vs seat-occupy 0 " + SEAT_X + " " + SEAT_Y + " " + SEAT_Z);
        scenario().requireArranged("the seat-occupy probe must seat an NPC occupant: " + occupy,
                occupy.contains("\"ok\":true") && occupy.contains("\"mounted\":true"));
        String occupantName = Reply.of("artest vs seat-occupy", occupy).text(OCCUPANT_NAME);
        scenario().requireArranged("seat-occupy must report the occupant's name: " + occupy,
                occupantName != null);

        standBesideTheSeat();
        // The occupancy must still HOLD at the moment of the click — measured server-side, not
        // assumed from the occupy call an instant earlier.
        String occupancy = exec("artest vs seat-status 0 " + SEAT_X + " " + SEAT_Y + " " + SEAT_Z);
        scenario().requireArranged("the NPC occupant must still be seated when the bot clicks (it was "
                        + "mounted a moment ago): " + occupancy,
                occupancy.contains("\"passengers\":[{"));

        long refusalMark = events.markInstrumented();
        long refusalClientMark = clientEvents().mark();
        bot().interactBlock(SEAT_X, SEAT_Y, SEAT_Z);
        // The refusal is sent IMMEDIATELY (not deferred): the seat answers and returns without ever
        // reaching the mount. Two links, in production's own order.
        events.assertChain(refusalMark, "a right-click on an OCCUPIED pilot seat must reach the"
                        + " server and answer the clicker", NOTICE_BUDGET_TICKS,
                "right_click_block", "status_message_sent");
        String refusal = events.since(refusalMark, "status_message_sent");
        assertTrue("clicking an OCCUPIED pilot seat must answer with the occupied refusal, keyed on "
                        + KEY_OCCUPIED + ": " + refusal,
                refusal.contains("\"key\":\"" + KEY_OCCUPIED + "\""));
        // NAMING the occupant is the contract, and the translation's own arguments are where the
        // name lives — a `contains` on the rendered line cannot tell a formatted name from a name
        // that happens to appear in the sentence.
        assertTrue("...and it must NAME the occupant (\"" + occupantName + "\") in the message's own"
                        + " format arguments: " + refusal,
                refusal.contains("\"" + occupantName + "\""));
        String refusalShown = awaitClientChat(refusalClientMark, occupantName, NOTICE_BUDGET_TICKS,
                "the refusal the server sent must reach the clicker's own HUD");
        assertTrue("the line the client was handed must name the occupant: " + refusalShown,
                refusalShown.contains(occupantName));

        // The refusal WINS: exactly one message per click, so the deferred "not assembled" notice
        // must not also have been queued. An absence, with the instrument's own witness beside it —
        // and the positive precondition is leg 1, which queued one through this very instrument.
        String refusalQueued = events.since(refusalMark, "action_bar_queued");
        Events.assertInstrumentRan(refusalQueued, "action_bar_events",
                "the occupied refusal wins over the unassembled notice");
        assertTrue("the occupied refusal must WIN over the unassembled notice — exactly one message"
                        + " per click, and the notice is the one that is queued: " + refusalQueued,
                Events.countRecords(refusalQueued, "key", KEY_NOT_ASSEMBLED) == 0);

        // ...and it must not MOUNT him. The absence means something because the click is recorded
        // above: the server saw it and chose not to seat him.
        String refusalMounts = events.since(refusalMark, "mount");
        assertTrue("a click on an occupied seat must NOT mount the clicker — the click reached the"
                        + " server (see the chain) and the seat refused it: " + refusalMounts,
                Events.countRecords(refusalMounts, "who", BOT) == 0);
        JsonObject afterRefusal = bot().reportRidingEntity();
        assertTrue("...and the client must not render him seated either: " + afterRefusal,
                !isRiding(afterRefusal));

        // ---- 4) LINKED but not a ship: the notice must STILL fire. -----------------------------
        // This is the state a FAILED assembly leaves behind, and it is the one the notice exists
        // for. The seat carries a flight-computer link — recorded by the assembler before the
        // physics mod confirms the spawn, and kept when the spawn is rejected — while no ship
        // manages it. Gating "assembled" on the link ALONE suppressed the notice here and lit the
        // full tier-2 flight HUD on an inert pile of blocks; the gate is a live ship resolve.
        dismountAndConfirm(events);
        String place2 = exec("artest fill 0 " + LINKED_X + " " + SEAT_Y + " " + LINKED_Z
                + " " + LINKED_X + " " + SEAT_Y + " " + LINKED_Z + " advancedrocketry:pilotSeat");
        scenario().requireArranged("placing the second pilot seat failed: " + place2,
                place2.contains("\"ok\":true"));
        String linked = exec("artest vs seat-link 0 " + LINKED_X + " " + SEAT_Y + " " + LINKED_Z
                + " " + LINKED_X + " " + (SEAT_Y + 1) + " " + LINKED_Z);
        scenario().requireArranged("the seat must end up LINKED — without that this leg tests the same "
                        + "unlinked case as leg 1 and proves nothing: " + linked,
                linked.contains("\"linked\":true"));
        scenario().requireArranged("CONTROL: and it must NOT be managed by a ship, or the notice is "
                        + "correctly absent for a reason that has nothing to do with the bug: " + linked,
                linked.contains("\"managedByShip\":false"));

        standBeside(LINKED_X, LINKED_Z);
        long linkedMark = events.markInstrumented();
        long linkedClientMark = clientEvents().mark();
        bot().interactBlock(LINKED_X, SEAT_Y, LINKED_Z);
        events.assertChain(linkedMark, "a right-click on a LINKED seat whose craft never became a"
                        + " ship must reach the server, seat the clicker, and STILL queue him the"
                        + " \"not assembled\" notice — the link is a build-time intention, not"
                        + " evidence that a ship exists", NOTICE_BUDGET_TICKS,
                "right_click_block", "mount", "action_bar_queued", "status_message_sent");
        String linkedQueued = events.since(linkedMark, "action_bar_queued");
        assertTrue("the notice queued for the linked-but-shipless craft must be " + KEY_NOT_ASSEMBLED
                        + ": " + linkedQueued,
                linkedQueued.contains("\"key\":\"" + KEY_NOT_ASSEMBLED + "\""));
        String linkedShown = awaitClientChat(linkedClientMark, "not assembled", NOTICE_BUDGET_TICKS,
                "the notice for a linked-but-shipless craft must reach the pilot's own HUD");
        assertTrue("the line the client was handed must say the ship is not assembled: " + linkedShown,
                linkedShown.toLowerCase(Locale.ROOT).contains("not assembled"));
    }

    // ---- Arrangement helpers -------------------------------------------------------------------

    /** The account the client harness plays under — the server keys its records by it, so it is how
     *  a message or a mount is told from one belonging to somebody else in a shared world. */
    private static final String BOT = "ForgeTestClient";

    /** What the dismount probe reports he was riding BEFORE it acted; -1 when he was riding nothing. */
    private static final String WAS_RIDING_ID = "wasRidingId";

    /**
     * Get off the seat, and PROVE it. What this leg needs is a STATE — the bot is not the seat's
     * occupant — because the legs after it measure a click by somebody who must not already be
     * sitting there: a self-click is a silent no-op, so an un-dismounted bot would make the occupied
     * leg red for "no message naming the occupant" when the arrangement, not the seat, was wrong.
     *
     * <p><b>The dismount EVENT is required only when there was a mount to leave</b>, and that is not
     * a softening — it is the difference between the state and the transition into it. This runs
     * twice: once with the bot seated from the notice leg, and once right after the OCCUPIED leg,
     * whose whole contract is that the seat REFUSED to mount him. On that second call he is already
     * standing, {@code Entity.dismountRidingEntity} finds no vehicle and returns without doing
     * anything, and the recorder (which records what he was thrown off) has nothing to record. So
     * an unconditional await there demanded a transition that must not happen on that path, and
     * spent its whole budget proving the seat had done its job — measured 2026-09-06, 200 ticks of
     * nothing but {@code deck_gate_decided}. The probe answers with what he WAS riding, so the leg
     * asks for the record exactly when one is owed, and the state is asserted either way.</p>
     */
    private void dismountAndConfirm(Events events) throws Exception {
        long mark = events.markInstrumented();
        String probe = exec("artest player dismount");
        Reply dismounted = Reply.of("artest player dismount", probe);
        scenario().requireArranged("the dismount probe must say what the bot was riding when it was"
                + " asked — without that this cannot tell a dismount that was owed from one that was"
                + " not: " + probe, dismounted.has(WAS_RIDING_ID));
        boolean wasSeated = dismounted.integer(WAS_RIDING_ID) >= 0;
        if (wasSeated) {
            events.await(mark, "dismount", "the probe must actually take the bot off the seat before"
                    + " a leg that measures somebody else's click can mean anything — it reported"
                    + " him riding " + dismounted.integer(WAS_RIDING_ID) + " when asked (" + probe + ")",
                    NOTICE_BUDGET_TICKS);
        }
        // CONDITIONAL on the same fact as the server half above, and for the same reason one line
        // down: a dismount that had nothing to dismount publishes nothing on either side, so an
        // unconditional wait for the client's own record would spend its whole budget proving that
        // the bot was already off the seat. Measured by writing it the other way first — 150 ticks
        // of `deck_look` and no chain at all.
        JsonObject riding = wasSeated ? awaitRiding(30, false) : bot().reportRidingEntity();
        scenario().requireArranged("the bot must be OFF the seat, as the client renders him, before"
                + " the next leg clicks it: " + riding + " probe=" + probe, !isRiding(riding));
    }

    /** Teleport until the client OBSERVABLY stands within interaction reach of the seat. */
    private void standBesideTheSeat() throws Exception {
        standBeside(SEAT_X, SEAT_Z);
    }

    /** Same, for any seat column on this platform. */
    private void standBeside(int seatX, int seatZ) throws Exception {
        double distSq = Double.POSITIVE_INFINITY;
        JsonObject state = null;
        for (int attempt = 0; attempt < 6 && distSq >= 25.0; attempt++) {
            exec("tp @a " + (seatX + 0.5) + " " + SEAT_Y + " " + (seatZ + 1.5) + " 0 0");
            bot().waitTicks(20);
            state = bot().reportState();
            if (state.has("worldReady") && state.get("worldReady").getAsBoolean()) {
                double dx = state.get("playerX").getAsDouble() - (seatX + 0.5);
                double dy = state.get("playerY").getAsDouble() - SEAT_Y;
                double dz = state.get("playerZ").getAsDouble() - (seatZ + 0.5);
                distSq = dx * dx + dy * dy + dz * dz;
            }
        }
        scenario().requireArranged("the client must observably stand within reach of the seat, or the "
                + "right-click is dropped before the block sees it. state=" + state, distSq < 25.0);
    }

    /** Server-side clear + client-observed empty hand (a held stack can eat the right-click). */
    private void emptyTheHand() throws Exception {
        exec("clear @a");
        bot().selectHotbar(0);
        String heldId = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            JsonObject items = bot().reportPlayerItems();
            if (items.has("worldReady") && items.get("worldReady").getAsBoolean()
                    && items.has("held")) {
                heldId = items.getAsJsonObject("held").get("id").getAsString();
                if (heldId.isEmpty()) {
                    return;
                }
            }
            bot().waitTicks(5);
        }
        scenario().requireArranged("the bot's hand must be observably empty; held=" + heldId,
                heldId != null && heldId.isEmpty());
    }

    // ---- Observation helpers -------------------------------------------------------------------

    /**
     * Wait for the CLIENT's own mount chain to end seated (or not), and hand back what it renders.
     *
     * <p><b>The client's own mount HAS a record, and the sentence that stood here said otherwise.</b>
     * {@code MixinEntityPositionWriters} is in the COMMON mixin list and {@code TestTrace.record}
     * routes by the entity's own {@code world.isRemote}, so a {@code startRiding} performed on the
     * client is written to the CLIENT's log with the verdict its caller got. Polling
     * {@code reportRidingEntity} watched the SHADOW of that act instead: it cannot say when the
     * mount happened, cannot tell a refusal from a mount that has not replicated, and on expiry
     * reports the last sample as though the sample were the finding.</p>
     *
     * <p>The chain is read to its END rather than to its first link. A seating that re-seats — and
     * a crossing does — reads {@code dismount → mount → dismount → mount}, so a wait that returns on
     * the first mount lands mid-chain and the read after it can honestly say the wrong thing.</p>
     */
    private JsonObject awaitRiding(int samples, boolean want) throws Exception {
        Events client = clientEvents();
        long mark = client.mark();
        // THE STATE OF THE SIDE THE TRANSITION IS OWED BY, read first. A body already where the
        // caller wants it owes no transition and will record none, so waiting for one spends the
        // whole budget proving the obvious — and the server's opinion cannot stand in for this,
        // which is what the first version of this wait assumed. Measured twice: the server reported
        // the bot riding, took him off, and the CLIENT recorded nothing at all across 150 ticks,
        // because on that path the client had already let go.
        JsonObject now = bot().reportRidingEntity();
        if (isRiding(now) == want) {
            return now;
        }
        // The mark is taken HERE, after the stimulus the caller issued: what is being waited for is
        // the client catching up with a server mount that has already been asserted on its own
        // chain, so the window is "from now on" and an empty one means he was already where he
        // belongs — which `endsSeated` reports as seated, deliberately.
        try {
            client.awaitMatching(mark, want ? "mount" : "dismount",
                    seen -> endsSeated(client, mark) == want,
                    want ? "the client's own chain ENDING in a mount"
                            : "the client's own chain ENDING in a dismount",
                    "the client must " + (want ? "RENDER the pilot on the seat the server already"
                            + " mounted him to" : "let the pilot GO once the server has")
                            + " — an expiry here is a replication statement, never an open question"
                            + " about whether the click worked", samples * 5);
        } catch (AssertionError never) {
            Events.assertInstrumentRan(client.since(mark, "mount"), "entity_mount_writes",
                    "the client's own mounts must be observed AT ALL before an absent one can be"
                            + " read as a seating the client never performed");
            throw new AssertionError(never.getMessage()
                    + " | the client's chain: mounts=" + client.since(mark, "mount")
                    + " ||| dismounts=" + client.since(mark, "dismount"), never);
        }
        return bot().reportRidingEntity();
    }

    // `endsSeated` lived here as a private copy until 2026-09-15, when the shared remount wait was
    // found returning on an INTERMEDIATE mount for want of exactly this predicate. It moved to the
    // shared base so one definition answers "is he seated at the END of this window" for every
    // class that asks — this one included.

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }
}
