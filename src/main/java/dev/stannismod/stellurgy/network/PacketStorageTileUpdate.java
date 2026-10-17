package dev.stannismod.stellurgy.network;

import dev.stannismod.stellurgy.entity.EntityRocket;
import dev.stannismod.stellurgy.libvulpes.interfaces.INetworkEntity;
import dev.stannismod.stellurgy.libvulpes.network.BasePacket;
import dev.stannismod.stellurgy.util.StorageChunk;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;

public class PacketStorageTileUpdate extends BasePacket {


    EntityRocket entity;
    StorageChunk chunk;
    int entityId;
    int x, y, z;
    TileEntity tile;
    NBTTagCompound nbt;

    public PacketStorageTileUpdate() {

    }

    public PacketStorageTileUpdate(Entity entity, StorageChunk chunk, TileEntity tile) {
        this.entity = (EntityRocket) entity;
        this.chunk = chunk;
        this.tile = tile;
        x = tile.getPos().getX();
        y = tile.getPos().getY();
        z = tile.getPos().getZ();
    }

    @Override
    public void write(ByteBuf out) {
        NBTTagCompound nbt = ReflectionHelper.getPrivateValue(SPacketUpdateTileEntity.class, tile.getUpdatePacket(), "field_148860_e");


        out.writeInt(entity.world.provider.getDimension());
        out.writeInt(entity.getEntityId());
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(z);

        PacketBuffer packetBuffer = new PacketBuffer(out);
        packetBuffer.writeCompoundTag(nbt);
    }

    @Override
    public void readClient(ByteBuf in) {
        // The sender's dimension; the rocket is looked up in the world the client is in.
        in.readInt();
        entityId = in.readInt();
        x = in.readInt();
        y = in.readInt();
        z = in.readInt();
        try {
            nbt = new PacketBuffer(in).readCompoundTag();
        } catch (IOException e) {
            nbt = null;
            e.printStackTrace();
        }
    }

    @Override
    public void read(ByteBuf in) {

    }

    @SideOnly(Side.CLIENT)
    @Override
    public void executeClient(EntityPlayer thePlayer) {
        World world = thePlayer.world;
        Entity ent = world.getEntityByID(entityId);
        if (!(ent instanceof INetworkEntity)) {
            return;
        }
        StorageChunk storage = ((EntityRocket) ent).storage;
        //Make sure the chunk is initialized before using it
        if (storage != null) {
            TileEntity tile = storage.getTileEntity(new BlockPos(x, y, z));
            tile.onDataPacket(Minecraft.getMinecraft().getConnection().getNetworkManager(), new SPacketUpdateTileEntity(new BlockPos(x, y, z), 0, nbt));
        }
    }

    @Override
    public void executeServer(EntityPlayerMP player) {

    }

}
