package dev.stannismod.stellurgy.libvulpes.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.EnumHand;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;

/**
 * A packet about the item a player holds in his main hand: the player's dimension and entity id, a
 * packet id, an optional compound, and whatever bytes that item writes for that id.
 *
 * <p>Decoding reads bytes only; the holder is looked up, and his item handed its bytes, on the game
 * thread.</p>
 *
 * <p><b>From a client, the only item it may speak for is the one in its own hand.</b> The server
 * applies the packet only when the player it names is the sender himself, in his own world; the item
 * is the sender's main-hand item, read when the packet executes. Bytes that do not decode are a dropped
 * packet, logged.</p>
 */
public class PacketItemModifcation extends BasePacket {

	private NBTTagCompound nbt;

	private byte packetId;
	private int entityId;
	private EntityPlayer entity;
	private INetworkItem machine;

	/** The world the sender named. Read off the wire; compared only on the game thread. */
	private int dimId;
	/**
	 * The item's own bytes, copied out of the decoder's buffer; {@code null} when the packet did not
	 * decode, and {@link #malformed} then says why.
	 */
	private byte[] payload;
	private String malformed;

	public PacketItemModifcation() {
		nbt = new NBTTagCompound();
	}

	public PacketItemModifcation(INetworkItem machine, EntityPlayer entity, byte packetId) {
		this();
		this.machine = machine;
		this.entity = entity;
		this.packetId = packetId;
		this.entityId = entity.getEntityId();
	}


	public PacketItemModifcation(INetworkItem machine, EntityPlayer entity, byte packetId, NBTTagCompound nbt) {
		this(machine, entity, packetId);
		this.nbt = nbt;
	}

	@Override
	public void write(ByteBuf out) {
		PacketBuffer buffer = new PacketBuffer(out);

		write(buffer);
	}

	private void write(PacketBuffer out) {
		out.writeInt(entity.world.provider.getDimension());
		out.writeInt(entity.getEntityId());
		out.writeByte(packetId);

		out.writeBoolean(!nbt.hasNoTags());

		if(!nbt.hasNoTags()) {
			out.writeCompoundTag(nbt);
		}

		machine.writeDataToNetwork(out, packetId, entity.getHeldItem(EnumHand.MAIN_HAND));
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
		return "held-item packet " + packetId + " for entity " + entityId + " in dimension " + dimId;
	}

	/** The item of {@code stack} when it speaks this channel, else {@code null}. */
	private static INetworkItem networkItemOf(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() instanceof INetworkItem ? (INetworkItem) stack.getItem() : null;
	}

	@Override
	public void executeServer(EntityPlayerMP player) {
		if (payload == null) {
			PacketSenderCheck.refuse(player, describe(), "it does not decode (" + malformed + ")");
			return;
		}
		if (entityId != player.getEntityId()) {
			PacketSenderCheck.refuse(player, describe(), "it names a holder other than the sender (entity "
					+ player.getEntityId() + ")");
			return;
		}
		if (!PacketSenderCheck.inSendersWorld(player, dimId)) {
			PacketSenderCheck.refuse(player, describe(), "it names a world other than the sender's ("
					+ player.world.provider.getDimension() + ")");
			return;
		}
		ItemStack itemStack = player.getHeldItem(EnumHand.MAIN_HAND);
		INetworkItem item = networkItemOf(itemStack);
		if (item == null) {
			return;
		}
		try {
			item.readDataFromNetwork(Unpooled.wrappedBuffer(payload), packetId, nbt, itemStack);
		} catch (RuntimeException e) {
			// Whatever an item's reader throws on these bytes, the bytes are the client's.
			PacketSenderCheck.refuse(player, describe(), "its payload of " + payload.length
					+ " bytes does not decode as the item reads it (" + e + ")");
			return;
		}
		item.useNetworkData(player, Side.SERVER, packetId, nbt, itemStack);
	}

	@Override
	public void executeClient(EntityPlayer player) {
		if (payload == null || player == null || player.world == null) {
			return;
		}
		// The holder the server named reads the bytes into his own item; the receiving player's item
		// applies them.
		Entity holder = player.world.getEntityByID(entityId);
		if (holder instanceof EntityPlayer) {
			ItemStack held = ((EntityPlayer) holder).getHeldItem(EnumHand.MAIN_HAND);
			INetworkItem heldItem = networkItemOf(held);
			if (heldItem != null) {
				heldItem.readDataFromNetwork(Unpooled.wrappedBuffer(payload), packetId, nbt, held);
			}
		}
		ItemStack itemStack = player.getHeldItem(EnumHand.MAIN_HAND);
		INetworkItem item = networkItemOf(itemStack);
		if (item != null) {
			item.useNetworkData(player, Side.CLIENT, packetId, nbt, itemStack);
		}
	}
}
