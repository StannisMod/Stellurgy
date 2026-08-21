package com.github.stannismod.forge.testing.mixin;

import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.SPacketChunkData;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.github.stannismod.forge.testing.client.bridge.ForgeTestClientBootstrap;

/**
 * Records the moment the client actually HAS a chunk's blocks.
 *
 * <h2>Why this is a mixin and not a Forge event</h2>
 *
 * <p>{@code ChunkEvent.Load} is the obvious candidate and it is WRONG here.
 * {@code ChunkProviderClient.loadChunk} constructs an EMPTY chunk, puts it in the map, posts that
 * event, and only afterwards does {@code handleChunkData} call {@code chunk.read(…)} to fill it. A
 * recorder on the event would therefore report "chunk received" about a chunk containing nothing —
 * a witness that is confidently wrong, which is worse than no witness.</p>
 *
 * <p>There is no event after the fill. The TAIL of {@code handleChunkData} is the first instant at
 * which "the client can see these blocks" is true, so that is where this injects.</p>
 *
 * <h2>Why a mixin against code we compile is allowed HERE</h2>
 *
 * <p>The distinction is DIRECTION. A production mixin patches a tree into itself, which is
 * indirection where an edit would do. This one reaches from the HARNESS into the product and ships
 * with the harness: it lives in the test source set, so it is absent from a released jar entirely
 * and a shipped game pays nothing for it. That is the whole point — an observation a test wants must
 * cost production zero, and a log line written into production costs it forever.</p>
 *
 * <p>Registered from {@code ForgeTestClientBootstrap}, which itself runs only under
 * {@code -Dforge.test.client=true}.</p>
 */
@Mixin(NetHandlerPlayClient.class)
public class MixinNetHandlerPlayClient {

    @Inject(method = "handleChunkData", at = @At("TAIL"))
    private void forgeTest$recordChunkApplied(SPacketChunkData packet, CallbackInfo ci) {
        ForgeTestClientBootstrap.recordChunkApplied(
                packet.getChunkX(), packet.getChunkZ(), packet.isFullChunk());
    }
}
