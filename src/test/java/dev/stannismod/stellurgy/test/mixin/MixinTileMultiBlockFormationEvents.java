package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiBlock;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * A multiblock controller's completeness, recorded where it changes: {@code multiblock_formation}
 * for every attempt to form (carrying {@code complete}, the verdict), and {@code multiblock_part_lost}
 * when a part tells its controller it is going away. Both carry the controller's {@code dim},
 * {@code x}, {@code y}, {@code z}. Read by {@code MachineLibraryGroupTest}.
 *
 * <p><b>The seams.</b> {@code attemptCompleteStructure(IBlockState)} is where a controller's
 * completeness is DECIDED and stored: the validation walk runs inside it and its result is the flag
 * every later tick reads. A right-click, a probe's {@code machine try-complete} and a machine's own
 * periodic retry all arrive there, so a record names the decision whichever of them asked. The
 * first-tick re-validation of a machine that LOADED complete calls the walk directly and is not
 * recorded. {@code invalidateComponent(TileEntity)} is the one way a part un-completes its controller
 * — an item hatch calls it as its chunk unloads.</p>
 *
 * <p>Server worlds only: a client forms its own copy for the GUI, and that copy decides nothing. Each
 * is asked at most once per controller per retry period or per unload, so every record is kept.</p>
 */
@Mixin(TileMultiBlock.class)
public abstract class MixinTileMultiBlockFormationEvents {

    private static final String INSTRUMENT = "multiblock_formation_events";

    @Inject(method = "attemptCompleteStructure(Lnet/minecraft/block/state/IBlockState;)Z",
            at = @At("RETURN"), require = 1)
    private void stellurgyTest$formationDecided(IBlockState state, CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        stellurgyTest$recordOnServer("multiblock_formation", ",\"complete\":" + cir.getReturnValue());
    }

    @Inject(method = "invalidateComponent(Lnet/minecraft/tileentity/TileEntity;)V",
            at = @At("RETURN"), require = 1)
    private void stellurgyTest$partLost(TileEntity part, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        stellurgyTest$recordOnServer("multiblock_part_lost", "");
    }

    private void stellurgyTest$recordOnServer(String type, String tail) {
        TileMultiBlock self = (TileMultiBlock) (Object) this;
        World world = self.getWorld();
        if (world == null || world.isRemote) {
            return;
        }
        BlockPos pos = self.getPos();
        TestTrace.record(world, type,
                "\"dim\":" + world.provider.getDimension()
                        + ",\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ() + tail);
    }
}
