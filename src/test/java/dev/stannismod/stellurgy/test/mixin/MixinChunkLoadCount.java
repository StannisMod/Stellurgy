package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.test.trace.SideTrace;

/**
 * Counts chunk arrivals on both sides.
 *
 * <h2>Why it had to be a mixin rather than a gated subscriber</h2>
 *
 * <p>This replaces a {@code ChunkEvent.Load} handler Stellurgy registered on EVERY launch, gated by
 * nothing. It could not be gated: the counter's client half is what a smoothness test needs, and a
 * harness client JVM has no test mode to gate a subscriber behind. A test mixin has no such problem
 * — it exists only where the harness coremod queued it, on either side.</p>
 *
 * <p>{@code Chunk.onLoad} is where the event is posted from, so this is the same instant by
 * construction rather than by argument.</p>
 */
@Mixin(Chunk.class)
public abstract class MixinChunkLoadCount {

    @Inject(method = "onLoad", at = @At("HEAD"))
    private void stellurgyTest$chunkLoaded(CallbackInfo ci) {
        Chunk self = (Chunk) (Object) this;
        (self.getWorld() == null ? SideTrace.here() : SideTrace.of(self.getWorld()))
                .motion().chunkLoaded();
    }
}
