package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.test.trace.TestTrace;
import zmaster587.advancedRocketry.util.DelayedActionBar;

/**
 * A deferred action-bar notice as an event: production decided to tell a player something, and
 * deliberately put it a few ticks OUT so it lands after the mount packet's tracker flush instead
 * of under vanilla's "press X to dismount" hint.
 *
 * <h2>The event</h2>
 *
 * <ul>
 *   <li>{@code action_bar_queued} — HEAD of the public static
 *       {@code DelayedActionBar.send(EntityPlayerMP, ITextComponent, int)}: the moment a notice is
 *       put on the pending list, which is the moment the DECISION to notify was taken (a seat found
 *       occupied, a seat on an unassembled hull, a crossing that landed on a taken seat). Recorded
 *       through the player's own world, which for an {@code EntityPlayerMP} is the server log.
 *       {@code who} is the player's name; {@code key} is the translation key when the component is
 *       a {@code TextComponentTranslation} — every production caller today passes one — and the
 *       component's unformatted text otherwise; {@code delayTicks} is the ARGUMENT as the caller
 *       gave it, before production's own {@code max(1, …)} clamp, so a caller asking for zero
 *       records zero.</li>
 * </ul>
 *
 * <h2>What this mixin is silent about</h2>
 *
 * <p>It does not say the message was DELIVERED: the drain runs in a later server tick, skips a
 * player who has since left, and is cleared wholesale when the server is gone — none of that is
 * seen here. The delivery itself is {@code EntityPlayerMP.sendStatusMessage}, a separate event
 * ({@code status_message_sent}) recorded by its own mixin; the two are correlated by {@code who}
 * and {@code key} on a run, and their order is measured there, never assumed here. It also does not
 * see the translation's ARGUMENTS (an occupant's name), only the key.</p>
 *
 * <p><b>And it is not the only door to the action bar.</b> A caller that wants its line NOW does not
 * queue at all: the pilot seat's "someone is already piloting" refusal and the flight computer's
 * "your seat was destroyed" notice both call {@code sendStatusMessage(component, true)} straight on
 * the player. So an absence of {@code action_bar_queued} is NOT evidence that nothing appeared above
 * the hotbar — {@code status_message_sent} is the type that sees every line, and this one only ever
 * says which of them were deliberately DEFERRED.</p>
 */
@Mixin(DelayedActionBar.class)
public abstract class MixinDelayedActionBarEvents {

    private static final String INSTRUMENT = "action_bar_events";

    @Inject(method = "send", at = @At("HEAD"))
    private static void arTest$queued(EntityPlayerMP player, ITextComponent message, int delayTicks,
            CallbackInfo ci) {
        if (player == null) {
            TestTrace.instrumentHere(INSTRUMENT);
            TestTrace.recordHere("action_bar_queued", "\"who\":\"?\",\"key\":\""
                    + TestTrace.json(arTest$key(message)) + "\",\"delayTicks\":" + delayTicks);
            return;
        }
        TestTrace.instrument(player, INSTRUMENT);
        TestTrace.record(player, "action_bar_queued",
                "\"who\":\"" + TestTrace.json(player.getName())
                + "\",\"key\":\"" + TestTrace.json(arTest$key(message))
                + "\",\"delayTicks\":" + delayTicks);
    }

    /** The translation key when there is one, else the literal text; never null. */
    private static String arTest$key(ITextComponent message) {
        if (message == null) {
            return "null";
        }
        if (message instanceof TextComponentTranslation) {
            return ((TextComponentTranslation) message).getKey();
        }
        return message.getUnformattedText();
    }
}
