package zmaster587.advancedRocketry.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.test.trace.TestTrace;
import zmaster587.advancedRocketry.tile.multiblock.TileObservatory;
import zmaster587.advancedRocketry.universe.RegionScan;

/**
 * The observatory's survey as two events: the instrument was AIMED, and the picture ADVANCED.
 *
 * <h2>{@code region_scan_begun}</h2>
 *
 * <p>An operator's aim, in production's own verdict. {@code beginRegionScan} answers {@code true}
 * exactly when a survey is now in flight and {@code false} when it refused — on the client, with no
 * origin to aim FROM, or with a region no survey can walk. Read at the method's RETURN so every
 * exit is one record, and the verdict is the return value itself rather than a re-derivation. The
 * tests used to ask {@code getActiveScan() != null} in a loop, which cannot say whether the aim was
 * refused or merely not yet taken.</p>
 *
 * <h2>{@code region_scan_advanced}</h2>
 *
 * <p>One batch of looks written. {@code completeRegionScanIfDue} is called from the tile's every
 * server tick and returns early far more often than it resolves anything — no scan, no crystal,
 * nothing due, not enough distance data — so it is a chatty seam and only the CHANGE is recorded:
 * the survey object is immutable and every resolved batch replaces it (or drops it on completion),
 * so a snapshot of the shadowed {@code activeScan} and {@code lastScanDiscoveries} taken at HEAD
 * and compared at RETURN is the edge. {@code complete} is whether the instrument is idle again
 * afterwards, which is production's own "the survey is finished" — the reference is nulled at the
 * same place the operator's progress bar goes green.</p>
 *
 * <h2>Side and silence</h2>
 *
 * <p>Both seams are server-only by production's own guards ({@code world.isRemote} refuses the
 * aim; the tick calls the completion only on the server), routed by the calling thread so a client
 * call, should one ever reach the aim, lands in the client log as a refusal rather than being
 * dropped. SILENT about: a passive sweep ({@code beginPassiveSweep} is a separate seam and is not
 * recorded here), an abort ({@code abortRegionScan}), a survey held for want of a crystal, a step
 * stalled on distance data, and the obscured-look count — none of those changes the two shadowed
 * fields, so none produces a record. A survey resolved whole on its first completion pass (the
 * research switch off, so every cell is due at once) is one {@code region_scan_begun} then, on the
 * tile's next server tick, one {@code region_scan_advanced} with {@code complete:true} — the aim
 * never runs the completion itself.</p>
 */
@Mixin(TileObservatory.class)
public abstract class MixinTileObservatoryEvents {

    private static final String INSTRUMENT = "observatory_events";

    @Shadow private RegionScan activeScan;
    @Shadow private int lastScanDiscoveries;

    /** What the completion pass saw on entry — compared at its return so only a change is kept. */
    private RegionScan arTest$scanAtHead;
    private int arTest$discoveriesAtHead;

    @Inject(method = "beginRegionScan", at = @At("RETURN"))
    private void arTest$scanBegun(int dirX, int dirY, int dirZ, int distanceSteps,
                                  CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        TileObservatory self = (TileObservatory) (Object) this;
        TestTrace.recordHere("region_scan_begun", "\"pos\":\"" + arTest$xyz(self)
                + "\",\"dir\":\"" + dirX + "," + dirY + "," + dirZ
                + "\",\"distanceSteps\":" + distanceSteps
                + ",\"accepted\":" + cir.getReturnValue());
    }

    @Inject(method = "completeRegionScanIfDue", at = @At("HEAD"))
    private void arTest$completionEntered(CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        arTest$scanAtHead = activeScan;
        arTest$discoveriesAtHead = lastScanDiscoveries;
    }

    // RETURN, not TAIL: the method has four early exits and the record must be taken on every one
    // of them, because the comparison — not the exit — decides whether anything is said. No local
    // is captured, so the early-exit LVT trap does not apply.
    @Inject(method = "completeRegionScanIfDue", at = @At("RETURN"))
    private void arTest$completionLeft(CallbackInfo ci) {
        boolean scanChanged = activeScan != arTest$scanAtHead;
        boolean discoveriesChanged = lastScanDiscoveries != arTest$discoveriesAtHead;
        if (!scanChanged && !discoveriesChanged) {
            return;
        }
        TileObservatory self = (TileObservatory) (Object) this;
        TestTrace.recordHere("region_scan_advanced", "\"pos\":\"" + arTest$xyz(self)
                + "\",\"discoveries\":" + lastScanDiscoveries
                + ",\"complete\":" + (activeScan == null));
    }

    private static String arTest$xyz(TileObservatory tile) {
        if (tile.getPos() == null) {
            return "null";
        }
        return tile.getPos().getX() + "," + tile.getPos().getY() + "," + tile.getPos().getZ();
    }
}
