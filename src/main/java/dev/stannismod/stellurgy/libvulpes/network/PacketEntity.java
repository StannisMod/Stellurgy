package dev.stannismod.stellurgy.libvulpes.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import dev.stannismod.stellurgy.libvulpes.interfaces.INetworkEntity;

import java.io.IOException;

/**
 * A packet addressed to an {@link INetworkEntity}: a dimension, an entity id, a packet id, an optional
 * compound, and whatever bytes that entity writes for that id.
 *
 * <p>Decoding reads bytes and nothing else, on either side; the entity is looked up, and handed its
 * bytes, on the game thread. An entity's reader may build world-shaped state (a rocket's reader builds
 * a whole storage chunk and lights it), and that is not work for Netty's thread.</p>
 *
 * <p><b>From a client, the address is a claim.</b> The server applies it only when the entity is in
 * the sender's own world and the server is showing it to him ({@link PacketSenderCheck#seesEntity}).
 * Which packet ids a client may send at all, and who may send each, is the entity's own rule. Bytes
 * that do not decode are a dropped packet, logged.</p>
 */
public class PacketEntity extends BasePacket {

	INetworkEntity entity;

	NBTTagCompound nbt;
	int entityId;
	byte packetId;

	/** The world the sender named. Read off the wire; compared and resolved only on the game thread. */
	private int dimId;
	/**
	 * The entity's own bytes, copied out of the decoder's buffer; {@code null} when the packet did not
	 * decode, and {@link #malformed} then says why.
	 */
	private byte[] payload;
	private String malformed;

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
		decode(new PacketBuffer(in));
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void readClient(ByteBuf in) {
		decode(new PacketBuffer(in));
	}

	/** Header, compound and payload bytes. Touches no world. */
	private void decode(PacketBuffer in) {
		try {
			dimId = in.readInt();
			entityId = in.readInt();
			packetId = in.readByte();
			if (in.readBoolean()) {
				NBTTagCompound read = in.readCompoundTag();
				if (read != null) {
					nbt = read;
				}
			}
			payload = new byte[in.readableBytes()];
			in.readBytes(payload);
		} catch (IOException | RuntimeException e) {
			payload = null;
			malformed = e.toString();
			in.skipBytes(in.readableBytes());
		}
	}

	/** The packet as the log names it. */
	private String describe() {
		return "entity packet " + packetId + " for entity " + entityId + " in dimension " + dimId;
	}

	/**
	 * The addressed entity in {@code world}, with its bytes read into {@link #nbt}; {@code null} when
	 * the world holds no such {@link INetworkEntity}.
	 */
	private INetworkEntity resolve(World world) {
		Entity ent = world.getEntityByID(entityId);
		return ent instanceof INetworkEntity ? (INetworkEntity) ent : null;
	}

	@Override
	public void executeServer(EntityPlayerMP player) {
		if (payload == null) {
			PacketSenderCheck.refuse(player, describe(), "it does not decode (" + malformed + ")");
			return;
		}
		if (!PacketSenderCheck.inSendersWorld(player, dimId)) {
			PacketSenderCheck.refuse(player, describe(), "the entity is not in the sender's world (he is in "
					+ player.world.provider.getDimension() + ")");
			return;
		}
		INetworkEntity found = resolve(player.world);
		if (found == null) {
			return;
		}
		if (!PacketSenderCheck.seesEntity(player, (Entity) found)) {
			PacketSenderCheck.refuse(player, describe(), "the server is not showing that entity to the sender");
			return;
		}
		try {
			found.readDataFromNetwork(Unpooled.wrappedBuffer(payload), packetId, nbt);
		} catch (RuntimeException e) {
			// Whatever an entity's reader throws on these bytes, the bytes are the client's.
			PacketSenderCheck.refuse(player, describe(), "its payload of " + payload.length
					+ " bytes does not decode as the entity reads it (" + e + ")");
			return;
		}
		entity = found;
		entity.useNetworkData(player, Side.SERVER, packetId, nbt);
	}

	@Override
	public void executeClient(EntityPlayer player) {
		// The receiving player's own world: this method is not @SideOnly and must stay loadable on a
		// dedicated server.
		if (payload == null || player == null || player.world == null) {
			return;
		}
		INetworkEntity found = resolve(player.world);
		if (found == null) {
			return;
		}
		found.readDataFromNetwork(Unpooled.wrappedBuffer(payload), packetId, nbt);
		entity = found;
		entity.useNetworkData(player, Side.CLIENT, packetId, nbt);
	}
}
