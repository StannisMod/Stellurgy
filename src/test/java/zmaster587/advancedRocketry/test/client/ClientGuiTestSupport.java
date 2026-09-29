package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.client.ClientBot;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import zmaster587.advancedRocketry.test.Events;

import java.io.IOException;

/**
 * Shared helpers for the client GUI E2E tests.
 *
 * <p>The right-click &rarr; server {@code onBlockActivated} &rarr; {@code openGui} &rarr;
 * client {@code displayGuiScreen} round-trip is driven over a socket bridge to
 * a real Minecraft client. A single {@code rightClickBlock} call is
 * occasionally a no-op (the interaction lands a tick before the chunk/player is
 * fully settled, or the packet is otherwise dropped), and no amount of polling
 * recovers a click that never registered — so {@link #openGuiByRightClick}
 * <em>re-issues</em> the right-click between polls.</p>
 */
final class ClientGuiTestSupport {

    private ClientGuiTestSupport() {
    }

    /** {@code report_state} reports the open screen's class under the {@code screen} key. */
    static String screenOf(JsonObject state) {
        return state != null && state.has("screen") ? state.get("screen").getAsString() : "";
    }

    /**
     * Right-clicks the block at (x,y,z) until a GUI screen opens, re-issuing the
     * click each attempt. Returns the open screen's class, or {@code ""} if no
     * GUI opened across all attempts.
     */
    static String openGuiByRightClick(ClientBot bot, Events clientEvents, int x, int y, int z)
            throws Exception {
        String already = screenOf(bot.reportState());
        if (!already.isEmpty()) {
            return already;
        }
        long mark = clientEvents.mark();
        // The click is the STIMULUS and `client_gui_opened` is the link: the recorder sits on
        // GuiOpenEvent at LOWEST with receiveCanceled, so a CANCELLED open is recorded too and a
        // test that finds one has learned exactly what it needed. `gui` is "none" when a screen
        // CLOSES, which is why the condition asks for a record that is not one of those.
        //
        // The click is re-issued every 60 ticks while the log is read every 5. A single
        // rightClickBlock is occasionally a no-op — the interaction lands a tick before the chunk
        // or the player has settled — and no amount of READING recovers a click that never
        // registered, which is what makes the re-click a stimulus rather than patience.
        //
        // It THROWS rather than answering "": an open that never happened is news, and the wait's
        // own failure names what the client WAS told. The caller's assertion is a different
        // question — whether the screen is still open and is the one it meant.
        clientEvents.awaitMatching(mark, "client_gui_opened",
                reply -> Events.records(reply).size()
                        > Events.recordsWhere(reply, "gui", "none").size(),
                "opening any screen",
                "the right-click at " + x + "," + y + "," + z + " must open a GUI", 360,
                () -> bot.rightClickBlock(x, y, z, EnumFacing.UP, EnumHand.MAIN_HAND), 60);
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
