package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.player.EntityPlayerMP;

import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.api.event.ShipEvent;
import zmaster587.advancedRocketry.integration.vs.DeckHold;
import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;
import zmaster587.advancedRocketry.space.ShipAboardTag;
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

    // require = 1 on every injector in this class, and it is the point rather than a precaution: the
    // expiry injection below matched NOTHING for as long as it named an ordinal that does not exist,
    // and an injector is not required by default, so the recorder's silence read as "no hold ever
    // ended". A seam's signature is checked when the mixin is APPLIED, never when it compiles; the
    // only way to be told is to demand a match.
    @Inject(method = "onPlayerTick", require = 1,
            at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;",
                    ordinal = 0))
    private void arTest$endedResolving(TickEvent.PlayerTickEvent event, CallbackInfo ci) {
        arTest$ended(event, "resolving");
    }

    @Inject(method = "onPlayerTick", require = 1,
            at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;",
                    ordinal = 1))
    private void arTest$endedExcluded(TickEvent.PlayerTickEvent event, CallbackInfo ci) {
        arTest$ended(event, "excluded");
    }

    /**
     * The EXPIRY, taken where it happens.
     *
     * <p>This used to be a third {@code Map.remove} ordinal inside {@code onPlayerTick} — and that
     * method contains exactly TWO such calls: the expiry's removal lives in {@code giveUp}, one
     * frame down. So the injection matched nothing and, injectors not being required by default,
     * said nothing about it either; the branch that hands a crew member to vanilla gravity on a
     * tilted deck was the one branch of the three that could never be recorded. Measured
     * 2026-09-13 while diagnosing a standing relog whose hold left no record at all.</p>
     *
     * <p>Taken at the CALL to {@code giveUp} rather than inside it, because its second parameter is
     * the private inner {@code Hold} and an {@code @Inject} handler has to name every parameter type
     * exactly. An {@code @At} target is a plain STRING, so the descriptor can name that type where
     * Java source cannot — the same door the re-seat-step redirect uses for the same reason.</p>
     */
    @Inject(method = "onPlayerTick", require = 1, at = @At(value = "INVOKE",
            target = "Lzmaster587/advancedRocketry/integration/vs/DeckHold;giveUp"
                    + "(Lnet/minecraft/entity/player/EntityPlayerMP;"
                    + "Lzmaster587/advancedRocketry/integration/vs/DeckHold$Hold;)V"),
            remap = false)
    private void arTest$endedExpired(TickEvent.PlayerTickEvent event, CallbackInfo ci) {
        arTest$ended(event, "expired");
    }

    /**
     * Whether a login ARMED a hold at all, and off the ending's path.
     *
     * <p>Without it this recorder could only speak when a hold ended, so it declared its instrument
     * there too — and a scenario with no records was unreadable: "no hold was ever armed", "the hold
     * is still live" and "the recorder is dead" all look like silence. Measured on the standing-relog
     * red, where the roster carried no {@code deck_hold_events} at all and the absence therefore
     * proved nothing. The login hook runs once per login and this reads production's own answer
     * afterwards ({@code isHeld}), so the record is a fact about the hold rather than about the tag
     * the hook consulted.</p>
     */
    @Inject(method = "onPlayerLoggedIn", at = @At("RETURN"), require = 1)
    private void arTest$loginConsidered(PlayerEvent.PlayerLoggedInEvent event, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        if (!(event.player instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        ShipAboardTag.Aboard aboard = ShipAboardTag.of(player);
        // WHICH WORLD the hook looked in. The durable resolve walks `player.world`'s loaded tiles
        // for the flight computer, and a login hook runs before the restore has moved the player
        // anywhere — so if this is not the dimension the placement chose, the lookup searched a
        // world his ship was never in, and the one-shot that is supposed to cover the missing load
        // edge could not have succeeded.
        TestTrace.record(player, "deck_hold_login",
                "\"who\":\"" + TestTrace.json(player.getName()) + "\""
                        + ",\"dim\":" + (player.world == null ? -999
                                : player.world.provider.getDimension())
                        + ",\"held\":" + DeckHold.isHeld(player)
                        + ",\"heldFor\":" + (DeckHold.heldShipId(player) == null ? "null"
                                : "\"" + TestTrace.json(DeckHold.heldShipId(player)) + "\"")
                        + ",\"tagged\":" + (aboard != null)
                        + ",\"posture\":\"" + (aboard == null ? "none" : aboard.posture) + "\""
                        + (aboard == null ? ""
                                : ",\"standDx\":" + TestTrace.fmt(aboard.standDx)
                                        + ",\"standDy\":" + TestTrace.fmt(aboard.standDy)
                                        + ",\"standDz\":" + TestTrace.fmt(aboard.standDz)));
    }

    /**
     * Each tick a live hold spends PINNING the body, and which of its two pins it used.
     *
     * <p>The three ending records say how a hold finished and nothing about what it did meanwhile —
     * and "meanwhile" is the whole question for a hold that never ends: the unresolved branch
     * teleports the body to its own position and ZEROES its motion every tick, which on a moving
     * deck is not a hold at all. {@code inPlace} is that branch; {@code deckPoint} is the resolved
     * one, which pins to the persisted deck point and seeds the client.</p>
     *
     * <p>One record per held tick, so at most {@code HOLD_WINDOW_TICKS} of them per hold — the
     * volume a per-tick recorder is allowed only because the window is bounded and short.</p>
     */
    @Inject(method = "onPlayerTick", require = 1, at = @At(value = "INVOKE",
            target = "Lzmaster587/advancedRocketry/integration/vs/DeckHold;holdUnresolved"
                    + "(Lnet/minecraft/entity/player/EntityPlayerMP;"
                    + "Lzmaster587/advancedRocketry/integration/vs/DeckHold$Hold;)V"),
            remap = false)
    private void arTest$pinnedInPlace(TickEvent.PlayerTickEvent event, CallbackInfo ci) {
        arTest$pin(event, "inPlace");
    }

    @Inject(method = "onPlayerTick", require = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/EntityPlayerMP;setPositionAndUpdate(DDD)V"))
    private void arTest$pinnedToDeckPoint(TickEvent.PlayerTickEvent event, CallbackInfo ci) {
        arTest$pin(event, "deckPoint");
    }

    /**
     * The two removals that end a hold from OUTSIDE the tick handler, and both were silent.
     *
     * <p>Measured 2026-09-13: a hold armed at a relog pinned the body for ten ticks and then stopped
     * pinning, with no ending record of any kind — and with the three tick-handler branches required
     * and bound, "it ended somewhere else" was the only reading left. These are the somewhere else.
     * A taxonomy of endings that covers three of five paths cannot tell "the hold ended for a reason"
     * from "the tick handler stopped running", which is the distinction the diagnosis turned on.</p>
     *
     * <p>The ship-gone record carries the event's ids rather than a player: the removal happens
     * inside an iterator loop where the entry is not a parameter, and WHICH craft went is the
     * question that path answers anyway.</p>
     */
    @Inject(method = "onPlayerLoggedOut", require = 1,
            at = @At(value = "INVOKE",
                    target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;"))
    private void arTest$endedLoggedOut(PlayerEvent.PlayerLoggedOutEvent event, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        if (event.player instanceof EntityPlayerMP) {
            EntityPlayerMP player = (EntityPlayerMP) event.player;
            TestTrace.record(player, "deck_hold_ended",
                    "\"who\":\"" + TestTrace.json(player.getName()) + "\",\"why\":\"loggedOut\"");
        }
    }

    @Inject(method = "onShipGone", require = 1,
            at = @At(value = "INVOKE", target = "Ljava/util/Iterator;remove()V"))
    private void arTest$endedShipGone(ShipEvent.ShipGoneEvent event, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordHere("deck_hold_ended",
                "\"why\":\"shipGone\",\"ship\":\"" + TestTrace.json(String.valueOf(event.shipId))
                        + "\",\"substrate\":\"" + TestTrace.json(String.valueOf(event.substrateId))
                        + "\"");
    }

    private static void arTest$pin(TickEvent.PlayerTickEvent event, String where) {
        TestTrace.instrumentHere(INSTRUMENT);
        if (!(event.player instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        TestTrace.record(player, "deck_hold_pin",
                "\"who\":\"" + TestTrace.json(player.getName()) + "\",\"where\":\"" + where + "\""
                        + ",\"x\":" + TestTrace.fmt(player.posX)
                        + ",\"y\":" + TestTrace.fmt(player.posY)
                        + ",\"z\":" + TestTrace.fmt(player.posZ)
                        + ",\"resolving\":" + ShipFrameTravel.isResolving(player));
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
