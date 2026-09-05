package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The deck capture's two edges as events, on whichever side they happen: a body is TAKEN by a ship's
 * deck, and it is RELEASED — with the gate that released it, in production's own words.
 *
 * <p>The resolver ends every episode through one private method that carries the reason as a
 * string ({@code release(entity, reason)}), so a "churn" — a capture cycled through the external-move
 * guard — is a sequence of {@code deck_released} records whose reasons start with
 * {@code externalMove}, and a walk off the deck's edge is one that reads {@code leftShipRegion} or
 * {@code steppedOntoTerrain}. The tests used to read the LAST reason and a cumulative drop counter
 * out of production statics; a counter cannot say which releases happened in THIS window nor in which
 * order, and the last reason cannot say what came before it.</p>
 *
 * <p>Routed by the body's own world, so the client's resolver writes the client log and the server's
 * the server log — the two never see each other's captures.</p>
 */
@Mixin(ShipFrameTravel.class)
public abstract class MixinShipFrameTravelEvents {

    private static final String INSTRUMENT = "deck_capture_events";

    @Inject(method = "captureState", at = @At("TAIL"))
    private static void arTest$captured(Entity entity, String shipId, double localX, double localY,
                                        double localZ, double worldX, double worldY, double worldZ,
                                        double carryX, double carryY, double carryZ, CallbackInfo ci) {
        TestTrace.instrument(entity, INSTRUMENT);
        if (entity == null || entity.world == null) {
            return;
        }
        TestTrace.record(entity, "deck_captured", "\"e\":" + entity.getEntityId()
                + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\",\"ship\":\"" + shipId
                + "\",\"local\":\"" + TestTrace.fmt(localX) + "," + TestTrace.fmt(localY) + ","
                + TestTrace.fmt(localZ) + "\",\"worldY\":" + TestTrace.fmt(worldY));
    }

    @Inject(method = "release", at = @At("HEAD"))
    private static void arTest$released(Entity entity, String reason, CallbackInfo ci) {
        TestTrace.instrument(entity, INSTRUMENT);
        if (entity == null || entity.world == null) {
            return;
        }
        // HEAD, before the state is removed: only a body that IS captured has an episode to end, and
        // `release` no-ops for an untracked one. Ask the same question production is about to ask.
        if (ShipFrameTravel.aboardShipId(entity) == null) {
            return;
        }
        TestTrace.record(entity, "deck_released", "\"e\":" + entity.getEntityId()
                + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\",\"reason\":\""
                + TestTrace.json(reason) + "\",\"y\":" + TestTrace.fmt(entity.posY));
    }
}
