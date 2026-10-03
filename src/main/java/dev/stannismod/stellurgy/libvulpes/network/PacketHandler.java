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
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.network.BasePacket.BasePacketHandlerClient;
import dev.stannismod.stellurgy.libvulpes.network.BasePacket.BasePacketHandlerServer;

import java.lang.reflect.Method;
import java.util.EnumMap;

/**
 * The mod's network channel: its codec, the packet classes registered on it, and the pipes to send
 * through.
 *
 * <p>An instance IS the channel. Constructing one registers the channel with FML, which accepts a
 * name once per JVM and refuses it after — so the constructor is a lifecycle act and is called from
 * exactly one place, {@link LibVulpes#preInit}, which holds the result for the life of the process
 * (FML has no way to unregister a channel). Nothing happens at class load.</p>
 *
 * <p>The static {@code sendTo*} methods are a facade over that one channel, which is how the rest of
 * the mod sends; they hold no state and fail loudly before {@code preInit} has built it.</p>
 */
public final class PacketHandler {

	/** The channel's name on the wire; both sides must agree on it. */
	public static final String CHANNEL = "libVulpes";

	// Netty's own name generator for pipeline handlers, reached reflectively. Facts about the netty
	// on the classpath, not state: resolved once, never written again.
	private static final Class<?> defaultChannelPipeline;
	private static final Method generateName;
	static {
		try
		{
			defaultChannelPipeline = Class.forName("io.netty.channel.DefaultChannelPipeline");
			generateName = defaultChannelPipeline.getDeclaredMethod("generateName", ChannelHandler.class);
			generateName.setAccessible(true);
		}
		catch (Exception e)
		{
			FMLLog.log(Level.FATAL, e, "What? Netty isn't installed, what magic is this?");
			throw Throwables.propagate(e);
		}
	}

	private final Codec codec = new Codec();
	private final EnumMap<Side, FMLEmbeddedChannel> channels;
	/** The next packet class's wire id: registration order IS the wire format. */
	private int discriminatorNumber;

	/** Registers the channel with FML. See the class comment for who may call this. */
	public PacketHandler() {
		channels = NetworkRegistry.INSTANCE.newChannel(CHANNEL, codec);
	}

	public void addDiscriminator(Class<? extends BasePacket> clazz) {
		codec.addDiscriminator(discriminatorNumber, clazz);
		discriminatorNumber++;

		if(FMLCommonHandler.instance().getSide().isClient()) {
			FMLEmbeddedChannel channel = channels.get(Side.CLIENT);
			String type = channel.findChannelHandlerNameForType(Codec.class);
			addClientHandlerAfter(channel, type, new BasePacketHandlerClient(), clazz);
		}
		FMLEmbeddedChannel channel = channels.get(Side.SERVER);
		String type = channel.findChannelHandlerNameForType(Codec.class);
		addServerHandlerAfter(channel, type, new BasePacketHandlerServer(), clazz);
	}

	private <REQ extends IMessage, REPLY extends IMessage, NH extends INetHandler> void addServerHandlerAfter(FMLEmbeddedChannel channel, String type, IMessageHandler<? super REQ, ? extends REPLY> messageHandler, Class<REQ> requestType)
    {
        SimpleChannelHandlerWrapper<REQ, REPLY> handler = getHandlerWrapper(messageHandler, Side.SERVER, requestType);
        channel.pipeline().addAfter(type, generateName(channel.pipeline(), handler), handler);
    }

    private <REQ extends IMessage, REPLY extends IMessage, NH extends INetHandler> void addClientHandlerAfter(FMLEmbeddedChannel channel, String type, IMessageHandler<? super REQ, ? extends REPLY> messageHandler, Class<REQ> requestType)
    {
        SimpleChannelHandlerWrapper<REQ, REPLY> handler = getHandlerWrapper(messageHandler, Side.CLIENT, requestType);
        channel.pipeline().addAfter(type, generateName(channel.pipeline(), handler), handler);
    }

    private <REPLY extends IMessage, REQ extends IMessage> SimpleChannelHandlerWrapper<REQ, REPLY> getHandlerWrapper(IMessageHandler<? super REQ, ? extends REPLY> messageHandler, Side side, Class<REQ> requestType)
    {
        return new SimpleChannelHandlerWrapper<>(messageHandler, side, requestType);
    }

    private static String generateName(ChannelPipeline pipeline, ChannelHandler handler)
    {
        try
        {
            return (String)generateName.invoke(defaultChannelPipeline.cast(pipeline), handler);
        }
        catch (Exception e)
        {
            FMLLog.log(Level.FATAL, e, "It appears we somehow have a not-standard pipeline. Huh");
            throw Throwables.propagate(e);
        }
    }

	private FMLEmbeddedChannel side(Side side) {
		return channels.get(side);
	}

	/** The one channel, as {@link LibVulpes#preInit} built it. */
	private static PacketHandler channel() {
		return LibVulpes.instance.packets();
	}

	public static void sendToServer(BasePacket packet) {
		FMLEmbeddedChannel channel = channel().side(Side.CLIENT);
		channel.attr(FMLOutboundHandler.FML_MESSAGETARGET).set(FMLOutboundHandler.OutboundTarget.TOSERVER);
		channel.writeAndFlush(packet).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
	}

	public static void sendToPlayersTrackingEntity(BasePacket packet, Entity entity) {
		for( EntityPlayer player : ((WorldServer)entity.world).getEntityTracker().getTrackingPlayers(entity)) {
			sendToPlayer(packet, player);
		}
	}

	public static void sendToAll(BasePacket packet) {
		FMLEmbeddedChannel channel = channel().side(Side.SERVER);
		channel.attr(FMLOutboundHandler.FML_MESSAGETARGET).set(FMLOutboundHandler.OutboundTarget.ALL);
		channel.writeAndFlush(packet).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
	}

	public static void sendToPlayer(BasePacket packet, EntityPlayer player) {
		FMLEmbeddedChannel channel = channel().side(Side.SERVER);
		channel.attr(FMLOutboundHandler.FML_MESSAGETARGET).set(FMLOutboundHandler.OutboundTarget.PLAYER);
		channel.attr(FMLOutboundHandler.FML_MESSAGETARGETARGS).set(player);
		channel.writeAndFlush(packet).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
	}

	public static void sendToDispatcher(BasePacket packet, NetworkManager netman) {
		FMLEmbeddedChannel channel = channel().side(Side.SERVER);
		channel.attr(FMLOutboundHandler.FML_MESSAGETARGET).set(FMLOutboundHandler.OutboundTarget.DISPATCHER);
		channel.attr(FMLOutboundHandler.FML_MESSAGETARGETARGS).set(NetworkDispatcher.get(netman));
		channel.writeAndFlush(packet).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
	}

	public static void sendToNearby(BasePacket packet,int dimId, int x, int y, int z, double dist) {
		FMLEmbeddedChannel channel = channel().side(Side.SERVER);
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
