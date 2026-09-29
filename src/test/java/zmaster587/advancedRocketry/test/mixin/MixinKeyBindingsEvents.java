package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.client.KeyBindings;
import zmaster587.advancedRocketry.entity.EntityDummy;
import zmaster587.advancedRocketry.test.trace.TestTrace;
import zmaster587.advancedRocketry.tile.TilePilotSeat;

/**
 * The CLIENT half of the pilot-input chain as events: whether this client's keybind handler
 * decided it was piloting a ship this tick, and whether it actually put a pilot-input packet on
 * the wire.
 *
 * <h2>The events, each taken where production already answers</h2>
 *
 * <ul>
 *   <li>{@code pilot_input_sent} — at the INVOKE of {@code PacketHandler.sendToServer} inside
 *       {@code KeyBindings.handleShipPilotInput}: the one call that sends a
 *       {@code PACKET_PILOT_INPUT} for the seat the pilot sits on. Production sends only when
 *       {@code PilotInputCadence.shouldSend} says the intent changed or its re-assert phase came
 *       up, so this is already an edge and never a per-tick record. Carries {@code seat} — the
 *       seat's block position in the same {@code "(x,y,z)"} form the server half
 *       ({@code pilot_input_received}) writes, so one chain can join the two by it.</li>
 *   <li>{@code ship_pilot_gate_decided} — RETURN of the same method, which returns {@code true}
 *       exactly when the player is piloting a ship this tick; that return IS the gate. Recorded on
 *       every return while the player rides an {@link EntityDummy} (a seat mount), and on the open
 *       side always; a walking tick's {@code false} is not a refusal and is dropped, as production
 *       itself distinguishes at the early return. Carries {@code open} (the return value) and
 *       {@code ridingDummy}. Per seated tick by design — like {@code deck_gate_decided} it has its
 *       own ring, so a test awaits the gate's CURRENT answer rather than its last flip; a test that
 *       needs an edge reads two consecutive records. THE COST, stated: twenty records a second
 *       while a player sits on a seat mount, against a 256-deep per-type ring — roughly thirteen
 *       seconds of history. A test that wants to see the gate's answer from further back than that
 *       must read it while it is happening, not afterwards; nothing else shares this type's ring, so
 *       the churn costs no other event anything.</li>
 * </ul>
 *
 * <h2>Beside the older diagnostic</h2>
 *
 * <p>{@code MixinKeyBindingsSeatGate} still counts the same two moments into its own store, and both
 * mixins apply to this class at the same two injection points. They coexist only because no handler
 * here shares a NAME and descriptor with one there: a shared one is merged once, and the loser's
 * injection then calls the winner's body — the event silently never recorded, the counter silently
 * doubled. Any handler or helper added below must keep that distinctness.</p>
 *
 * <h2>How {@code seat} is obtained without a local capture</h2>
 *
 * <p>The seat production resolved is a local of the target method. Rather than a
 * {@code LocalCapture} (a child-JVM fatal when the LVT differs), the send hook asks the same PURE
 * oracle production asked at the method's head — {@link TilePilotSeat#forShipPilot} on the same
 * mount and world, in the same tick, before anything between the two calls could change it. The
 * answer cannot differ from production's; it is a read of the same state in the same instant. Were
 * it to answer null anyway, the record says {@code "unresolved"} instead of a coordinate, so a
 * reader can tell the fallback from a seat.</p>
 *
 * <p>Both injections use {@code CallbackInfoReturnable<Boolean>}: the target returns a boolean and
 * mixin requires the returnable form for ANY injection into it, the mid-method INVOKE included
 * (measured 2026-08-21 — the plain form fails the whole client at keybind registration). The INVOKE
 * descriptor is copied verbatim from {@code MixinKeyBindingsSeatGate}, which weaves today. Whether it
 * weaves HERE, and in what order these records fall against the seat's own, is a fact of a run.</p>
 *
 * <h2>Side</h2>
 *
 * <p>{@code KeyBindings} exists only on the client; both records are routed by the player's world
 * and land in the client log. This mixin belongs in the {@code client} list.</p>
 *
 * <h2>What this mixin is silent about</h2>
 *
 * <p>It does not say WHAT input was sent (the {@code FreeFlightInput} is another local, and the
 * server's {@code pilot_input_received} / {@code pilot_input_delivered} are where its arrival is
 * read); it does not see the toggle / jump commands, which leave through a different path
 * ({@code pilot_command_sent} is deferred — the packet's id has no getter); it does not say WHY the
 * gate closed on a seated tick (no link, or a link whose ship does not exist — the resolver's own
 * reading is {@code MixinTilePilotSeatDiag}'s); and it counts nothing on a tick where the player
 * rides nothing at all.</p>
 */
@Mixin(KeyBindings.class)
public abstract class MixinKeyBindingsEvents {

    private static final String INSTRUMENT_SENT = "pilot_input_sent_events";
    private static final String INSTRUMENT_GATE = "ship_pilot_gate_events";

    @Inject(method = "handleShipPilotInput",
            at = @At(value = "INVOKE",
                    target = "Lzmaster587/libVulpes/network/PacketHandler;"
                            + "sendToServer(Lzmaster587/libVulpes/network/BasePacket;)V"))
    // CallbackInfoReturnable even though this sits mid-method: the target returns a boolean.
    //
    // Named `...SentEvent`, NOT `arTest$inputSent`: MixinKeyBindingsSeatGate declares a handler of
    // that exact name and descriptor at this exact call, and two mixins on one target may not.
    // Mixin merges the first and skips the second with a warning, after which BOTH injections call
    // the surviving body — which would leave this event permanently unrecorded while the older
    // counter double-counted. The two files coexist only through distinct handler names.
    private void arTest$inputSentEvent(Minecraft mc, EntityPlayerSP player,
                                       CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrument(player, INSTRUMENT_SENT);
        TilePilotSeat seat = TilePilotSeat.forShipPilot(player.getRidingEntity(), mc.world);
        String where = seat == null ? "unresolved" : arTest$xyz(seat.getPos());
        TestTrace.record(player, "pilot_input_sent", "\"seat\":\"" + where + "\"");
    }

    @Inject(method = "handleShipPilotInput", at = @At("RETURN"))
    private void arTest$gateDecided(Minecraft mc, EntityPlayerSP player,
                                    CallbackInfoReturnable<Boolean> cir) {
        if (player == null) {
            // Production dereferences the player before either return, so this cannot be reached;
            // kept so a null can never turn the instrument into a crash inside the keybind tick.
            TestTrace.instrumentHere(INSTRUMENT_GATE);
            return;
        }
        TestTrace.instrument(player, INSTRUMENT_GATE);
        boolean open = cir.getReturnValue();
        boolean ridingDummy = player.getRidingEntity() instanceof EntityDummy;
        if (!open && !ridingDummy) {
            return; // a walking tick: not a refusal, noise
        }
        // The early return (seat == null) has no seat to read; the payload deliberately carries none.
        TestTrace.record(player, "ship_pilot_gate_decided",
                "\"open\":" + open + ",\"ridingDummy\":" + ridingDummy);
    }

    private static String arTest$xyz(BlockPos pos) {
        return "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }
}
