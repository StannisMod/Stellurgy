package dev.stannismod.stellurgy.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import zmaster587.libVulpes.network.BasePacket;

import java.util.LinkedList;

public class PacketConfigSync extends BasePacket {
    StellurgyConfiguration config;
    int spaceDimId;
    int stationSize;
    boolean planetsMustBeDiscovered;
    LinkedList<Integer> knownPlanets;

    public PacketConfigSync(StellurgyConfiguration config) {
        this.config = config;
    }


    public PacketConfigSync() {
        this.config = new StellurgyConfiguration(StellurgyConfiguration.getCurrentConfig());
    }

    @Override
    public void write(ByteBuf out) {
        config.writeConfigToNetwork(new PacketBuffer(out));
    }

    @Override
    public void readClient(ByteBuf in) {
        config = config.readConfigFromNetwork(new PacketBuffer(in));
    }

    @Override
    public void read(ByteBuf in) {
        //nice try
    }

    @Override
    public void executeClient(EntityPlayer thePlayer) {
        try {
            StellurgyConfiguration.loadConfigFromServer(config);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void executeServer(EntityPlayerMP player) {
    }

}
