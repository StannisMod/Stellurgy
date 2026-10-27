package dev.stannismod.stellurgy.network;

import dev.stannismod.stellurgy.client.ClientWorldDrawings;
import dev.stannismod.stellurgy.libvulpes.network.BasePacket;
import dev.stannismod.stellurgy.probe.ProbeFall;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;

/**
 * Server&rarr;client: the first probe is coming down in this world. Carries the fall's INPUTS — where it
 * lands, along which heading, when it entered, and the body and air it falls through — never its samples:
 * the client computes the same trajectory from the same numbers, so one packet serves the whole fall and a
 * player who arrives mid-fall is sent the same packet and joins it where it is.
 */
public class PacketProbeFall extends BasePacket {

    private int dimension;
    private long entryTick;
    private long landing;
    private double heading;
    private double radius;
    private double gravity;
    private double atmosphere;
    private double line;

    public PacketProbeFall() {
    }

    public static PacketProbeFall of(ProbeFall.Fall fall) {
        PacketProbeFall packet = new PacketProbeFall();
        packet.dimension = fall.dimension;
        packet.entryTick = fall.entryTick;
        packet.landing = fall.landing.toLong();
        packet.heading = fall.headingRad;
        packet.radius = fall.bodyRadiusM;
        packet.gravity = fall.surfaceGravityMS2;
        packet.atmosphere = fall.atmospherePercent;
        packet.line = fall.orbitLineAltitudeM;
        return packet;
    }

    @Override
    public void write(ByteBuf out) {
        out.writeInt(dimension);
        out.writeLong(entryTick);
        out.writeLong(landing);
        out.writeDouble(heading);
        out.writeDouble(radius);
        out.writeDouble(gravity);
        out.writeDouble(atmosphere);
        out.writeDouble(line);
    }

    @Override
    public void readClient(ByteBuf in) {
        dimension = in.readInt();
        entryTick = in.readLong();
        landing = in.readLong();
        heading = in.readDouble();
        radius = in.readDouble();
        gravity = in.readDouble();
        atmosphere = in.readDouble();
        line = in.readDouble();
    }

    @Override
    public void read(ByteBuf in) {
        // never sent to the server
    }

    @Override
    public void executeClient(EntityPlayer player) {
        // Sent for the world the player was in; a change of world since makes it news about another.
        if (player.world.provider.getDimension() != dimension) {
            return;
        }
        ProbeFall.Fall fall = new ProbeFall.Fall(dimension, entryTick, BlockPos.fromLong(landing), heading, radius, gravity,
                atmosphere, line, "", new net.minecraft.nbt.NBTTagCompound());
        ClientWorldDrawings.apply(drawings -> drawings.probeFall().told(fall));
    }

    @Override
    public void executeServer(EntityPlayerMP player) {
    }
}
