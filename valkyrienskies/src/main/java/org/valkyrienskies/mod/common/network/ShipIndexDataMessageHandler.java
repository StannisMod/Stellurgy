package org.valkyrienskies.mod.common.network;

import net.minecraft.client.Minecraft;
import net.minecraft.util.IThreadListener;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import org.valkyrienskies.mod.common.ships.QueryableShipData;
import org.valkyrienskies.mod.common.ships.ship_world.IPhysObjectWorld;
import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.util.ValkyrienUtils;

import java.util.UUID;

public class ShipIndexDataMessageHandler implements IMessageHandler<ShipIndexDataMessage, IMessage> {

    @Override
    @SuppressWarnings("Convert2Lambda")
    // Why do you not use a lambda? Because lambdas are compiled and this causes NoClassDefFound
    // errors. DON'T USE A LAMBDA
    public IMessage onMessage(ShipIndexDataMessage message, MessageContext ctx) {
        IThreadListener mainThread = Minecraft.getMinecraft();
        mainThread.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                World world = Minecraft.getMinecraft().world;
                if (world == null || world.provider.getDimension() != message.dimensionID) {
                    // The index is of a world this client is not in. The server sends one to a
                    // player who has just LEFT a world, unloading that world's ships for him, and a
                    // ship keeps its uuid when it crosses between worlds - so applying it here can
                    // unload the very ship that arrived with him.
                    return;
                }
                IPhysObjectWorld physObjectWorld = ValkyrienUtils.getPhysObjWorld(world);
                QueryableShipData worldData = QueryableShipData.get(world);
                for (ShipData shipData : message.indexedData) {
                    // The server holds one ship per claim, but tells this client only of ships it
                    // indexes, never of ships it forgets — so a record of a ship that no longer
                    // exists stays here. Once the server hands that claim to a new ship, both records
                    // name the same chunks and every lookup by position fails. The record arriving
                    // for the claim is the live one; any other holding it is gone.
                    ChunkPos centre = shipData.getChunkClaim().getCenterPos();
                    java.util.Optional<ShipData> holder = worldData.getShipFromChunk(centre.x, centre.z);
                    if (holder.isPresent() && !holder.get().getUuid().equals(shipData.getUuid())) {
                        physObjectWorld.queueShipUnload(holder.get().getUuid());
                        worldData.removeShip(holder.get());
                    }
                    worldData.addOrUpdateShipPreservingPhysObj(shipData, world);
                }
                for (UUID loadID : message.shipsToLoad) {
                    physObjectWorld.queueShipLoad(loadID);
                }
                for (UUID unloadID : message.shipsToUnload) {
                    physObjectWorld.queueShipUnload(unloadID);
                }
            }
        });

        return null;
    }
}
