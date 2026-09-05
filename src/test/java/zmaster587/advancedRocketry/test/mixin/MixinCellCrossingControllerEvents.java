package zmaster587.advancedRocketry.test.mixin;

import java.util.UUID;

import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.space.CellCrossingController;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The two cell-to-cell crossings a settled ship can ask for, as events: a seam CARRY
 * ({@code carry_requested}) and a DIRECT jump ({@code direct_jump_requested}), each with its verdict.
 * A carry is asked for every tick a ship is past its cell's face, so the refused ones are recorded
 * only when the verdict changes for that ship — one record per stretch of refusals, not one per tick.
 */
@Mixin(CellCrossingController.class)
public abstract class MixinCellCrossingControllerEvents {

    @Inject(method = "requestCarry", at = @At("RETURN"))
    private void arTest$carry(int slotDim, BlockPos afcPos, UUID shipId, GalacticCoord cell,
                              double[] shipPos, CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentHere("cell_crossing_events");
        boolean granted = cir.getReturnValueZ();
        if (!granted && !zmaster587.advancedRocketry.command.test.CrossingDiag
                .noteBlocked("carry:" + shipId, "refused")) {
            return; // the same refusal as last tick
        }
        if (granted) {
            zmaster587.advancedRocketry.command.test.CrossingDiag.clear("carry:" + shipId);
        }
        TestTrace.recordServer("carry_requested", "\"ship\":\"" + shipId + "\",\"slotDim\":" + slotDim
                + ",\"cell\":\"" + (cell == null ? "null" : cell.cellKey()) + "\",\"granted\":" + granted);
    }

    @Inject(method = "requestDirectJump", at = @At("RETURN"))
    private void arTest$directJump(int slotDim, BlockPos afcPos, UUID shipId, GalacticCoord cell,
                                   GalacticCoord target, CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentHere("cell_crossing_events");
        TestTrace.recordServer("direct_jump_requested", "\"ship\":\"" + shipId + "\",\"slotDim\":"
                + slotDim + ",\"target\":\"" + (target == null ? "null" : target.cellKey())
                + "\",\"granted\":" + cir.getReturnValueZ());
    }
}
