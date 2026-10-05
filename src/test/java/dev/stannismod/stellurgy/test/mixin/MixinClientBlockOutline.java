package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.RayTraceResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * A block outlined as the one the cursor is on, as an event: {@code client_block_outlined}.
 *
 * <p>The HEAD of {@code RenderGlobal#drawSelectionBox}, which the frame reaches only when no
 * {@code DrawBlockHighlightEvent} listener cancelled it. Carries {@code outlined: true} and the block's
 * {@code x/y/z}. At most one record per client tick, since the frame draws it many times a tick.
 * Client log. Read by {@code HelmControlsClientGroupTest}.</p>
 */
@Mixin(RenderGlobal.class)
public abstract class MixinClientBlockOutline {

    private static final String INSTRUMENT = "client_block_outlines";

    /** The world tick this renderer last recorded an outline in; one record a tick is enough. */
    @Unique
    private long stellurgyTest$lastOutlineTick = Long.MIN_VALUE;

    @Inject(method = "drawSelectionBox", at = @At("HEAD"), require = 1)
    private void stellurgyTest$outlined(EntityPlayer player, RayTraceResult hit, int execute, float partialTicks,
                                        CallbackInfo ci) {
        if (player == null || player.world == null || hit == null
                || hit.typeOfHit != RayTraceResult.Type.BLOCK || hit.getBlockPos() == null) {
            return;
        }
        TestTrace.instrument(player.world, INSTRUMENT);
        long tick = player.world.getTotalWorldTime();
        if (tick == stellurgyTest$lastOutlineTick) {
            return;
        }
        stellurgyTest$lastOutlineTick = tick;
        TestTrace.record(player.world, "client_block_outlined", "\"outlined\":true"
                + ",\"x\":" + hit.getBlockPos().getX() + ",\"y\":" + hit.getBlockPos().getY()
                + ",\"z\":" + hit.getBlockPos().getZ());
    }
}
