package org.valkyrienskies.mod.common.network;

import net.minecraft.client.Minecraft;
import net.minecraft.util.IThreadListener;
import net.minecraft.util.math.AxisAlignedBB;
import org.joml.Vector3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import org.valkyrienskies.mod.common.ships.QueryableShipData;
import org.valkyrienskies.mod.common.ships.physics_data.ShipPhysicsData;
import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.ships.interpolation.ITransformInterpolator;
import org.valkyrienskies.mod.common.ships.ship_transform.ShipTransform;
import org.valkyrienskies.mod.common.ships.ship_world.IPhysObjectWorld;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;
import org.valkyrienskies.mod.common.util.ValkyrienUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class ShipTransformUpdateMessageHandler implements IMessageHandler<ShipTransformUpdateMessage, IMessage> {

    @Override
    @SuppressWarnings("Convert2Lambda")
    // Why do you not use a lambda? Because lambdas are compiled and this causes NoClassDefFound
    // errors. DON'T USE A LAMBDA
    public IMessage onMessage(final ShipTransformUpdateMessage message, final MessageContext ctx) {
        IThreadListener mainThread = Minecraft.getMinecraft();
        mainThread.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                World world = Minecraft.getMinecraft().world;
                if (world == null || world.provider.getDimension() != message.getDimensionID()) {
                    // The poses in this packet belong to a world this client is no longer in. It is
                    // addressed to a dimension and carries no other way to tell, and a ship keeps its
                    // identity when it crosses between worlds - so applying it against whatever world
                    // the client is in now can put the SOURCE world's pose on the ship that just
                    // arrived here. The window is a dimension change with a packet still in flight.
                    return;
                }
                IPhysObjectWorld physObjectWorld = ValkyrienUtils.getPhysObjWorld(world);
                QueryableShipData worldData = QueryableShipData.get(world);

                for (Map.Entry<UUID, ShipTransformUpdateMessage.ShipPoseAndMotion> transformUpdate : message.shipTransforms.entrySet()) {
                    final UUID shipID = transformUpdate.getKey();
                    final ShipTransformUpdateMessage.ShipPoseAndMotion update = transformUpdate.getValue();
                    final ShipTransform shipTransform = update.transform;
                    final AxisAlignedBB shipBB = update.aabb;

                    final PhysicsObject physicsObject = ValkyrienUtils.getPhysObjWorld(world).getPhysObjectFromUUID(shipID);
                    if (physicsObject != null) {
                        // Do not update the transform in ShipData, that will be done by PhysicsObject.tick()
                        ITransformInterpolator interpolator = physicsObject.getTransformInterpolator();
                        interpolator.onNewTransformPacket(shipTransform, shipBB,
                                update.linearX, update.linearY, update.linearZ,
                                update.angularX, update.angularY, update.angularZ);
                        // The craft's DECLARED motion, stored where every consumer already looks for
                        // it — the same ShipPhysicsData the server fills from its physics step. This
                        // is the whole point of carrying it: a client asking "how fast is this deck
                        // moving at this point" now gets an answer it was TOLD, by the same
                        // expression the server evaluates, instead of one it reconstructed by
                        // differencing what it happened to observe.
                        final ShipPhysicsData physicsData = physicsObject.getShipData().getPhysicsData();
                        if (physicsData != null) {
                            physicsData.setLinearVelocity(new Vector3d(update.linearX, update.linearY, update.linearZ));
                            physicsData.setAngularVelocity(new Vector3d(update.angularX, update.angularY, update.angularZ));
                        }
                    }
                }
            }
        });

        return null;
    }
}
