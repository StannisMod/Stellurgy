package dev.stannismod.stellurgy.test.client;

import com.github.stannismod.forge.testing.client.ClientBot;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.stannismod.stellurgy.test.Events;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;

/**
 * Shared helpers for the client GUI E2E tests.
 *
 * <p>The right-click &rarr; server {@code onBlockActivated} &rarr; {@code openGui} &rarr;
 * client {@code displayGuiScreen} round-trip is driven over a socket bridge to
 * a real Minecraft client. {@link #openGuiByRightClick} clicks once and links on the
 * client's own record of the screen it was told to display.</p>
 */
final class ClientGuiTestSupport {

    private ClientGuiTestSupport() {
    }

    /** {@code report_state} reports the open screen's class under the {@code screen} key. */
    static String screenOf(JsonObject state) {
        return state != null && state.has("screen") ? state.get("screen").getAsString() : "";
    }

    /**
     * Right-clicks the block at (x,y,z) once and waits for the client to display a screen;
     * fails when it never does. Returns the open screen's class as the client reports it after.
     */
    static String openGuiByRightClick(ClientBot bot, Events clientEvents, int x, int y, int z)
            throws Exception {
        String already = screenOf(bot.reportState());
        if (!already.isEmpty()) {
            return already;
        }
        // ONE click, from a mark taken before it, and `client_gui_opened` is the link: the recorder
        // sits on GuiOpenEvent at LOWEST with receiveCanceled, so a CANCELLED open is recorded too and
        // a test that finds one has learned exactly what it needed. `gui` is "none" when a screen
        // CLOSES, which is why the condition asks for a record that is not one of those.
        //
        // The click is not repeated. The verb runs the client's own processRightClickBlock on its
        // thread, which sends the use packet for any block inside the world border (vanilla's
        // PlayerControllerMP); what can drop it is the server's reach check against where the
        // player stands, and that is the caller's arrangement to have linked before it calls (the
        // placement's own record). A re-click until a screen appeared turned a dropped click into a
        // retry nobody saw.
        //
        // It THROWS rather than answering "": an open that never happened is news, and the wait's
        // own failure names what the client WAS told. The caller's assertion is a different
        // question — whether the screen is still open and is the one it meant.
        long mark = clientEvents.mark();
        bot.rightClickBlock(x, y, z, EnumFacing.UP, EnumHand.MAIN_HAND);
        clientEvents.awaitMatching(mark, "client_gui_opened",
                reply -> Events.records(reply).size()
                        > Events.recordsWhere(reply, "gui", "none").size(),
                "opening any screen",
                "the right-click at " + x + "," + y + "," + z + " must open a GUI", 360);
        return screenOf(bot.reportState());
    }

    /**
     * First entry in {@code report_buttons} whose {@code id} satisfies
     * {@code [minId, maxId)} and is clickable (enabled + visible), or
     * {@code Integer.MIN_VALUE} if none. Used to pick a stable mod-assigned
     * button id rather than relying on list position.
     */
    static int findButtonId(JsonObject reportButtons, int minId, int maxId) {
        JsonArray buttons = reportButtons.getAsJsonArray("buttons");
        for (JsonElement element : buttons) {
            JsonObject button = element.getAsJsonObject();
            int id = button.get("id").getAsInt();
            if (id >= minId && id < maxId
                    && button.get("enabled").getAsBoolean()
                    && button.get("visible").getAsBoolean()) {
                return id;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Container slot number of the first slot holding {@code itemId}, or -1. */
    static int findSlotWithItem(JsonObject reportSlots, String itemId, boolean playerSlot) {
        JsonArray slots = reportSlots.getAsJsonArray("slots");
        for (JsonElement element : slots) {
            JsonObject slot = element.getAsJsonObject();
            if (slot.get("hasStack").getAsBoolean()
                    && slot.get("playerSlot").getAsBoolean() == playerSlot
                    && itemId.equals(slot.get("item").getAsString())) {
                return slot.get("slot").getAsInt();
            }
        }
        return -1;
    }
}
