package dev.stannismod.stellurgy.test.mixin;

import dev.stannismod.stellurgy.test.trace.TestTrace;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.SPacketBlockChange;
import net.minecraft.network.play.server.SPacketMultiBlockChange;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A block the server changed, as the CLIENT applied it: {@code client_block_changed}.
 *
 * <p>The TAIL of {@code NetHandlerPlayClient#handleBlockChange} and {@code #handleMultiBlockChange}, the
 * line after the client's world took the new state ({@code invalidateRegionAndSetBlock}). Both handlers
 * open with {@code PacketThreadUtil.checkThreadAndEnqueue}, which leaves the netty thread by THROWING, so
 * the tail is reached on the client's main thread only, once the state is in its world. One record per
 * position, carrying {@code x/y/z} and the {@code block} registry name the packet set there. Client log.
 * Read by {@code HelmControlsClientGroupTest}.</p>
 *
 * <p>The first instant a block the server placed is in the client's world, so anything the client derives
 * from its world (the crosshair's pick, which vanilla re-derives each tick) can be asked about it from the
 * NEXT client tick on. Silent about a block that arrived inside a whole chunk ({@code chunk_data_applied}
 * is that record) and about a change the client made itself.</p>
 */
@Mixin(NetHandlerPlayClient.class)
public abstract class MixinClientBlockChangeEvents {

    private static final String INSTRUMENT = "client_block_changes";

    @Inject(method = "handleBlockChange", at = @At("TAIL"), require = 1)
    private void stellurgyTest$blockChanged(SPacketBlockChange packet, CallbackInfo ci) {
        stellurgyTest$record(packet.getBlockPosition(), packet.getBlockState());
    }

    @Inject(method = "handleMultiBlockChange", at = @At("TAIL"), require = 1)
    private void stellurgyTest$blocksChanged(SPacketMultiBlockChange packet, CallbackInfo ci) {
        for (SPacketMultiBlockChange.BlockUpdateData changed : packet.getChangedBlocks()) {
            stellurgyTest$record(changed.getPos(), changed.getBlockState());
        }
    }

    @Unique
    private static void stellurgyTest$record(BlockPos pos, IBlockState state) {
        WorldClient world = Minecraft.getMinecraft().world;
        if (world == null || pos == null) {
            return;
        }
        TestTrace.instrument(world, INSTRUMENT);
        String block = state == null || state.getBlock().getRegistryName() == null
                ? "none" : state.getBlock().getRegistryName().toString();
        TestTrace.record(world, "client_block_changed", "\"x\":" + pos.getX() + ",\"y\":" + pos.getY()
                + ",\"z\":" + pos.getZ() + ",\"block\":\"" + TestTrace.json(block) + "\"");
    }
}
