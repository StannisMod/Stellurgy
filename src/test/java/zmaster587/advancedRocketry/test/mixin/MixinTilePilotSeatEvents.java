package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.test.trace.TestTrace;
import zmaster587.advancedRocketry.tile.TilePilotSeat;

/**
 * The pilot-input chain's landing on the seat, as events: a control packet ARRIVED at a seat, a
 * command packet ARRIVED at a seat, and a control packet was DELIVERED to the linked flight
 * computer. The three facts are taken where production itself has them — the packet handler's own
 * entry and the one call that hands the input on — never from a line added to the seat.
 *
 * <h2>The events</h2>
 *
 * <ul>
 *   <li>{@code pilot_input_received} — HEAD of {@code useNetworkData}, when the packet id is
 *       {@code PACKET_PILOT_INPUT}: the seat's handler was entered with a free-flight control
 *       packet. Nothing has been decided yet; the pilot guard and the computer resolve come after.
 *       Payload: {@code seat} (the seat's block position), {@code who} (the sender's name),
 *       {@code remote} (the side production was told it is handling on — {@code true} only if a
 *       client ever receives one, which the seat never sends).</li>
 *   <li>{@code pilot_command_received} — the same HEAD, when the id is one of
 *       {@code PACKET_FLIGHT_ASSIST_TOGGLE} / {@code PACKET_AUTO_TAKEOFF_TOGGLE} /
 *       {@code PACKET_JUMP}. Payload: {@code seat}, {@code who}, {@code kind} — one of
 *       {@code "flight_assist"}, {@code "auto_takeoff"}, {@code "jump"}.</li>
 *   <li>{@code pilot_input_delivered} — the INVOKE of
 *       {@code TileAdvancedFlightComputer.setPilotInput} inside {@code useNetworkData}: the guard
 *       held, the computer resolved, and the input is being handed over. The descriptor is the one
 *       {@code MixinTilePilotSeatDiag.arTest$inputDelivered} weaves today, copied verbatim.
 *       Payload: {@code seat}, {@code who}.</li>
 * </ul>
 *
 * <h2>Which side, and which thread</h2>
 *
 * <p>{@code useNetworkData} runs on whichever side the packet was dispatched on — the server for
 * every packet the piloting client sends. It is not a netty-thread call: the packet layer schedules
 * the handler onto the receiving side's main thread before it runs, so these records are taken on
 * the server tick thread. Each is routed by the sender's own world where a player is in hand (the
 * exact answer), and by the calling thread's side otherwise, so a server-handled packet lands in the
 * server log. This mixin is in the common list because the seat class exists on both sides.</p>
 *
 * <h2>Beside the older diagnostic</h2>
 *
 * <p>{@code MixinTilePilotSeatDiag} still counts the same three moments into its own store, and both
 * mixins apply to this seat. They coexist only because no handler here shares a NAME and descriptor
 * with one there — a shared one is merged once and the loser's injection silently calls the winner's
 * body. Any handler or helper added below must keep that distinctness.</p>
 *
 * <h2>How often</h2>
 *
 * <p>Not per tick: the client sends a control packet on a change of intent, and re-asserts a held
 * one on its seat's own phase once a second, so a held throttle produces about one
 * {@code pilot_input_received} (and one {@code pilot_input_delivered}) per second rather than
 * twenty. The 256-deep per-type ring therefore holds minutes of a flight, not seconds.</p>
 *
 * <h2>What this mixin is silent about</h2>
 *
 * <p>It does not say WHY an input was not delivered — the guard's verdict and the computer resolve
 * are the Diag mixin's to name, and a chain with {@code pilot_input_received} but no
 * {@code pilot_input_delivered} says only that one of them refused. It does not see the packet
 * leave the client ({@code pilot_input_sent} is the key-binding mixin's) nor what the computer did
 * with the input once it had it. A packet with an id the seat does not know is not recorded. Order
 * between the events is measured on a run, never assumed here.</p>
 */
@Mixin(TilePilotSeat.class)
public abstract class MixinTilePilotSeatEvents {

