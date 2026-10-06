package dev.stannismod.stellurgy.libvulpes.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.interfaces.INetworkEntity;

import java.io.IOException;

/**
 * A packet addressed to an {@link INetworkEntity}: a dimension, an entity id, a packet id, an optional
 * compound, and whatever bytes that entity writes for that id.
 *
 * <p>Decoding reads BYTES; the game thread resolves the ENTITY — the same split as
 * {@link PacketMachine}, for the same reason: Netty decodes on its own IO thread, a world looked up
 * there is looked up off the game thread, and an exception thrown by a decoder (a dimension that is
 * not loaded) is fatal to the sender's connection.</p>
 */
public class PacketEntity extends BasePacket {

	INetworkEntity entity;

	NBTTagCompound nbt;
	byte packetId;

	/** Where the entity is. Read off the wire; resolved only on the game thread. */
	private int dimId;
	private int entityId;
	/** The entity's own bytes, copied out of the decoder's buffer and read back on the game thread. */
	private byte[] payload;

	public PacketEntity() {
		nbt = new NBTTagCompound();
	}

	public PacketEntity(INetworkEntity machine, byte packetId) {
		this();
		this.entity = machine;
		this.packetId = packetId;
	}


	public PacketEntity(INetworkEntity entity, byte packetId, NBTTagCompound nbt) {
		this(entity, packetId);
		this.nbt = nbt;
	}

	@Override
	public void write(ByteBuf out) {
		PacketBuffer buffer = new PacketBuffer(out);

		write(buffer);
	}

	private void write(PacketBuffer out) {
		out.writeInt(((Entity)entity).world.provider.getDimension());
		out.writeInt(((Entity)entity).getEntityId());
		out.writeByte(packetId);

		out.writeBoolean(!nbt.hasNoTags());

		if(!nbt.hasNoTags()) {
			out.writeCompoundTag(nbt);
		}

		entity.writeDataToNetwork(out, packetId);
	}

	@Override
	public void read(ByteBuf in) {
		readAddressAndPayload(new PacketBuffer(in));
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void readClient(ByteBuf in) {
		readAddressAndPayload(new PacketBuffer(in));
	}

	/** The whole of decoding: an address, the optional compound, and the bytes behind them. */
	private void readAddressAndPayload(PacketBuffer in) {
		dimId = in.readInt();
		entityId = in.readInt();
		packetId = in.readByte();

		if(in.readBoolean()) {
			NBTTagCompound read = null;

			try {
				read = in.readCompoundTag();
			} catch (IOException e) {
				e.printStackTrace();
			}

			this.nbt = read;
		}

		payload = new byte[in.readableBytes()];
		in.readBytes(payload);
	}

	/**
	 * Find the addressed entity in {@code world}. Game thread only.
	 *
	 * @return the entity, or {@code null} when the world is not loaded or the id names no
	 *         {@link INetworkEntity} in it
	 */
	private INetworkEntity resolve(World world) {
		if (world == null || payload == null) {
			return null;
		}
		Entity ent = world.getEntityByID(entityId);
		return ent instanceof INetworkEntity ? (INetworkEntity) ent : null;
	}

	/**
	 * A client's use of the entity. The dimension and the id are the client's to write, so the entity
	 * is asked whether this player may use it before a byte of the payload is read, and a refusal is
	 * logged.
	 */
	@Override
	public void executeServer(EntityPlayerMP player) {
		entity = resolve(DimensionManager.getWorld(dimId));
		if (entity == null) {
			return;
		}
		if (!entity.canBeUsedBy(player)) {
			LibVulpes.logger.warn("Refused entity packet {} for {} #{} (dim {}) from {}: the player may not"
							+ " use it", packetId, entity.getClass().getSimpleName(), entityId, dimId,
					player == null ? "nobody" : player.getName());
			return;
		}
		entity.readDataFromNetwork(new PacketBuffer(Unpooled.wrappedBuffer(payload)), packetId, nbt);
		entity.useNetworkData(player, Side.SERVER, packetId, nbt);
	}

	@Override
	public void executeClient(EntityPlayer player) {
		// The receiving player's own world, as in PacketMachine: this method is not @SideOnly.
		entity = resolve(player == null ? null : player.world);
		if (entity != null) {
			entity.readDataFromNetwork(new PacketBuffer(Unpooled.wrappedBuffer(payload)), packetId, nbt);
			entity.useNetworkData(player, Side.CLIENT, packetId, nbt);
		}
	}

}
