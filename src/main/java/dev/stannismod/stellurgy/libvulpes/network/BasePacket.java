package dev.stannismod.stellurgy.libvulpes.network;

import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * A packet of the libVulpes channel: decoded on the network thread, executed on the game thread.
 *
 * <p><b>The no-argument constructor, {@link #readClient} and {@link #read} run on the NETTY thread.</b>
 * The channel's codec builds the packet from its discriminator and fills it there, while the game
 * thread is ticking the world, rendering, or still finishing a login. So they only turn bytes into
 * this packet's own fields. They never read or change the state of the game or of this mod: no world,
 * entity, tile, player, client or server instance, no {@code DimensionManager} / {@code ServerView} /
 * {@code SpaceObjectManager} or any other manager, no configuration currently in force. That state
 * belongs to the game thread, and on a client it may not exist yet: packets can arrive before the
 * connection's galaxy has been made, and an exception thrown while decoding is fatal to the
 * connection.</p>
 *
 * <p><b>{@link #executeClient} and {@link #executeServer} run on the GAME thread</b>, queued by the two
 * handlers below in arrival order, and are where everything the packet DOES belongs — every lookup
 * and every write. A decoder that needs a world to make sense of its bytes stores the raw values
 * (an id, a position, the tag) and resolves them in its executor.</p>
 */
public abstract class BasePacket implements IMessage {
	/** Effectively final, process lifetime: built once at class initialisation. */
	private static final BiMap<Integer,Class<? extends BasePacket>> idMap;

	static {
		ImmutableBiMap.Builder<Integer, Class<? extends BasePacket>> builder = ImmutableBiMap.builder();
		//builder.put(Integer.valueOf(0), PacketMachine.class);
		idMap = builder.build();
	}

	public static BasePacket constructPacket(int packetId) throws ProtocolException, InstantiationException, IllegalAccessException {
		Class<? extends BasePacket> clazz = idMap.get(packetId);
		if(clazz == null){
			throw new ProtocolException("Protocol Exception!  Unknown Packet Id!");
		} else {
			return clazz.newInstance();
		}
	}




	public static class ProtocolException extends Exception {

		public ProtocolException() {
		}

		public ProtocolException(String message, Throwable cause) {
			super(message, cause);
		}

		public ProtocolException(String message) {
			super(message);
		}

		public ProtocolException(Throwable cause) {
			super(cause);
		}
	}

	public final int getPacketId() {
		if(idMap.inverse().containsKey(getClass())) {
			return idMap.inverse().get(getClass());
		} else {
			throw new RuntimeException("Packet " + getClass().getSimpleName() + " is a missing mapping!");
		}
	}

	public abstract void write(ByteBuf out);

	public abstract void readClient(ByteBuf in);

	public abstract void read(ByteBuf in);

	@SideOnly(Side.CLIENT)
	public abstract void executeClient(EntityPlayer thePlayer);

	public abstract void executeServer(EntityPlayerMP player);

	@Override
	public void fromBytes(ByteBuf buf) {
		switch(FMLCommonHandler.instance().getSide()) {
		case CLIENT:
			readClient(buf);
			break;
		case SERVER:
			read(buf);
			break;
		}
	}

	@Override
	public void toBytes(ByteBuf buf) {
		write(buf);
	}

	/*
	 * How a packet reaches its executor. The channel's codec decodes on the netty thread; these two
	 * handlers, one per side, then queue the executor on that side's game thread, in arrival order,
	 * in the same queue vanilla's own packets are applied from. Every packet of this channel runs its
	 * execute method on the game thread.
	 *
	 * The PLAYER is read when the executor runs, never when the packet arrives. Between the two the
	 * game thread may still apply packets queued earlier, and some of those replace the player: a
	 * client's SPacketRespawn builds a new EntityPlayerSP in a new world, a server's respawn builds a
	 * new EntityPlayerMP. A player captured on arrival is then the old one, and a packet that looks its
	 * world up through him lands in the world he left.
	 */

	public static class BasePacketHandlerServer implements IMessageHandler<BasePacket, IMessage> {
		@Override
		public IMessage onMessage(BasePacket message, MessageContext ctx) {
			NetHandlerPlayServer handler = ctx.getServerHandler();
			handler.player.getServerWorld().addScheduledTask(() -> message.executeServer(handler.player));
			return null;
		}
	}

	public static class BasePacketHandlerClient implements IMessageHandler<BasePacket, IMessage> {
		@Override
		public IMessage onMessage(BasePacket message, MessageContext ctx) {
			Minecraft mc = Minecraft.getMinecraft();
			mc.addScheduledTask(() -> message.executeClient(mc.player));
			return null;
		}
	}
}
