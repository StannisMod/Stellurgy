package zmaster587.advancedRocketry.test.mixin;

import java.util.List;
import java.util.UUID;

import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.space.CrewTransfer;
import zmaster587.advancedRocketry.space.ShipCrossingService;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The momentary cell-to-cell crossing every entry, descent and seam carry is performed by, as an
 * event: {@code cell_crossing_begun} — the hull was cut out of its world and pasted into the
 * destination (or was not, which is {@code ok:false}), with the crew it took along.
 *
 * <p>The settle that follows is not recorded here: the service drives it through its {@code Ops}
 * (the pose write, the re-seat, the unpark), and each of those is recorded at the ops implementation's
 * own return, where the verdict is. The controller's completion callbacks are anonymous classes a
 * mixin cannot reach — the commit they make is the ledger write, recorded on the ledger itself.</p>
 */
@Mixin(ShipCrossingService.class)
public abstract class MixinShipCrossingServiceEvents {

    @Inject(method = "begin", at = @At("RETURN"))
    private void arTest$begun(UUID shipId, int srcDim, double[] srcShipPos, int destDim,
                              int pasteX, int pasteY, int pasteZ, List<CrewTransfer.Crew> crew,
                              double[] finalPose, ShipCrossingService.Completion completion,
                              CallbackInfoReturnable<BlockPos> cir) {
        TestTrace.instrumentHere("cell_crossing_events");
        BlockPos anchor = cir.getReturnValue();
        TestTrace.recordServer("cell_crossing_begun", "\"ship\":\"" + shipId + "\",\"srcDim\":" + srcDim
                + ",\"destDim\":" + destDim + ",\"crew\":" + (crew == null ? 0 : crew.size())
                + ",\"ok\":" + (anchor != null) + ",\"anchor\":\"" + (anchor == null ? "null"
                        : anchor.getX() + "," + anchor.getY() + "," + anchor.getZ()) + "\"");
    }
}
