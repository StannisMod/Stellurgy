package dev.stannismod.stellurgy.test.mixin;

import java.util.UUID;

import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.stannismod.stellurgy.space.CellCrossingController;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The two cell-to-cell crossings a settled ship can ask for, as events: a seam CARRY
 * ({@code carry_requested}) and a DIRECT jump ({@code direct_jump_requested}), each with its verdict.
 * A carry is asked for every tick a ship is past its cell's face, so the refused ones are recorded
 * only when the verdict changes for that ship — one record per stretch of refusals, not one per tick.
 */
@Mixin(CellCrossingController.class)
public abstract class MixinCellCrossingControllerEvents {

    @Inject(method = "requestCarry", at = @At("RETURN"))
    private void stellurgyTest$carry(int slotDim, BlockPos afcPos, UUID shipId, GalacticCoord cell,
                              double[] shipPos, CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentHere("cell_crossing_events");
        boolean granted = cir.getReturnValueZ();
        if (!granted && !dev.stannismod.stellurgy.test.trace.CrossingMemory.here()
                .noteBlocked("carry:" + shipId, "refused")) {
            return; // the same refusal as last tick
        }
        if (granted) {
            dev.stannismod.stellurgy.test.trace.CrossingMemory.here().clear("carry:" + shipId);
        }
        // The pose the verdict was decided ON. A test that waits for the flight computer's own carry
        // has no other way to learn it: a granted carry starts cutting the hull out in the same call,
        // so any read taken afterwards is of the crossing, not of what was judged. Absent when the caller
        // passed none, so a reader gets "not measured" rather than a zero.
        String pose = shipPos == null || shipPos.length < 3 ? ""
                : ",\"px\":" + shipPos[0] + ",\"py\":" + shipPos[1] + ",\"pz\":" + shipPos[2];
        TestTrace.recordServer("carry_requested", "\"ship\":\"" + shipId + "\",\"slotDim\":" + slotDim
                + ",\"cell\":\"" + (cell == null ? "null" : cell.cellKey()) + "\",\"granted\":" + granted
                + pose);
    }

    @Inject(method = "requestDirectJump", at = @At("RETURN"))
    private void stellurgyTest$directJump(int slotDim, BlockPos afcPos, UUID shipId, GalacticCoord cell,
                                   GalacticCoord target, CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentHere("cell_crossing_events");
        TestTrace.recordServer("direct_jump_requested", "\"ship\":\"" + shipId + "\",\"slotDim\":"
                + slotDim + ",\"target\":\"" + (target == null ? "null" : target.cellKey())
                + "\",\"granted\":" + cir.getReturnValueZ());
    }
}
