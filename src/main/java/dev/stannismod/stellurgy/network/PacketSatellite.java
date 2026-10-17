package dev.stannismod.stellurgy.network;

import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.SatelliteRegistry;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.libvulpes.network.BasePacket;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;

public class PacketSatellite extends BasePacket {

    SatelliteBase machine;

    NBTTagCompound nbt;

    byte packetId;

    public PacketSatellite() {
        nbt = new NBTTagCompound();
    }

    public PacketSatellite(SatelliteBase machine) {
        this();
        this.machine = machine;
    }


    @Override
    public void write(ByteBuf outline) {
        PacketBuffer packetBuffer = new PacketBuffer(outline);
        NBTTagCompound nbt = new NBTTagCompound();
        machine.writeToNBT(nbt);

        packetBuffer.writeCompoundTag(nbt);
    }

    @Override
    public void readClient(ByteBuf in) {
        try {
            nbt = new PacketBuffer(in).readCompoundTag();
        } catch (IOException e) {
            nbt = null;
            Stellurgy.logger.warn("Unreadable satellite packet", e);
        }
    }

    @Override
    public void read(ByteBuf in) {
        //Should never happen

    }

    @Override
    @SideOnly(Side.CLIENT)
    public void executeClient(EntityPlayer player) {
        if (nbt == null) {
            return;
        }
        SatelliteBase satellite = SatelliteRegistry.createFromNBT(nbt);
        // A satellite type this client's mod set does not have: createFromNBT reports it and answers null.
        if (satellite == null) {
            return;
        }
        DimensionProperties prop = DimensionManager.getInstance().getDimensionProperties(satellite.getDimensionId());
        if (prop == null) {
            Stellurgy.logger.warn("Received satellite {} for unknown dim {}", satellite.getId(),
                    satellite.getDimensionId());
            return;
        }
        prop.addSatellite(satellite);
    }

    public void executeServer(EntityPlayerMP player) {
    }

    public void execute(EntityPlayer player, Side side) {
    }

}
