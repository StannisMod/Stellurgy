package dev.stannismod.stellurgy.network;

import dev.stannismod.stellurgy.client.ClientWorldDrawings;
import dev.stannismod.stellurgy.libvulpes.network.BasePacket;
import dev.stannismod.stellurgy.probe.TransmitterRegistry;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Server&rarr;client: every transmitter in the world the player is in, whole — sent on login, on a change of
 * world and whenever one starts or stops. The list is what a radio there hears, and a state rather than an
 * event, so the newest packet replaces what the client held.
 */
public class PacketTransmitters extends BasePacket {

    /** A wire bound on one world's transmitters: far above anything a world places. */
    private static final int MAX_TRANSMITTERS = 4096;

    private int dimension;
    private final List<BlockPos> positions = new ArrayList<>();

    public PacketTransmitters() {
    }

    public static PacketTransmitters of(World world) {
        PacketTransmitters packet = new PacketTransmitters();
        packet.dimension = world.provider.getDimension();
        for (BlockPos pos : TransmitterRegistry.get(world).positions()) {
            if (packet.positions.size() >= MAX_TRANSMITTERS) {
                break;
            }
            packet.positions.add(pos);
        }
        return packet;
    }

    @Override
    public void write(ByteBuf out) {
        out.writeInt(dimension);
        out.writeShort(positions.size());
        for (BlockPos pos : positions) {
            out.writeLong(pos.toLong());
        }
    }

    @Override
    public void readClient(ByteBuf in) {
        dimension = in.readInt();
        int count = Math.min(MAX_TRANSMITTERS, in.readUnsignedShort());
        positions.clear();
        for (int i = 0; i < count; i++) {
            positions.add(BlockPos.fromLong(in.readLong()));
        }
    }

    @Override
    public void read(ByteBuf in) {
        // never sent to the server
    }

    @Override
    public void executeClient(EntityPlayer player) {
        // Sent for the world the player was in; a change of world since makes it news about another.
        if (player.world.provider.getDimension() == dimension) {
            ClientWorldDrawings.apply(drawings -> drawings.transmitters().told(positions));
        }
    }

    @Override
    public void executeServer(EntityPlayerMP player) {
    }
}
