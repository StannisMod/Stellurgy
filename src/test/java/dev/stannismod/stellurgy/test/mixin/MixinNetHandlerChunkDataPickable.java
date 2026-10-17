package dev.stannismod.stellurgy.test.mixin;

import com.github.stannismod.forge.testing.client.bridge.ForgeTestClientBootstrap;
import dev.stannismod.stellurgy.test.trace.ClientShipPickable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.SPacketChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.ships.QueryableShipData;
import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.ships.ship_world.IPhysObjectWorld;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;
import org.valkyrienskies.mod.common.util.ValkyrienUtils;

import java.util.Optional;

/**
 * The half of {@link ClientShipPickable} that a chunk packet completes: the data of a chunk a LOADED
 * ship claims has just been read into the client world.
 *
 * <p>The seam is the handler's return, after {@code Chunk#read}. Not {@code ChunkEvent.Load}: the client
 * posts that from {@code ChunkProviderClient#loadChunk} on a freshly made, still empty chunk, before the
 * packet's blocks are read into it ({@code NetHandlerPlayClient#handleChunkData}), so a record there
 * would claim blocks the client does not hold yet.</p>
 *
 * <p>Only the game-thread pass reads the chunk; the netty-thread call is re-queued by
 * {@code PacketThreadUtil} and leaves by its exception. Observation. Client. Test source set.</p>
 */
@Mixin(NetHandlerPlayClient.class)
public abstract class MixinNetHandlerChunkDataPickable {

    @Shadow
    private WorldClient clientWorldController;

    @Inject(method = "handleChunkData", at = @At("RETURN"), require = 1)
    private void stellurgyTest$pickableChunkRead(SPacketChunkData packet, CallbackInfo ci) {
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread() || clientWorldController == null) {
            return;
        }
        ForgeTestClientBootstrap.noteInstrumentEntered(ClientShipPickable.RECORD);
        Optional<ShipData> claimant = QueryableShipData.get(clientWorldController)
                .getShipFromChunk(packet.getChunkX(), packet.getChunkZ());
        if (!claimant.isPresent()) {
            return;
        }
        IPhysObjectWorld manager = ValkyrienUtils.getPhysObjWorld(clientWorldController);
        PhysicsObject ship = manager == null ? null : manager.getPhysObjectFromUUID(claimant.get().getUuid());
        if (ship != null) {
            ClientShipPickable.recordIfWhole(clientWorldController, ship, "chunk_data");
        }
    }
}
