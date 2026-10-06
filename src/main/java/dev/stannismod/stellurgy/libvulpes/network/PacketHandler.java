package dev.stannismod.stellurgy.libvulpes.network;

import com.google.common.base.Throwables;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.network.FMLEmbeddedChannel;
import net.minecraftforge.fml.common.network.FMLIndexedMessageToMessageCodec;
import net.minecraftforge.fml.common.network.FMLOutboundHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleChannelHandlerWrapper;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.Level;
import dev.stannismod.stellurgy.libvulpes.network.BasePacket.BasePacketHandlerClient;
import dev.stannismod.stellurgy.libvulpes.network.BasePacket.BasePacketHandlerServer;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.List;

/**
 * The mod's network channel: opening it, and sending through it.
 *
 * <p>Holds nothing. The channel is FML's: {@code NetworkRegistry} keeps it by name from the moment it
 * is opened until the JVM exits and never lets it go, so a field here would only be a second copy of
 * what FML already owns. Opening is one call over the whole ordered packet list, so the codec and the
 * wire-id counter live exactly as long as that call; a second opening is refused by FML itself.</p>
 */
public final class PacketHandler {

	/** The channel's name on the wire; both sides must agree on it. */
	public static final String CHANNEL = "libVulpes";

	private PacketHandler() {
	}

	/**
	 * Registers the channel with FML and every packet class on it, numbered in list order — that order
	 * IS the wire format. Called once, from the mod's pre-init; FML throws on a second call.
	 */
	public static void openChannel(List<Class<? extends BasePacket>> packets) {
		Method generateName = pipelineNameGenerator();
		Codec codec = new Codec();
		EnumMap<Side, FMLEmbeddedChannel> channels = NetworkRegistry.INSTANCE.newChannel(CHANNEL, codec);
		boolean physicalClient = FMLCommonHandler.instance().getSide().isClient();
		for (int id = 0; id < packets.size(); id++) {
			Class<? extends BasePacket> clazz = packets.get(id);
			codec.addDiscriminator(id, clazz);
			if (physicalClient) {
				addHandlerAfterCodec(channels.get(Side.CLIENT), generateName,
						new SimpleChannelHandlerWrapper<>(new BasePacketHandlerClient(), Side.CLIENT, clazz));
			}
			addHandlerAfterCodec(channels.get(Side.SERVER), generateName,
					new SimpleChannelHandlerWrapper<>(new BasePacketHandlerServer(), Side.SERVER, clazz));
		}
	}

	private static <REQ extends IMessage, REPLY extends IMessage> void addHandlerAfterCodec(
			FMLEmbeddedChannel channel, Method generateName, SimpleChannelHandlerWrapper<REQ, REPLY> handler) {
		String codecName = channel.findChannelHandlerNameForType(Codec.class);
		channel.pipeline().addAfter(codecName, generateName(generateName, channel.pipeline(), handler), handler);
	}

	/** Netty's own name generator for pipeline handlers, which it does not expose. */
	private static Method pipelineNameGenerator() {
		try {
			Method method = Class.forName("io.netty.channel.DefaultChannelPipeline")
					.getDeclaredMethod("generateName", ChannelHandler.class);
			method.setAccessible(true);
			return method;
		} catch (Exception e) {
			FMLLog.log(Level.FATAL, e, "What? Netty isn't installed, what magic is this?");
			throw Throwables.propagate(e);
		}
	}

	private static String generateName(Method generateName, ChannelPipeline pipeline, ChannelHandler handler) {
		try {
			return (String) generateName.invoke(pipeline, handler);
		} catch (Exception e) {
			FMLLog.log(Level.FATAL, e, "It appears we somehow have a not-standard pipeline. Huh");
			throw Throwables.propagate(e);
		}
	}

	/**
	 * @throws IllegalStateException before {@link #openChannel} has run: nothing can be sent yet
	 */
	private static FMLEmbeddedChannel channel(Side side) {
		FMLEmbeddedChannel channel = NetworkRegistry.INSTANCE.getChannel(CHANNEL, side);
		if (channel == null) {
			throw new IllegalStateException("the " + CHANNEL + " channel is not open: pre-init has not run");
		}
		return channel;
	}

	public static void sendToServer(BasePacket packet) {
		FMLEmbeddedChannel channel = channel(Side.CLIENT);
		channel.attr(FMLOutboundHandler.FML_MESSAGETARGET).set(FMLOutboundHandler.OutboundTarget.TOSERVER);
		channel.writeAndFlush(packet).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
	}

	public static void sendToPlayersTrackingEntity(BasePacket packet, Entity entity) {
		for( EntityPlayer player : ((WorldServer)entity.world).getEntityTracker().getTrackingPlayers(entity)) {
			sendToPlayer(packet, player);
		}
	}

	public static void sendToAll(BasePacket packet) {
		FMLEmbeddedChannel channel = channel(Side.SERVER);
		channel.attr(FMLOutboundHandler.FML_MESSAGETARGET).set(FMLOutboundHandler.OutboundTarget.ALL);
		channel.writeAndFlush(packet).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
	}

	public static void sendToPlayer(BasePacket packet, EntityPlayer player) {
		FMLEmbeddedChannel channel = channel(Side.SERVER);
		channel.attr(FMLOutboundHandler.FML_MESSAGETARGET).set(FMLOutboundHandler.OutboundTarget.PLAYER);
		channel.attr(FMLOutboundHandler.FML_MESSAGETARGETARGS).set(player);
		channel.writeAndFlush(packet).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
	}

	public static void sendToDispatcher(BasePacket packet, NetworkManager netman) {
		FMLEmbeddedChannel channel = channel(Side.SERVER);
		channel.attr(FMLOutboundHandler.FML_MESSAGETARGET).set(FMLOutboundHandler.OutboundTarget.DISPATCHER);
		channel.attr(FMLOutboundHandler.FML_MESSAGETARGETARGS).set(NetworkDispatcher.get(netman));
		channel.writeAndFlush(packet).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
	}

	public static void sendToNearby(BasePacket packet,int dimId, int x, int y, int z, double dist) {
		FMLEmbeddedChannel channel = channel(Side.SERVER);
		channel.attr(FMLOutboundHandler.FML_MESSAGETARGET).set(FMLOutboundHandler.OutboundTarget.ALLAROUNDPOINT);
		channel.attr(FMLOutboundHandler.FML_MESSAGETARGETARGS).set(new NetworkRegistry.TargetPoint(dimId, x, y, z,dist));
		channel.writeAndFlush(packet).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
	}

	public static void sendToNearby(BasePacket packet,int dimId, BlockPos pos, double dist) {
		sendToNearby(packet, dimId, pos.getX(), pos.getY(), pos.getZ(), dist);
	}

	private static final class Codec extends FMLIndexedMessageToMessageCodec<BasePacket> {

		@Override
		public void encodeInto(ChannelHandlerContext ctx, BasePacket msg,
				ByteBuf data) {
			msg.write(data);
		}

		@Override
		public void decodeInto(ChannelHandlerContext ctx, ByteBuf data, BasePacket packet) {

			Side side = FMLCommonHandler.instance().getSide();
			if(FMLCommonHandler.instance().getSide().isClient()) {
				side = FMLCommonHandler.instance().getEffectiveSide();
			}

			// Decoding only. Executing is the per-class handlers' job (BasePacket's two handlers), which
			// queue it on the game thread.
			switch (side) {
			case CLIENT:
				packet.readClient(data);
				break;
			case SERVER:
				packet.read(data);
				break;
			}

		}
	}

}
