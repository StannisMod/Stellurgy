package dev.stannismod.stellurgy.test.trace;

import com.github.stannismod.forge.testing.client.bridge.ForgeTestClientBootstrap;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;

/**
 * {@code client_ship_pickable}: this client can now aim at the ship — its crosshair raytrace can strike
 * one of the ship's blocks.
 *
 * <p>The client's raytrace ({@code MixinWorld#rayTraceBlocksIgnoreShip}, Valkyrien Skies, vendored)
 * traces into every ship of the client's ship manager whose box the ray crosses, and reads the ship's
 * blocks out of the client WORLD at their subspace address. So a ship is pickable when both halves hold:
 * the manager has LOADED it, and every chunk the ship claims holds the data the server sent. Either half
 * may complete last — the ship index can reach the client before or after its chunks — so the record is
 * written at both changes that can complete the pair: the manager's load of the ship
 * ({@code MixinWorldClientShipManagerPickable}) and the read of a claimed chunk's packet
 * ({@code MixinNetHandlerChunkDataPickable}). It is written each time one of them leaves the pair whole,
 * so a re-sent chunk writes it again; a waiter takes the first naming its ship after its mark.</p>
 *
 * <p>Payload: {@code ship} the ship's id, {@code via} which change completed the pair
 * ({@code ship_loaded} / {@code chunk_data}), {@code chunks} how many claimed chunks were checked.</p>
 *
 * <p>Not decided here: whether the ray of a given look meets the ship — that is the geometry of the
 * stand, which the waiting test owns. Client-only, game thread only (the manager refuses any other).</p>
 */
public final class ClientShipPickable {

    public static final String RECORD = "client_ship_pickable";

    private ClientShipPickable() {
    }

    /** Records {@link #RECORD} for {@code ship} when every chunk it claims is loaded in {@code world}. */
    public static void recordIfWhole(World world, PhysicsObject ship, String via) {
        ShipData data = ship.getShipData();
        int chunks = 0;
        for (ChunkPos pos : data.getChunkClaim()) {
            if (world.getChunkProvider().getLoadedChunk(pos.x, pos.z) == null) {
                return;
            }
            chunks++;
        }
        if (chunks == 0) {
            return;
        }
        ForgeTestClientBootstrap.recordEvent(RECORD, "\"ship\":\"" + data.getUuid() + "\",\"via\":\"" + via
                + "\",\"chunks\":" + chunks);
    }
}
