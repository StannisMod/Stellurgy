package com.github.stannismod.forge.testing.mixin;

import net.minecraft.client.gui.GuiIngame;
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.ITextComponent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.github.stannismod.forge.testing.client.bridge.ForgeTestClientBootstrap;

/**
 * Records {@code client_chat_received}: a chat line, system message or action-bar text has been
 * handed to the client's in-game HUD for display.
 *
 * <h2>The seam</h2>
 *
 * <p>{@code GuiIngame.addChatMessage(ChatType, ITextComponent)} is the ONE method every displayed
 * message passes through: {@code NetHandlerPlayClient.handleChat} calls it for {@code CHAT} and
 * {@code SYSTEM} messages (which land in {@code GuiNewChat}) AND for {@code GAME_INFO} (the action
 * bar above the hotbar, which never touches the chat GUI at all). A recorder on {@code GuiNewChat}
 * would therefore be blind to the action bar; this one is not. Forge's {@code GuiIngameForge}
 * extends {@code GuiIngame} and does not override this method, so the injection is reached by the
 * subclass the running client actually instantiates.</p>
 *
 * <p>Injected at HEAD: the fact recorded is "the HUD was TOLD this message", taken from the
 * arguments. The listeners run afterwards and the record does not depend on any of them. The
 * method is declared exactly once in {@code GuiIngame} (verified against
 * {@code build/rfg/minecraft-src/…/GuiIngame.java:1300} on 2026-09-05), so the bare name selector
 * cannot match twice.</p>
 *
 * <h2>Ring pressure</h2>
 *
 * <p>Ungated, and it does not need a gate: the seam fires once per message displayed. That was
 * checked rather than assumed for the action bar, which is the one path that could plausibly repeat
 * on a clock — every {@code GAME_INFO} sender in this mod is one-shot ({@code DelayedActionBar}
 * removes an entry as it sends it; the block and detector messages are interaction-driven), so
 * nothing here refreshes the bar on a tick. A mod that DID would turn the 256-record ring over, and
 * the reply's {@code dropped} map is what would say so.</p>
 *
 * <h2>Side</h2>
 *
 * <p>Client only — {@code GuiIngame} exists only on the client. Recorded into the client event log
 * through {@link ForgeTestClientBootstrap#recordEvent(String, String)}.</p>
 *
 * <h2>Payload</h2>
 *
 * <p>{@code chatType} — the {@link ChatType} name ({@code CHAT}, {@code SYSTEM}, {@code GAME_INFO});
 * {@code text} — the message's UNFORMATTED text (translation keys resolved on the client, formatting
 * codes stripped), with quotes and backslashes made JSON-safe the same way the consumer's
 * {@code TestTrace.json} does (a double quote becomes a single quote, a backslash a slash), so a
 * reader greps for the words, not the exact punctuation.</p>
 *
 * <h2>What this is SILENT about</h2>
 *
 * <ul>
 *   <li>A message CANCELLED by a {@code ClientChatReceivedEvent} listener — Forge's
 *       {@code onClientChat} runs before this seam and a cancelled packet never reaches it. That is
 *       the correct reading of "received": the player never saw it either.</li>
 *   <li>Messages printed straight into {@code GuiNewChat.printChatMessage} by client-side code —
 *       those bypass this method. The one that matters in practice is
 *       {@code EntityPlayerSP.sendMessage}, whose whole body is
 *       {@code mc.ingameGUI.getChatGUI().printChatMessage(component)}: any mod code that calls
 *       {@code player.sendMessage(…)} on the CLIENT side is invisible here. The same call under
 *       {@code !world.isRemote} is not — it goes out as an {@code SPacketChat} and arrives through
 *       {@code handleChat}, which is this seam's only vanilla caller.</li>
 *   <li>Whether the message was RENDERED, scrolled off, or hidden by the chat visibility setting.
 *       The record says the HUD was told; it does not say a pixel changed.</li>
 *   <li>Who sent it: an {@code SPacketChat} carries no sender. The text is all there is.</li>
 * </ul>
 *
 * <p>Registered from the harness's own {@code mixins.forgetestframework.json} ({@code client} list),
 * which the harness coremod queues only under {@code -Dforge.test.client=true}.</p>
 */
@Mixin(GuiIngame.class)
public class MixinGuiIngameChat {

    /**
     * The harness names its observation points {@code client_*_events} ({@code client_sound_events},
     * {@code client_gui_events}, {@code client_entity_join_events}); this is that name for the chat
     * seam, so {@code assertInstrumentRan} reads the same way across the harness's recorders.
     */
    private static final String INSTRUMENT = "client_chat_events";

    @Inject(method = "addChatMessage", at = @At("HEAD"))
    private void forgeTest$recordChatReceived(ChatType chatType, ITextComponent message, CallbackInfo ci) {
        ForgeTestClientBootstrap.noteInstrumentEntered(INSTRUMENT);
        ForgeTestClientBootstrap.noteInstrumentEntered("client_chat_received");
        String type = chatType == null ? "null" : chatType.name();
        String text = message == null ? "" : jsonSafe(message.getUnformattedText());
        ForgeTestClientBootstrap.recordEvent("client_chat_received",
                "\"chatType\":\"" + type + "\",\"text\":\"" + text + "\"");
    }

    /** The consumer's {@code TestTrace.json} rule, repeated here because the harness cannot see it. */
    private static String jsonSafe(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace('\\', '/').replace('"', '\'').replace('\n', ' ').replace('\r', ' ');
    }
}
