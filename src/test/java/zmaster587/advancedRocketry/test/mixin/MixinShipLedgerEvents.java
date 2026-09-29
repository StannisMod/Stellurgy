package zmaster587.advancedRocketry.test.mixin;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.ShipLedger;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The durable ledger's three writes as events — the one commit every kind of crossing ends in.
 *
 * <p>An entry, a descent, a seam carry and a hyperspace arrival each finish by telling the ledger
 * where the ship now is ({@code ledger_settled}); a departure marks it in flight
 * ({@code ledger_transit_begun}); a descent takes it out of space entirely ({@code ledger_removed}).
 * The controllers do this from completion callbacks a mixin cannot see, so the ledger itself is the
 * seam — and it is the better one: it is what the login restore and the descent trigger read, so a
 * chain that ends here ends where the rest of the game starts believing it.</p>
 */
@Mixin(ShipLedger.class)
public abstract class MixinShipLedgerEvents {

    private static final String INSTRUMENT = "ledger_events";

    @Inject(method = "settle", at = @At("HEAD"))
    private void arTest$settled(UUID shipId, GalacticCoord coord, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordServer("ledger_settled", "\"ship\":\"" + shipId + "\",\"cell\":\""
                + (coord == null ? "null" : coord.cellKey()) + "\"");
    }

    @Inject(method = "beginTransit", at = @At("HEAD"))
    private void arTest$transitBegun(UUID shipId, GalacticCoord target, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordServer("ledger_transit_begun", "\"ship\":\"" + shipId + "\",\"target\":\""
                + (target == null ? "null" : target.cellKey()) + "\"");
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void arTest$removed(UUID shipId, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordServer("ledger_removed", "\"ship\":\"" + shipId + "\"");
    }
}
