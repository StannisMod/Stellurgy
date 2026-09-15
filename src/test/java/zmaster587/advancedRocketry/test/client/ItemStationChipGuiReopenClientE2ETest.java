package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import zmaster587.advancedRocketry.test.Events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Repro for finding C010 (HIGH),
 * the MANDATORY player-visible client side.
 *
 * <p>Two GUI handlers are registered on the same {@code AdvancedRocketry
 * .instance} mod container (AdvancedRocketry.java:993 libVulpes, :995 AR);
 * Forge keys the handler map by mod container, so the AR handler (last)
 * wins and it serves only {@code guiId.OreMappingSatellite}. When a player
 * presses any button in the Space-Station-Chip GUI, {@code
 * ItemStationChip.useNetworkData:208-210} unconditionally runs
 * {@code closeScreen()} then {@code openGui(AdvancedRocketry.instance,
 * MODULARFULLSCREEN)} — but the AR handler returns null for
 * MODULARFULLSCREEN (ordinal 3 ≠ OreMappingSatellite), so no GUI packet is
 * sent and the screen stays closed. The chip GUI becomes unusable: every
 * button press dismisses it instead of refreshing it.</p>
 *
 * <p>The initial open ({@code ItemStationChip.onItemRightClick:81}) targets
 * {@code LibVulpes.instance} and works — so the bug only shows on the
 * button-press RE-open. Stimulus is the real client (sneak-right-click to
 * open, then a real GUI button click); observation is the ordered event log
 * on both sides, ending at the client's own screen.</p>
 *
 * <p><b>Corrected contract, pinned here (C010 fix, Path B)</b>: after a
 * button press the GUI re-opens as {@code GuiModularFullScreen} — the AR
 * GuiHandler now delegates non-AR (libVulpes MODULAR*) ids to the libVulpes
 * handler instead of returning null. This test previously pinned the empty
 * screen (polarity flipped when the fix landed). Recorded as a known defect.</p>
 *
 * <h2>Why this reads a chain and not a screen</h2>
 *
 * <p>The defect this class exists for was a SILENT no-op: the handler answered
 * null, Forge's {@code openGui} sent no packet at all, and nothing anywhere said
 * so. Watching the final screen for two hundred ticks cannot tell that apart
 * from a lost button packet, a lost sneak, or a client that never displayed what
 * it was sent — a regression would read {@code after=""} and name none of them.
 * Each of those is now its own link, and the one the fix moved —
 * {@code gui_container_served}, the AR handler's own answer — is asserted
 * directly.</p>
 */
public class ItemStationChipGuiReopenClientE2ETest extends AbstractClientE2ETest {

    private static final int LSHIFT = 42;
    private static final String CHIP = "advancedrocketry:spacestationchip";

    /**
     * {@code zmaster587.libVulpes.inventory.GuiHandler.guiId.MODULARFULLSCREEN.ordinal()} — the id
     * the chip re-opens on, and the one AR's handler used to answer null for. It travels in Forge's
     * own {@code OpenGui} message, so it is the id the handler is ASKED about, not an internal name.
     */
    private static final int MODULARFULLSCREEN_ID = 3;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    /** The server's ordered event log — {@code mark} before the stimulus, {@code assertChain} after. */
    private Events events() {
        return new Events(this::exec, bot()::waitTicks);
    }

    /** The CLIENT's own, behind the same verbs. This class extends the harness base rather than an
     *  AR shared one, so it reaches the adapter directly. */
    private Events clientEvents() {
        return ClientEvents.of(bot());
    }

    /**
     * The CLIENT's own records of {@code type} since {@code mark}, waited for until one carries
     * {@code needle}.
     *
     * <p>A hand-rolled copy of this loop stood here, and it had the defect the shared wait exists to
     * remove: it exited when the reply contained the needle and RETURNED, leaving each caller to
     * assert the very condition the loop had just stopped on — an assertion that can only restate a
     * success or re-check the last sample of a timeout. The shared wait fails where the claim is
     * disproved instead, and prints the whole chain that did happen.</p>
     */
    private String awaitClientRecords(long mark, String type, String needle, String what,
                                      int tickBudget) throws Exception {
        return clientEvents().awaitCarrying(mark, type, needle, what, tickBudget);
    }

    /**
     * The client is holding {@code itemId}, waited for as the set-slot PACKET that puts it there.
     *
     * <p>An ARRANGEMENT gate, and it stays one: the stimulus below is the client's own use of the
     * item in its hand, so what has to be true first is that the client's hand HOLDS the chip. What
     * changed is what a failure here MEANS. The poll this replaces could only say "the field still
     * did not read right after 200 ticks", which is a sentence about the machine; the link says the
     * equip never reached the client, and the instrument check beside it separates that from a
     * recorder that never wove.</p>
     *
     * <p>Read once first: the seam change-gates per (window, slot), so re-equipping an item the
     * hand already holds is recorded nowhere at all.</p>
     *
     * @param equipMark the CLIENT's own mark, taken BEFORE the command that equips the item
     */
    private void awaitHeld(long equipMark, String itemId) throws Exception {
        String held = heldOnClient();
        if (itemId.equals(held)) {
            return;
        }
        try {
            clientEvents().awaitCarrying(equipMark, "client_slot_set",
                    "\"item\":\"" + itemId + "\"",
                    "ARRANGEMENT: the equip must REACH the client, or the sneak-right-click below"
                            + " uses an empty hand", HELD_LINK_BUDGET_TICKS);
        } catch (AssertionError never) {
            Events.assertInstrumentRan(clientEvents().since(equipMark, "client_slot_set"),
                    "client_slot_set", "the client's own slot writes must be observed at all before"
                            + " an absent one can be read as an equip that never landed");
            throw never;
        }
        held = heldOnClient();
        if (!itemId.equals(held)) {
            throw new AssertionError("ARRANGEMENT: the client APPLIED a slot write carrying "
                    + itemId + " and still renders " + held + " in hand");
        }
    }

