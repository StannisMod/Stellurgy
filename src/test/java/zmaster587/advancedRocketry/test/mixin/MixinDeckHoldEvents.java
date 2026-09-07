package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.player.EntityPlayerMP;

import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.integration.vs.DeckHold;
import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * WHY a deck hold ended, and what was holding the body when it did.
 *
 * <p>The hold is the server's half of putting a crew member back on a deck he was carried to: it
 * pins him to the arrival's own deck point every tick and asks his client to seed the capture. It
 * ends on one of three branches, and until this recorder existed all three were silent — a body that
 * finished a crossing anchored on the wrong hull looked identical to one anchored on the right one,
 * and the difference between "the hold quit early" and "the hold ran out" was unobservable.</p>
 *
 * <p>The three are separated by the {@code HOLDS.remove} they go through, in source order:</p>
 *
 * <ul>
 *   <li>{@code resolving} — the capture is on the hold's OWN ship and a seed has gone out. The test
 *       used to be {@code ShipFrameTravel.isResolving}, which answers whether ANY capture holds the
 *       body and never whether it is the one the hold is waiting for; this recorder is what measured
 *       it ending on a neighbour's hull, and the record still carries the ship so a future
 *       regression of the same shape names itself.</li>
 *   <li>{@code excluded} — the body entered a state that owns its own movement (riding, elytra,
 *       creative flight, water, a ladder). The seed would refuse anyway.</li>
 *   <li>{@code expired} — the window ran out. The ship never came back, and the body is handed to
 *       vanilla.</li>
 * </ul>
 *
 * <p>Each record carries the ship the CAPTURE is anchored on at that instant
 * ({@code aboard}), so a hold that ended on a foreign craft says so in the record rather than
 * leaving the reader to infer it from a probe read taken some ticks later.</p>
 *
 * <h2>What it is silent about</h2>
 *
 * <p><b>It cannot name the ship the HOLD was for.</b> That lives in a private field of a private
 * inner class and no injector here can reach it; the caller that armed the hold is the one that
 * knows, and it already records it ({@code crew_reseated} carries the durable name,
 * {@code hyperspace_arrival_cut} the physics id). Read the two together.</p>
 *
 * <p><b>It says nothing about the CLIENT.</b> The hold's whole job on the far side is to ask the
 * owning client to seed, and this seam is the server's; a hold that ended correctly here can still
 * leave a client that never seeded. That is what {@code deck_seed_decided} is for.</p>
 */
@Mixin(value = DeckHold.class, remap = false)
public abstract class MixinDeckHoldEvents {

    private static final String INSTRUMENT = "deck_hold_events";

    @Inject(method = "onPlayerTick",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;",
                    ordinal = 0))
    private void arTest$endedResolving(TickEvent.PlayerTickEvent event, CallbackInfo ci) {
        arTest$ended(event, "resolving");
    }

    @Inject(method = "onPlayerTick",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;",
                    ordinal = 1))
    private void arTest$endedExcluded(TickEvent.PlayerTickEvent event, CallbackInfo ci) {
        arTest$ended(event, "excluded");
    }

    @Inject(method = "onPlayerTick",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;",
                    ordinal = 2))
    private void arTest$endedExpired(TickEvent.PlayerTickEvent event, CallbackInfo ci) {
        arTest$ended(event, "expired");
    }

    private static void arTest$ended(TickEvent.PlayerTickEvent event, String why) {
        TestTrace.instrumentHere(INSTRUMENT);
        if (!(event.player instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        // The ship the CAPTURE is on, in either mode — not `aboardShipId`, which is deliberately
        // null in hull-stand and would report "nothing holds him" for a body clinging to the outside
        // of a neighbour's hull, which is precisely one of the shapes this recorder exists to name.
        String held = ShipFrameTravel.capturedShipId(player);
        TestTrace.record(player, "deck_hold_ended",
                "\"who\":\"" + TestTrace.json(player.getName()) + "\",\"why\":\"" + why
                        + "\",\"resolving\":" + ShipFrameTravel.isResolving(player)
                        + ",\"aboard\":" + (held == null ? "null"
                                : "\"" + TestTrace.json(held) + "\"")
                        + ",\"mode\":\""
                        + (held == null ? "none"
                                : ShipFrameTravel.isResolvingAboard(player) ? "aboard" : "hull")
                        + "\"");
    }
}
