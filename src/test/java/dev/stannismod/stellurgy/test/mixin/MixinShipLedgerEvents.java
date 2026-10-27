package dev.stannismod.stellurgy.test.mixin;

import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.space.ShipLedger;
import dev.stannismod.stellurgy.test.trace.TestTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

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
    private void stellurgyTest$settled(UUID shipId, GalacticCoord coord, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordServer("ledger_settled", "\"ship\":\"" + shipId + "\",\"cell\":\""
                + (coord == null ? "null" : coord.cellKey()) + "\"");
    }

    @Inject(method = "beginTransit", at = @At("HEAD"))
    private void stellurgyTest$transitBegun(UUID shipId, GalacticCoord target, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordServer("ledger_transit_begun", "\"ship\":\"" + shipId + "\",\"target\":\""
                + (target == null ? "null" : target.cellKey()) + "\"");
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void stellurgyTest$removed(UUID shipId, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordServer("ledger_removed", "\"ship\":\"" + shipId + "\"");
    }

    /**
     * {@code ledger_hull_measured}: a settled ship's self-report brought the ledger a hull radius that
     * differs from the one it held — the first measurement after it came into space, or a hull that
     * changed. The self-report runs every tick of every settled ship, so this is filtered to the EDGE:
     * the record says when the ledger's knowledge of a hull changed, which is what a scenario waits on
     * before it lets the ship's world go. Its own instrument name, since it is its own hook.
     */
    @Inject(method = "updatePosition", at = @At("HEAD"))
    private void stellurgyTest$hullMeasured(UUID shipId, GalacticCoord coord, double hullRadius,
                                            CallbackInfo ci) {
        TestTrace.instrumentHere("ledger_hull_events");
        ShipLedger.Entry held = ((ShipLedger) (Object) this).get(shipId);
        if (held == null || held.state != ShipLedger.State.SETTLED || Double.isNaN(hullRadius)
                || Double.compare(held.hullRadius, hullRadius) == 0) {
            return;
        }
        TestTrace.recordServer("ledger_hull_measured", "\"ship\":\"" + shipId + "\",\"hullRadius\":"
                + hullRadius);
    }
}
