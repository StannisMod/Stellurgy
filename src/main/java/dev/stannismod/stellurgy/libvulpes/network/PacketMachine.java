package dev.stannismod.stellurgy.libvulpes.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import dev.stannismod.stellurgy.libvulpes.util.INetworkMachine;

/**
 * A packet addressed to an {@link INetworkMachine} tile: a dimension, a block position, a machine
 * packet id, and whatever bytes that machine writes for that id.
 *
 * <p><b>Decoding reads BYTES; the game thread resolves the MACHINE.</b> Netty decodes on its own IO
 * thread, so anything a decoder does to a world it does off the game thread. {@link #read(ByteBuf)}
 * copies the payload and stops; world, chunk and tile are looked up in
 * {@link #executeServer(EntityPlayerMP)} / {@link #executeClient(EntityPlayer)}, which the channel's
 * handlers schedule onto the game thread. A machine that cannot be found there is dropped.</p>
 *
 * <p>The payload is COPIED rather than retained: a retained buffer would have to be released on
 * every path out of the executor, including the ones that drop the packet.</p>
 *
 * <p><b>From a client, the address is a claim.</b> The server applies it only when the machine is in
 * the sender's own world and within his container reach ({@link PacketSenderCheck}); bytes that do
 * not decode — too short a header, or a payload the machine cannot read — are a dropped packet, never
 * an exception on the server thread. Each refusal is logged.</p>
 */
public class PacketMachine extends BasePacket {

	/** The header every machine packet carries before its payload: dimension, x, y, z, packet id. */
	private static final int HEADER_BYTES = 4 * Integer.BYTES + 1;

	INetworkMachine machine;

	NBTTagCompound nbt;

	byte packetId;

	/** Where the machine is. Read off the wire; resolved only on the game thread. */
	private int dimId;
	private BlockPos pos;
	/**
	 * The machine's own bytes, copied out of the decoder's buffer and read back on the game thread;
	 * {@code null} when the header itself did not decode.
	 */
	private byte[] payload;

	public PacketMachine() {
		nbt = new NBTTagCompound();
	}

	public PacketMachine(INetworkMachine machine, byte packetId) {
		this();
		this.machine = machine;
		this.packetId = packetId;
	}


	@Override
	public void write(ByteBuf outline) {
		outline.writeInt(((TileEntity)machine).getWorld().provider.getDimension());
		outline.writeInt(((TileEntity)machine).getPos().getX());
		outline.writeInt(((TileEntity)machine).getPos().getY());
		outline.writeInt(((TileEntity)machine).getPos().getZ());

		outline.writeByte(packetId);

		machine.writeDataToNetwork(outline, packetId);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void readClient(ByteBuf in) {
		readAddressAndPayload(in);
	}

	@Override
	public void read(ByteBuf in) {
		readAddressAndPayload(in);
	}

	/**
	 * The whole of decoding: an address, and the bytes behind it. Touches nothing that ticks. A header
	 * shorter than {@link #HEADER_BYTES} leaves {@link #payload} {@code null}, and the executor drops
	 * the packet: an exception here would be thrown inside Netty's decoder, which closes the connection.
	 */
	private void readAddressAndPayload(ByteBuf in) {
		if (in.readableBytes() < HEADER_BYTES) {
			in.skipBytes(in.readableBytes());
			return;
		}
		dimId = in.readInt();
		int x = in.readInt();
		int y = in.readInt();
		int z = in.readInt();
		pos = new BlockPos(x, y, z);
		packetId = in.readByte();
		payload = new byte[in.readableBytes()];
		in.readBytes(payload);
	}

	/** The packet as the log names it. */
	private String describe() {
		return "machine packet " + packetId + (pos == null ? " (no address)" : " for " + pos + " in dimension " + dimId);
	}

	public void executeClient(EntityPlayer player) {
		// The receiving player's own world, not Minecraft.getMinecraft() — this method is not
		// @SideOnly and must stay loadable on a dedicated server, where that class does not exist.
		World world = player == null ? null : player.world;
		if (world == null || payload == null || !world.isBlockLoaded(pos)) {
			return;
		}
		TileEntity ent = world.getTileEntity(pos);
		if (!(ent instanceof INetworkMachine)) {
			return;
		}
		machine = (INetworkMachine) ent;
		machine.readDataFromNetwork(Unpooled.wrappedBuffer(payload), packetId, nbt);
		machine.useNetworkData(player, Side.CLIENT, packetId, nbt);
	}

	public void executeServer(EntityPlayerMP player) {
		if (payload == null) {
			PacketSenderCheck.refuse(player, describe(), "the header is shorter than " + HEADER_BYTES + " bytes");
			return;
		}
		if (!PacketSenderCheck.inSendersWorld(player, dimId)) {
			PacketSenderCheck.refuse(player, describe(), "the machine is not in the sender's world (he is in "
					+ player.world.provider.getDimension() + ")");
			return;
		}
		if (!PacketSenderCheck.withinContainerReach(player, pos)) {
			PacketSenderCheck.refuse(player, describe(), "the sender is not within reach of it (he is at "
					+ player.getPosition() + ")");
			return;
		}
		World world = player.world;
		if (!world.isBlockLoaded(pos)) {
			return;
		}
		TileEntity ent = world.getTileEntity(pos);
		if (!(ent instanceof INetworkMachine)) {
			return;
		}
		INetworkMachine found = (INetworkMachine) ent;
		try {
			found.readDataFromNetwork(Unpooled.wrappedBuffer(payload), packetId, nbt);
		} catch (RuntimeException e) {
			// Whatever a machine's reader throws on these bytes, the bytes are the client's.
			PacketSenderCheck.refuse(player, describe(), "its payload of " + payload.length
					+ " bytes does not decode as the machine reads it (" + e + ")");
			return;
		}
		machine = found;
		machine.useNetworkData(player, Side.SERVER, packetId, nbt);
	}

}