    /** What the client renders in the main hand, right now. */
    private String heldOnClient() throws Exception {
        return bot().reportPlayerItems().getAsJsonObject("held").get("id").getAsString();
    }

    /** How long the client is given to be TOLD about an equip, in ticks — the old poll's own
     *  ceiling, now bounding a wait for a RECORD. */
    private static final int HELD_LINK_BUDGET_TICKS = 200;

    @Test
    public void chipButtonPressReopensGuiAsFullScreen() throws Exception {
        bot().waitForWorld();

        long equipMark = clientEvents().mark();
        String equip = exec("artest player equip-stationchip");
        assertTrue("equip-stationchip must succeed: " + equip, equip.contains("\"ok\":true"));
        awaitHeld(equipMark, CHIP);

        // Sneak + right-click opens the chip's libVulpes modular GUI (this open
        // targets LibVulpes.instance, so it works even on the buggy build).
        //
        // Both marks are taken BEFORE the keypress, so nothing can happen between arming the
        // observation and the stimulus. The chain separates the three silences the old screen poll
        // ran them all together as: the use packet never reached the server (no `right_click_item`),
        // it reached it but the item did not open anything — a sneak the server never saw, which is
        // exactly the case this arrangement can produce (no `container_opened`) — and the server
        // opened a container the client never displayed (no `client_gui_opened`).
        Events events = events();
        long openMark = events.mark();
        long openOnClient = clientEvents().mark();
        bot().setKey(LSHIFT, true);
        try {
            bot().waitTicks(6);
            bot().useItem();
            events.assertChain(openMark, "a sneak-right-click with the chip in hand must REACH the"
                            + " server and make it open the chip's own container", 200,
                    "right_click_item", "container_opened");
            awaitClientRecords(openOnClient, "client_gui_opened",
                    "\"gui\":\"GuiModular\"",
                    "the chip GUI must open on sneak-right-click, on the player's OWN screen — the"
                            + " server answered the press, so what is missing is the client being"
                            + " asked to display anything for it", 200);
        } finally {
            bot().setKey(LSHIFT, false);
        }

        // Press the chip's DELETE button (BUTTON_ID_DELETE == 1). With the
        // default selection (0) DELETE is a no-op except the unconditional
        // re-open at useNetworkData:208-210 — so it isolates the re-open without
        // mutating the landing list. (clickButtonById targets the ModuleButton
        // id, as in RocketBuilderGuiE2ETest; arr.get(0) is a libVulpes chrome
        // button that does not route to the item's useNetworkData.)
        JsonObject buttons = bot().reportButtons();
        JsonArray arr = buttons.getAsJsonArray("buttons");
        assertTrue("chip GUI must render buttons: " + buttons,
                arr != null && arr.size() > 0);

        long pressMark = events.markInstrumented();
        long pressOnClient = clientEvents().mark();
        bot().clickButtonById(1);

        // Fixed: the button press re-opens the GUI as GuiModularFullScreen (the
        // AR GuiHandler now delegates MODULARFULLSCREEN to libVulpes). The chain is asserted in the
        // order production performs it — one server call stack: the packet is handed to the item,
        // the item asks Forge to open a gui, Forge asks AR's handler, and only a non-null answer
        // makes Forge open a container at all.
        events.assertChain(pressMark, "pressing a chip button must reach the ITEM, make it ask for"
                        + " the full-screen gui, and end in a container actually being opened —"
                        + " before the C010 fix the middle link answered null and everything after"
                        + " it simply did not happen, silently", 200,
                "chip_button_received", "gui_container_served", "container_opened");

        String served = events.since(pressMark, "gui_container_served");
        assertTrue("AR's gui handler must be ASKED for the libVulpes MODULARFULLSCREEN id the chip"
                        + " re-opens on (id " + MODULARFULLSCREEN_ID + "): " + served,
                served.contains("\"id\":" + MODULARFULLSCREEN_ID));
        assertEquals("...and it must not answer NULL for it. That null was the whole of C010: Forge"
                        + " sends no gui packet for a null container, so the screen stayed shut with"
                        + " nothing logged anywhere. handlerAnswers=" + served,
                0, Events.countRecords(served, "\"container\":\"null\""));

        // The player's own screen is the end of the chain, not the whole of it. Matched on the
        // event's exact `gui` field rather than by substring, so the centered GuiModular the chip
        // opened first cannot satisfy a test about GuiModularFullScreen.
        awaitClientRecords(pressOnClient, "client_gui_opened", "\"gui\":\"GuiModularFullScreen\"",
                "pressing a Space-Station-Chip button must re-open the GUI as GuiModularFullScreen"
                        + " — the GuiHandler delegates MODULARFULLSCREEN to the libVulpes handler"
                        + " instead of returning null, and a screen that never opens is that null"
                        + " coming back. What the handler answered: " + served, 200);
    }
}
