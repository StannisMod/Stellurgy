package zmaster587.advancedRocketry.test.mixin;

import java.util.List;
import java.util.UUID;

import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.command.test.CrossingDiag;
import zmaster587.advancedRocketry.space.CrewTransfer;
import zmaster587.advancedRocketry.space.VSShipCrossingOps;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The two halves of a cell crossing's SETTLE, as events, at the ops implementation's own returns:
 * the pose written onto the arrived hull ({@code crossing_pose_settled}) and the crew put back on
 * it ({@code crossing_crew_reseated}).
 *
 * <p>Both are retried every tick until the physics mod has claimed the pasted blocks, so a "not yet"
 * is recorded when its reason changes and not per tick ({@link CrossingDiag}): the pose half has one
 * reason ("the anchor is still a block"), the re-seat carries the placement's own account of what it
 * is waiting on. The chain a granted entry then IS: {@code entry_decided(STARTED) →
 * cell_crossing_begun → crossing_pose_settled → crossing_crew_reseated → ledger_settled}.</p>
 */
@Mixin(VSShipCrossingOps.class)
public abstract class MixinVSShipCrossingOpsEvents {

    private static final String INSTRUMENT = "cell_crossing_events";

    @Inject(method = "teleportPoseWithRiders", at = @At("RETURN"))
    private void arTest$pose(int destDim, BlockPos anchor, UUID vsShipUuid, double px, double py,
                             double pz, CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        String key = "pose:" + destDim + ":" + vsShipUuid;
        if (cir.getReturnValueZ()) {
            CrossingDiag.clear(key);
            TestTrace.recordServer("crossing_pose_settled", "\"vsShip\":\"" + vsShipUuid + "\",\"dim\":"
                    + destDim + ",\"pose\":\"" + TestTrace.fmt(px) + "," + TestTrace.fmt(py) + ","
                    + TestTrace.fmt(pz) + "\"");
        } else if (CrossingDiag.noteBlocked(key, "pasted blocks not yet claimed")) {
            TestTrace.recordServer("crossing_pose_pending", "\"vsShip\":\"" + vsShipUuid + "\",\"dim\":"
                    + destDim + ",\"anchor\":\"" + (anchor == null ? "null"
                            : anchor.getX() + "," + anchor.getY() + "," + anchor.getZ()) + "\"");
        }
    }

    @Inject(method = "reseat", at = @At("RETURN"))
    private void arTest$reseat(int destDim, BlockPos anchor, List<CrewTransfer.Crew> crew, UUID shipId,
                               UUID vsShipUuid, CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        String key = "crossing-reseat:" + shipId;
        if (cir.getReturnValueZ()) {
            CrossingDiag.clear(key);
            TestTrace.recordServer("crossing_crew_reseated", "\"ship\":\"" + shipId + "\",\"dim\":" + destDim
                    + ",\"crew\":" + (crew == null ? 0 : crew.size()));
            return;
        }
        String block = CrewTransfer.lastReseatBlock();
        if (CrossingDiag.noteBlocked(key, block)) {
            TestTrace.recordServer("crossing_reseat_blocked", "\"ship\":\"" + shipId + "\",\"dim\":" + destDim
                    + ",\"crew\":" + (crew == null ? 0 : crew.size()) + ",\"block\":\""
                    + TestTrace.json(block) + "\"");
        }
    }
}
