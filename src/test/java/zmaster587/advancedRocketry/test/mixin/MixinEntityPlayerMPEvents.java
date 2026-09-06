package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The two vanilla exits through which the server TELLS a player something, as events — so a test
 * can await "the game said X to him" instead of polling the client's chat history for a string.
 *
 * <h2>The events, each taken at the seam every message passes</h2>
 *
 * <ul>
 *   <li>{@code status_message_sent} — HEAD of {@code EntityPlayerMP.sendStatusMessage(ITextComponent,
 *       boolean)}: the server is about to send this player a status line, either above the hotbar
 *       ({@code actionBar} true — the form the delayed action bar, the pilot seat and the flight
 *       computer use) or into chat ({@code actionBar} false). Every action-bar line in the game
 *       ends here, whichever mod-side helper composed it.</li>
 *   <li>{@code chat_message_sent} — HEAD of {@code EntityPlayerMP.sendMessage(ITextComponent)}: the
 *       {@code ICommandSender} exit — command feedback, the crew-transfer refusals, anything a mod
 *       addressed to one player as chat.</li>
 * </ul>
 *
 * <p>Both record what production is about to hand to the connection, before the packet is built:
 * {@code who} (the player's name), {@code key} (the translation key when the component IS a
 * {@link TextComponentTranslation}, else empty), {@code text} (the component's
 * {@code getUnformattedText()}, which on the server is the key or the literal — the client
 * translates), and for a status line also {@code args} (the translation's format arguments,
 * stringified) and {@code actionBar}. Recorded on the SERVER log via {@code recordServer}: the
 * target is the server-side player and both methods run on the server thread, dedicated or
 * integrated.</p>
 *
 * <h2>What this mixin is silent about</h2>
 *
 * <p>It does not say that the client RECEIVED or DISPLAYED the message — that is the packet's and
 * the client's business; it does not see a broadcast that bypasses these methods
 * ({@code PlayerList.sendMessage} writes packets to every connection directly and never enters
 * {@code EntityPlayerMP.sendMessage}); and it does not see a component sent through
 * {@code connection.sendPacket} by hand. A per-message record with no edge filter: an action-bar
 * line re-sent every tick will turn the 256-deep ring over in about 13 s, so a test awaits the
 * first occurrence rather than reading the log after a long wait. (No production caller sends one
 * per tick today — the four in the mod are the delayed action bar's drain, the pilot-seat and
 * flight-computer refusals, and the client proxy below.)</p>
 *
 * <p><b>The whole CLIENT half is outside this seam.</b> The mixin targets
 * {@code EntityPlayerMP}, and the two methods above are declared on it (they are not inherited
 * calls into {@code EntityPlayer}), so a status line written on the client's own
 * {@code EntityPlayerSP} enters a DIFFERENT declaring class and is never recorded here.
 * {@code ClientProxy.sendClientStatusMessage} is exactly that: it composes a
 * {@code TextComponentTranslation} and hands it to {@code Minecraft.getMinecraft().player}. A test
 * awaiting a notice the client raised locally must await it in the client log through some other
 * instrument — a silence here is not evidence that nothing was shown.</p>
 *
 * <p>A vanilla target, so the mixin is NOT {@code remap = false}: the method names are MCP names
 * and are what the dev-time class carries. Whether the two injections weave is confirmed only on a
 * run.</p>
 */
@Mixin(EntityPlayerMP.class)
public abstract class MixinEntityPlayerMPEvents {

    private static final String INSTRUMENT_STATUS = "status_message_events";
    private static final String INSTRUMENT_CHAT = "chat_message_events";

    @Inject(method = "sendStatusMessage", at = @At("HEAD"))
    private void arTest$statusMessageSent(ITextComponent chatComponent, boolean actionBar,
                                          CallbackInfo ci) {
        EntityPlayerMP self = (EntityPlayerMP) (Object) this;
        // Routed by the player's own world, NOT by the effective side: the record below is written
        // to the server log unconditionally, and an instrument note that landed in the client log
        // would report this seam as "never ran" in the very log the records are in.
        TestTrace.instrument(self, INSTRUMENT_STATUS);
        TestTrace.recordServer("status_message_sent",
                "\"who\":\"" + TestTrace.json(self.getName()) + "\""
                        + ",\"key\":\"" + TestTrace.json(arTest$key(chatComponent)) + "\""
                        + ",\"args\":" + arTest$args(chatComponent)
                        + ",\"actionBar\":" + actionBar
                        + ",\"text\":\"" + TestTrace.json(arTest$text(chatComponent)) + "\"");
    }

    @Inject(method = "sendMessage", at = @At("HEAD"))
    private void arTest$chatMessageSent(ITextComponent component, CallbackInfo ci) {
        EntityPlayerMP self = (EntityPlayerMP) (Object) this;
        TestTrace.instrument(self, INSTRUMENT_CHAT);
        TestTrace.recordServer("chat_message_sent",
                "\"who\":\"" + TestTrace.json(self.getName()) + "\""
                        + ",\"key\":\"" + TestTrace.json(arTest$key(component)) + "\""
                        + ",\"text\":\"" + TestTrace.json(arTest$text(component)) + "\"");
    }

    /** The translation key when the component is one, else empty — never a guess from the text. */
    private static String arTest$key(ITextComponent component) {
        return component instanceof TextComponentTranslation
                ? ((TextComponentTranslation) component).getKey() : "";
    }

    private static String arTest$text(ITextComponent component) {
        return component == null ? "" : component.getUnformattedText();
    }

    /**
     * The translation's format arguments as a JSON array of strings; a nested component argument is
     * rendered by its own unformatted text so a key inside a key stays readable. {@code []} for a
     * non-translation component.
     */
    private static String arTest$args(ITextComponent component) {
        if (!(component instanceof TextComponentTranslation)) {
            return "[]";
        }
        Object[] formatArgs = ((TextComponentTranslation) component).getFormatArgs();
        StringBuilder sb = new StringBuilder("[");
        if (formatArgs != null) {
            for (Object arg : formatArgs) {
                if (sb.length() > 1) {
                    sb.append(',');
                }
                String rendered = arg instanceof ITextComponent
                        ? ((ITextComponent) arg).getUnformattedText() : String.valueOf(arg);
                sb.append('"').append(TestTrace.json(rendered)).append('"');
            }
        }
        return sb.append(']').toString();
    }
}
