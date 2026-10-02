package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.space.ShipTransitManager;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The lifecycle of a hyperspace jump, as EVENTS: departed, refused, settled.
 *
 * <h2>Whose model names these</h2>
 *
 * <p>The transit manager's own log already says "transit departed" and "transit settled"; these are
 * the same two facts, recorded where production commits them rather than where it talks about them.
 * A departure is committed when the durable ledger is told about it — that call sits after the
 * depart cut has succeeded and the transit is in the map — and a settle is committed the same way.
 * Both are private one-line methods with the ship and the coordinate as their only arguments, so
 * neither injection needs a local.</p>
 *
 * <p>A REFUSAL is a first-class event too. {@code beginTransit} returns {@code false} for a ship
 * already in flight and for a depart cut that failed, and a jump that never began looks, from every
 * later probe, exactly like a jump that hung. The record names it.</p>
 *
 * <p>Every hook announces itself on entry ({@link TestTrace#instrumentHere}), so a chain that is
 * about to conclude something from an empty log can first assert that somebody was looking.</p>
 */
@Mixin(ShipTransitManager.class)
public abstract class MixinShipTransitManagerEvents {

    private static final String INSTRUMENT = "transit_events";

    /** The departure is COMMITTED: the cut succeeded, the transit is in the map, the ledger is told. */
    @Inject(method = "ledgerBeginTransit", at = @At("HEAD"))
    private void stellurgyTest$departed(String shipId, GalacticCoord target, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        ShipTransitManager self = (ShipTransitManager) (Object) this;
        stellurgyTest$record("transit_departed", "\"ship\":\"" + shipId + "\",\"target\":\""
                + (target == null ? "null" : target.cellKey()) + "\",\"crew\":"
                + self.crewCountOf(shipId));
    }

    /**
     * What {@code beginTransit} answered. {@code false} is a refusal (already in flight, or the cut
     * failed and the crew was put back); {@code true} with no transit in the map is the DIRECT
     * crossing a short hop is performed as, which never enters hyperspace at all.
     */
    @Inject(method = "beginTransit", at = @At("RETURN"))
    private void stellurgyTest$beginReturned(String shipId, GalacticCoord origin, int originSlotDim,
                                      BlockPos originAnchor, GalacticCoord target, long speed,
                                      CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        ShipTransitManager self = (ShipTransitManager) (Object) this;
        boolean began = cir.getReturnValueZ();
        if (!began) {
            stellurgyTest$record("transit_refused", "\"ship\":\"" + shipId + "\",\"originSlotDim\":"
                    + originSlotDim + ",\"alreadyInTransit\":" + self.isInTransit(shipId));
        } else if (!self.isInTransit(shipId)) {
            stellurgyTest$record("transit_direct_crossing", "\"ship\":\"" + shipId + "\",\"target\":\""
                    + (target == null ? "null" : target.cellKey()) + "\"");
        }
    }

    /** The arrival is COMMITTED: the hull sits on its pose, its people are aboard, the ledger is told. */
    @Inject(method = "ledgerSettle", at = @At("HEAD"))
    private void stellurgyTest$settled(String shipId, GalacticCoord coord, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        stellurgyTest$record("transit_settled", "\"ship\":\"" + shipId + "\",\"cell\":\""
                + (coord == null ? "null" : coord.cellKey()) + "\"");
    }

    private static void stellurgyTest$record(String type, String payload) {
        TestTrace.recordServer(type, payload);
    }
}
