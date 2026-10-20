package dev.stannismod.stellurgy.test.client;

import com.github.stannismod.forge.testing.client.ClientBot;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.ChunkPos;
import org.valkyrienskies.mod.common.ships.chunk_claims.VSChunkClaim;

import java.io.IOException;

/**
 * How many tiles of one shipyard claim the CLIENT's world renderer holds in its global tile set — the set
 * {@code RenderGlobal#renderEntities} draws every frame, into which a ship's render chunks put their tiles.
 *
 * <p>The claim is the one centred on the given chunk, asked through Valkyrien Skies' own
 * {@link VSChunkClaim#containsChunk}, so the claim's extent is the substrate's and not a copy here. Read on
 * the client thread.</p>
 *
 * <p>Test source set: absent from a released jar.</p>
 */
public final class ClientShipRenderTiles {

    private ClientShipRenderTiles() {
    }

    /** The number of tiles in the claim centred on chunk {@code (centreX, centreZ)} that {@code bot}'s client draws. */
    public static int inClaim(ClientBot bot, int centreX, int centreZ) throws IOException {
        return Integer.parseInt(bot.invokeStaticInt(ClientShipRenderTiles.class.getName(), "inClaimOnClient",
                centreX, centreZ).get("returned").getAsString());
    }

    /** Runs in the client JVM, on its client thread — reached only through {@link #inClaim}. */
    static int inClaimOnClient(int centreX, int centreZ) {
        VSChunkClaim claim = new VSChunkClaim(new ChunkPos(centreX, centreZ));
        int held = 0;
        synchronized (Minecraft.getMinecraft().renderGlobal.setTileEntities) {
            for (TileEntity tile : Minecraft.getMinecraft().renderGlobal.setTileEntities) {
                if (claim.containsChunk(tile.getPos().getX() >> 4, tile.getPos().getZ() >> 4)) {
                    held++;
                }
            }
        }
        return held;
    }
}
