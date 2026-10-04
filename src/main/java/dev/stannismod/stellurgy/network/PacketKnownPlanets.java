package dev.stannismod.stellurgy.network;

import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.libvulpes.network.BasePacket;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;

import java.util.HashSet;
import java.util.Set;

/**
 * The planets every player of this save knows, told to a joining client — the floor a rocket's
 * planet list stands on when planets must be discovered. Sent at every login, a local one too: the
 * client keeps its own copy of the galaxy and has no other way to learn it.
 */
public class PacketKnownPlanets extends BasePacket {

    private Set<Integer> knownPlanets;

    public PacketKnownPlanets() {
    }

    public PacketKnownPlanets(Set<Integer> knownPlanets) {
        this.knownPlanets = new HashSet<>(knownPlanets);
    }

    @Override
    public void write(ByteBuf out) {
        PacketBuffer buffer = new PacketBuffer(out);
        buffer.writeInt(knownPlanets.size());
        for (Integer planetId : knownPlanets) {
            buffer.writeInt(planetId);
        }
    }

    @Override
    public void readClient(ByteBuf in) {
        PacketBuffer buffer = new PacketBuffer(in);
        int size = buffer.readInt();
        knownPlanets = new HashSet<>();
        for (int i = 0; i < size; i++) {
            knownPlanets.add(buffer.readInt());
        }
    }

    @Override
    public void read(ByteBuf in) {
    }

    @Override
    public void executeClient(EntityPlayer player) {
        DimensionManager.getInstance().knownPlanets.addAll(knownPlanets);
    }

    @Override
    public void executeServer(EntityPlayerMP player) {
    }
}
