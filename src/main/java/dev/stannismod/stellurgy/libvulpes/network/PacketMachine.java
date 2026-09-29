package dev.stannismod.stellurgy.libvulpes.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import dev.stannismod.stellurgy.libvulpes.util.INetworkMachine;

/**
 * A packet addressed to an {@link INetworkMachine} tile: a dimension, a block position, a machine
 * packet id, and whatever bytes that machine writes for that id.
 *
 * <p><b>Decoding reads BYTES; the game thread resolves the MACHINE.</b> Netty decodes on its own IO
 * thread, so anything a decoder does to a world it does off the game thread — and this class used to
 * resolve the destination world, its chunk and its tile right there, purely to find whom to hand the
 * buffer to. Two things came of that. It could not resolve a world the server thread happened to
 * have in its hands, and it checked the one thing it had no guard for last: a null world threw an
 * NPE straight out of the decoder. An exception in a Netty decoder is fatal to the connection, so a
 * single such packet disconnected the player — every time, if the client re-sent it on each join.
 *
 * <p>So {@link #read(ByteBuf)} now copies the payload and stops. World, chunk and tile are looked up
 * in {@link #executeServer(EntityPlayerMP)} / {@link #executeClient(EntityPlayer)}, which the
 * channel's handlers have always scheduled onto the game thread, and all three may legitimately be
 * absent — exactly as the chunk and the tile always could. A packet whose machine cannot be found is
 * dropped, which is what this class already did for two of those three and documented for the
 * third.</p>
 *
 * <p>The payload is COPIED rather than retained: a retained buffer would have to be released on
 * every path out of the executor, including the ones that drop the packet, and a leaked buffer is a
 * worse bug than the one this fixes.</p>
 */
public class PacketMachine extends BasePacket {

	INetworkMachine machine;

	NBTTagCompound nbt;

	byte packetId;

	/** Where the machine is. Read off the wire; resolved only on the game thread. */
	private int dimId;
	private BlockPos pos;
	/** The machine's own bytes, copied out of the decoder's buffer and read back on the game thread. */
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

	/** The whole of decoding: an address, and the bytes behind it. Touches nothing that ticks. */
	private void readAddressAndPayload(ByteBuf in) {
		dimId = in.readInt();
		int x = in.readInt();
		int y = in.readInt();
		int z = in.readInt();
		pos = new BlockPos(x, y, z);
		packetId = in.readByte();
		payload = new byte[in.readableBytes()];
		in.readBytes(payload);
	}

	/**
	 * Find the addressed machine in {@code world} and hand it its bytes. Game thread only, where a
	 * world lookup means something.
	 *
	 * @return the machine, or {@code null} when the world is not loaded, the block is not loaded, or
	 *         whatever is at that position is not an {@link INetworkMachine}
	 */
	private INetworkMachine resolveAndFeed(World world) {
		if (world == null || payload == null || !world.isBlockLoaded(pos)) {
			return null;
		}
		TileEntity ent = world.getTileEntity(pos);
		if (!(ent instanceof INetworkMachine)) {
			return null;
		}
		INetworkMachine found = (INetworkMachine) ent;
		found.readDataFromNetwork(Unpooled.wrappedBuffer(payload), packetId, nbt);
		return found;
	}

	public void executeClient(EntityPlayer player) {
		// The receiving player's own world, not Minecraft.getMinecraft() — this method is not
		// @SideOnly and must stay loadable on a dedicated server, where that class does not exist.
		machine = resolveAndFeed(player == null ? null : player.world);
		//Machine can be null if not all chunks are loaded
		if(machine != null)
			machine.useNetworkData(player, Side.CLIENT, packetId, nbt);
	}

	public void executeServer(EntityPlayerMP player) {
		machine = resolveAndFeed(DimensionManager.getWorld(dimId));
		if(machine != null)
			machine.useNetworkData(player, Side.SERVER, packetId, nbt);
	}

	public void execute(EntityPlayer player, Side side) {
		machine = resolveAndFeed(side.isClient() ? player.world : DimensionManager.getWorld(dimId));
		if(machine != null)
			machine.useNetworkData(player, side, packetId, nbt);
	}

}
