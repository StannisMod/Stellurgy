package zmaster587.advancedRocketry.test.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.SpaceManager;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * Every CLAIM taken on a cell and every one given back, as events.
 *
 * <h2>Why this is a mixin and not a probe verb</h2>
 *
 * <p>The numbers that decide whether a slot can be reused are a private map on {@link SpaceManager}
 * and the world's own occupancy; nothing outside can see either. The first attempt at this added a
 * public accessor to {@code SpaceManager} and a read-only census verb beside it — which is a change
 * to production's surface, made for an instrument, and the surface of a production class is an
 * architectural decision rather than a convenience a test may help itself to. Maintainer, 2026-09-12:
 * *«Публичный API - это архитектурное решение, помнишь?»* A test mixin shadows the field instead and
 * production gains nothing.</p>
 *
 * <h2>Mutations, not a snapshot — and that is the better instrument anyway</h2>
 *
 * <p>The question behind this is what ACCUMULATES between scenarios that share one server: a third
 * on-ramp scenario added to one class made whichever ran last fail in its arrangement, 3/3, while
 * passing alone on the same commit. A snapshot taken at the failure says which cells are held; the
 * sequence says WHO took each claim and whether anything gave it back, which is the half a snapshot
 * cannot reconstruct. Each record carries the caller trail for that reason.</p>
 *
 * <p>Recorded on the SERVER's own channel ({@code recordServer}): a cell claim belongs to no world —
 * it is the reason a world exists at all, so there is no world to key it against, and at the moment
 * of the first claim the slot may not be bound yet.</p>
 */
@Mixin(SpaceManager.class)
public abstract class MixinSpaceCellClaims {

    @Shadow(remap = false)
    private Map<String, Integer> refCount;

    @Inject(method = "materialize", at = @At("RETURN"), remap = false)
    private void arTest$claimTaken(GalacticCoord coord, CallbackInfoReturnable<Integer> cir) {
        arTest$claim(coord, "cell_claim_taken", cir.getReturnValue());
    }

    @Inject(method = "dematerialize", at = @At("RETURN"), remap = false)
    private void arTest$claimReleased(GalacticCoord coord, CallbackInfo ci) {
        arTest$claim(coord, "cell_claim_released", Integer.MIN_VALUE);
    }

    private void arTest$claim(GalacticCoord coord, String type, int slotDim) {
        if (coord == null) {
            return;
        }
        String cellKey = coord.cellKey();
        // The count AFTER the call, read off the map production keeps. Emitted in every state,
        // including the absent one as -1: a record that drops the field when there is no entry
        // answers "this cell has no claims" and "I could not look" with the same silence, and those
        // are the two readings this instrument exists to separate.
        Integer after = refCount == null ? null : refCount.get(cellKey);
        TestTrace.instrumentHere("cell_claim_events");
        TestTrace.recordServer(type,
                "\"cell\":\"" + cellKey + "\""
                        + ",\"claims\":" + (after == null ? -1 : after)
                        + ",\"knownCells\":" + (refCount == null ? -1 : refCount.size())
                        + (slotDim == Integer.MIN_VALUE ? "" : ",\"slotDim\":" + slotDim)
                        + ",\"by\":\"" + TestTrace.callerTrail() + "\"");
    }
}
