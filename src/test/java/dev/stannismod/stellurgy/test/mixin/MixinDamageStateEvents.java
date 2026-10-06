package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.damage.DamageState;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * A block's damage stage being written, as an event: {@code block_stage_set}.
 *
 * <p>The HEAD of {@code DamageState.setStage}, the one door every stage write goes through — the
 * damage engine staging or destroying a block, the welder taking a stage off, whichever home (a
 * part's own wear or the world's position-keyed map) keeps it. {@code from} is the stage the block
 * carried as the write arrived, {@code to} what it is being given, and {@code max} the stage at
 * which it is destroyed, all read before the write; a block the engine destroys is written
 * {@code to = max} and then set to air. Recorded only when {@code from != to}. Server log, filed
 * against the world written to. Read through {@code dev.stannismod.stellurgy.test.Weapons}.</p>
 *
 * <p>SILENT about a relocation carrying a record from one position to another, which moves the map
 * entry directly and is not a stage write; and about a client world, where the method returns.</p>
 */
@Mixin(DamageState.class)
public abstract class MixinDamageStateEvents {

    private static final String INSTRUMENT = "damage_stage_events";

    @Inject(method = "setStage", at = @At("HEAD"), require = 1)
    private static void stellurgyTest$stageSet(World world, BlockPos pos, int stage, CallbackInfo ci) {
        if (world == null || pos == null || world.isRemote) {
            return;
        }
        TestTrace.instrument(world, INSTRUMENT);
        int from = DamageState.getStage(world, pos);
        if (from == stage) {
            return;
        }
        TestTrace.record(world, "block_stage_set", "\"dim\":" + world.provider.getDimension()
                + ",\"pos\":\"" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "\""
                + ",\"from\":" + from
                + ",\"to\":" + stage
                + ",\"max\":" + DamageState.getMaxStage(world, pos));
    }
}
