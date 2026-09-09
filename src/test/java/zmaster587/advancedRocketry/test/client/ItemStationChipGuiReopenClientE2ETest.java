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
     * {@code needle} or the budget runs out. Returns the last reply either way, so a failure prints
     * what the client DID record rather than one stale sample.
     *
     * <p>Local to this class: {@link Events} runs over the server probe, and the client log is
     * reached through the bot. (A sibling copy lives in {@code MachineGuiClientGroupE2ETest}; the
     * two classes have no common base to put it on.)</p>
     */
    private String awaitClientRecords(long mark, String type, String needle, int tickBudget)
            throws Exception {
        String reply = clientEvents().since(mark, type);
        for (int waited = 0; waited < tickBudget && !reply.contains(needle); waited += 5) {
            bot().waitTicks(5);
            reply = clientEvents().since(mark, type);
        }
        return reply;
    }

    /**
     * Poll the CLIENT-rendered held item until it is {@code itemId} (~10 s).
     *
     * <p>An ARRANGEMENT gate, and it stays one: the stimulus below is the client's own use of the
     * item in its hand, so what has to be true first is that the client's hand HOLDS the chip — a
     * rendered-state fact, not a link of the contract. A failure here is the fixture not being
     * built, and it says so.</p>
     */
    private void waitForHeld(String itemId) throws Exception {
        String held = "";
        for (int waited = 0; waited < 200; waited += 5) {
            bot().waitTicks(5);
            held = bot().reportPlayerItems().getAsJsonObject("held").get("id").getAsString();
            if (itemId.equals(held)) return;
        }
        throw new AssertionError("ARRANGEMENT: the client never rendered " + itemId + " in hand, so"
                + " the sneak-right-click below would have used an empty hand; held=" + held);
    }

    @Test
    public void chipButtonPressReopensGuiAsFullScreen() throws Exception {
        bot().waitForWorld();

        String equip = exec("artest player equip-stationchip");
        assertTrue("equip-stationchip must succeed: " + equip, equip.contains("\"ok\":true"));
        waitForHeld(CHIP);

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
        String opened;
        try {
            bot().waitTicks(6);
            bot().useItem();
            events.assertChain(openMark, "a sneak-right-click with the chip in hand must REACH the"
                            + " server and make it open the chip's own container", 200,
                    "right_click_item", "container_opened");
            opened = awaitClientRecords(openOnClient, "client_gui_opened",
                    "\"gui\":\"GuiModular\"", 200);
        } finally {
            bot().setKey(LSHIFT, false);
        }
        assertTrue("the chip GUI must open on sneak-right-click, on the player's own screen;"
                        + " screensDisplayed=" + opened + " screen="
                        + bot().reportState().get("screen").getAsString(),
                opened.contains("\"gui\":\"GuiModular\""));

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
        String after = awaitClientRecords(pressOnClient, "client_gui_opened",
                "\"gui\":\"GuiModularFullScreen\"", 200);
        assertTrue("after the C010 fix, pressing a Space-Station-Chip button must "
                        + "re-open the GUI as GuiModularFullScreen — the AR GuiHandler now "
                        + "delegates MODULARFULLSCREEN (opened on AdvancedRocketry.instance) "
                        + "to the libVulpes handler instead of returning null. screensDisplayed="
                        + after + " screen=" + bot().reportState().get("screen").getAsString()
                        + " handlerAnswers=" + served,
                after.contains("\"gui\":\"GuiModularFullScreen\""));
    }
}