    private static final String INSTRUMENT = "pilot_seat_events";

    /** The delivery seam reports separately from the two packet-arrival seams. Those sit on the
     *  handler's HEAD and cannot miss; this one is an {@code INVOKE} descriptor, which stops
     *  matching in SILENCE if the call it names is renamed or moved into a helper. Under one shared
     *  name the arrival seams would vouch for a delivery seam that had died, and "the guard refused"
     *  would be indistinguishable from "the instrument is gone". */
    private static final String INSTRUMENT_DELIVERED = "pilot_seat_delivery_events";

    @Inject(method = "useNetworkData", at = @At("HEAD"))
    private void arTest$packetReceived(EntityPlayer player, Side side, byte id, NBTTagCompound nbt,
                                       CallbackInfo ci) {
        arTest$instrument(player, INSTRUMENT);
        TilePilotSeat self = (TilePilotSeat) (Object) this;
        if (id == TilePilotSeat.PACKET_PILOT_INPUT) {
            arTest$record(player, "pilot_input_received",
                    "\"seat\":\"" + arTest$seatXyz(self.getPos())
                            + "\",\"who\":\"" + arTest$who(player)
                            + "\",\"remote\":" + (side != null && side.isClient()));
            return;
        }
        String kind = arTest$commandKind(id);
        if (kind != null) {
            arTest$record(player, "pilot_command_received",
                    "\"seat\":\"" + arTest$seatXyz(self.getPos())
                            + "\",\"who\":\"" + arTest$who(player)
                            + "\",\"kind\":\"" + kind + "\"");
        }
    }

    // The descriptor is copied from MixinTilePilotSeatDiag.arTest$inputDelivered, which weaves
    // today; the two mixins inject at the same call and mixin merges both handlers.
    //
    // The NAME, however, is deliberately NOT copied. Every method a mixin declares is merged into
    // the target under its own name, so two mixins on one target that declare the same name AND
    // descriptor collide: the second is skipped with a warning, and its injection then calls the
    // FIRST one's body. That failure is silent in every way that matters — this event would never
    // be recorded while the older diagnostic counted each delivery twice. Hence the suffix here,
    // and on the coordinate helper below, which the Diag mixin also declares.
    @Inject(method = "useNetworkData",
            at = @At(value = "INVOKE",
                    target = "Lzmaster587/advancedRocketry/tile/TileAdvancedFlightComputer;"
                            + "setPilotInput(Lzmaster587/advancedRocketry/api/FreeFlightInput;)V"))
    private void arTest$inputDeliveredEvent(EntityPlayer player, Side side, byte id,
                                            NBTTagCompound nbt, CallbackInfo ci) {
        arTest$instrument(player, INSTRUMENT_DELIVERED);
        TilePilotSeat self = (TilePilotSeat) (Object) this;
        arTest$record(player, "pilot_input_delivered",
                "\"seat\":\"" + arTest$seatXyz(self.getPos())
                        + "\",\"who\":\"" + arTest$who(player) + "\"");
    }

    private static String arTest$commandKind(byte id) {
        if (id == TilePilotSeat.PACKET_FLIGHT_ASSIST_TOGGLE) {
            return "flight_assist";
        }
        if (id == TilePilotSeat.PACKET_AUTO_TAKEOFF_TOGGLE) {
            return "auto_takeoff";
        }
        if (id == TilePilotSeat.PACKET_JUMP) {
            return "jump";
        }
        return null;
    }

    private static void arTest$instrument(EntityPlayer player, String name) {
        if (player != null && player.world != null) {
            TestTrace.instrument(player, name);
        } else {
            TestTrace.instrumentHere(name);
        }
    }

    private static void arTest$record(EntityPlayer player, String type, String payload) {
        if (player != null && player.world != null) {
            TestTrace.record(player, type, payload);
        } else {
            TestTrace.recordHere(type, payload);
        }
    }

    private static String arTest$who(EntityPlayer player) {
        return player == null ? "null" : TestTrace.json(player.getName());
    }

    private static String arTest$seatXyz(BlockPos pos) {
        return "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }
}
