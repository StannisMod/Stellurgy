package org.valkyrienskies.mod.common.network;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.AxisAlignedBB;
import org.joml.Vector3dc;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.ships.ship_transform.ShipTransform;
import org.valkyrienskies.mod.common.util.jackson.VSJacksonUtil;

import java.io.IOException;
import java.util.*;

public class ShipTransformUpdateMessage implements IMessage {

    /**
     * One craft's state as of the tick this packet was built: where it is, how far it reaches, and
     * <b>how it is moving</b> — linear and angular velocity, in world axes, blocks and radians per
     * second.
     *
     * <p><b>Why the motion travels with the pose rather than in a packet of its own.</b> A velocity
     * is applied AT a point, and the point is expressed in the pose; the two describe one instant
     * and a consumer that pairs a fresh velocity with a stale pose is computing a movement the craft
     * never made. Sending them together is what makes them one statement.</p>
     *
     * <p><b>Why they are sent at all.</b> Player movement is client-authoritative, so a client that
     * must place a body standing on a moving deck needs the deck's motion — and until this carried
     * it, nothing did: the ship index packet updates transform, inertia and the physics flag but
     * never {@code ShipPhysicsData}, so a client's copy of a craft's velocity stayed at the zero it
     * was constructed with. The client then had to RECONSTRUCT the motion by differencing what it
     * could see, which is a guess dressed as a measurement, and one such guess put a body a
     * kilometre into the sky.</p>
     */
    public static final class ShipPoseAndMotion {
        public final ShipTransform transform;
        public final AxisAlignedBB aabb;
        /** World-frame, blocks per second. */
        public final double linearX, linearY, linearZ;
        /** World-frame, radians per second. */
        public final double angularX, angularY, angularZ;

        ShipPoseAndMotion(final ShipTransform transform, final AxisAlignedBB aabb,
                          final double linearX, final double linearY, final double linearZ,
                          final double angularX, final double angularY, final double angularZ) {
            this.transform = transform;
            this.aabb = aabb;
            this.linearX = linearX;
            this.linearY = linearY;
            this.linearZ = linearZ;
            this.angularX = angularX;
            this.angularY = angularY;
            this.angularZ = angularZ;
        }
    }

    private static final ObjectMapper serializer = VSJacksonUtil.getPacketMapper();
    final Map<UUID, ShipPoseAndMotion> shipTransforms;
    int dimensionID;

    public ShipTransformUpdateMessage() {
        this.shipTransforms = new HashMap<>();
        this.dimensionID = -1;
    }

    public void addData(final UUID shipUUID, final ShipTransform shipTransform, final AxisAlignedBB alignedBB,
                        final Vector3dc linearVelocity, final Vector3dc angularVelocity) {
        // A craft whose physics data has not been built yet still has a pose worth sending; it is
        // simply not moving as far as anyone can tell, and saying "zero" is the truth about it.
        final double lx = linearVelocity == null ? 0.0 : linearVelocity.x();
        final double ly = linearVelocity == null ? 0.0 : linearVelocity.y();
        final double lz = linearVelocity == null ? 0.0 : linearVelocity.z();
        final double ax = angularVelocity == null ? 0.0 : angularVelocity.x();
        final double ay = angularVelocity == null ? 0.0 : angularVelocity.y();
        final double az = angularVelocity == null ? 0.0 : angularVelocity.z();
        shipTransforms.put(shipUUID, new ShipPoseAndMotion(shipTransform, alignedBB, lx, ly, lz, ax, ay, az));
    }

    public void setDimensionID(int dimensionID) {
        this.dimensionID = dimensionID;
    }

    public int getDimensionID() {
        return dimensionID;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        PacketBuffer packetBuffer = new PacketBuffer(buf);
        int numberOfShips = packetBuffer.readInt();
        for (int i = 0; i < numberOfShips; i++) {
            UUID shipID = null;
            ShipTransform shipTransform = null;
            AxisAlignedBB axisAlignedBB = null;
            // Read the UUID
            {
                int bytesSize = packetBuffer.readInt();
                byte[] bytes = new byte[bytesSize];
                packetBuffer.readBytes(bytes);
                try {
                    shipID = serializer.readValue(bytes, UUID.class);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            // Read the ship transform
            {
                int bytesSize = packetBuffer.readInt();
                byte[] bytes = new byte[bytesSize];
                packetBuffer.readBytes(bytes);
                try {
                    shipTransform = serializer.readValue(bytes, ShipTransform.class);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            // Read the ship aabb
            {
                int bytesSize = packetBuffer.readInt();
                byte[] bytes = new byte[bytesSize];
                packetBuffer.readBytes(bytes);
                try {
                    axisAlignedBB = serializer.readValue(bytes, AxisAlignedBB.class);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (shipID == null || shipTransform == null || axisAlignedBB == null) {
                // corrupt packet
                shipTransforms.clear();
                return;
            }
            // The craft's motion, written beside its pose by toBytes below.
            final double lx = packetBuffer.readDouble();
            final double ly = packetBuffer.readDouble();
            final double lz = packetBuffer.readDouble();
            final double ax = packetBuffer.readDouble();
            final double ay = packetBuffer.readDouble();
            final double az = packetBuffer.readDouble();
            shipTransforms.put(shipID, new ShipPoseAndMotion(shipTransform, axisAlignedBB, lx, ly, lz, ax, ay, az));
        }
        dimensionID = packetBuffer.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBuffer packetBuffer = new PacketBuffer(buf);
        packetBuffer.writeInt(shipTransforms.size());
        for (Map.Entry<UUID, ShipPoseAndMotion> data : shipTransforms.entrySet()) {
            // Write index data to the byte buffer.
            try {
                // Write the UUID
                {
                    byte[] dataBytes = serializer.writeValueAsBytes(data.getKey());
                    int bytesSize = dataBytes.length;
                    packetBuffer.writeInt(bytesSize);
                    packetBuffer.writeBytes(dataBytes);
                }
                // Write the ship transform
                {
                    byte[] dataBytes = serializer.writeValueAsBytes(data.getValue().transform);
                    int bytesSize = dataBytes.length;
                    packetBuffer.writeInt(bytesSize);
                    packetBuffer.writeBytes(dataBytes);
                }
                // Write the ship aabb
                {
                    byte[] dataBytes = serializer.writeValueAsBytes(data.getValue().aabb);
                    int bytesSize = dataBytes.length;
                    packetBuffer.writeInt(bytesSize);
                    packetBuffer.writeBytes(dataBytes);
                }
                // Write the craft's MOTION, beside the pose it belongs to. Raw doubles rather than
                // the Jackson round-trip the three above use: six numbers with no schema to carry,
                // on a packet that goes out every tick for every craft in the dimension.
                {
                    packetBuffer.writeDouble(data.getValue().linearX);
                    packetBuffer.writeDouble(data.getValue().linearY);
                    packetBuffer.writeDouble(data.getValue().linearZ);
                    packetBuffer.writeDouble(data.getValue().angularX);
                    packetBuffer.writeDouble(data.getValue().angularY);
                    packetBuffer.writeDouble(data.getValue().angularZ);
                }
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        }
        packetBuffer.writeInt(dimensionID);
    }
}
