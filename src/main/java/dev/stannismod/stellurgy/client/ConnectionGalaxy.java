package dev.stannismod.stellurgy.client;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.stations.SpaceObjectManager;
import io.netty.util.AttributeKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * The galaxy as one connection to a server has been told about it: its planets and stars, and its
 * stations. Owned by the CONNECTION — kept on the netty channel, built when the connection is made
 * and gone with it — so nothing of one server can be read while connected to the next, and nothing
 * has to be cleared in between.
 *
 * <p>Looked up through FML's current play handler rather than the player: the server sends the galaxy
 * before the join-game packet, while the client has no player yet.</p>
 */
@SideOnly(Side.CLIENT)
public final class ConnectionGalaxy {

    /** Effectively final, client lifetime: a constant key, never written after class initialisation. */
    private static final AttributeKey<ConnectionGalaxy> KEY = AttributeKey.valueOf("stellurgy:galaxy");

    public final DimensionManager dimensions;
    public final SpaceObjectManager spaceObjects;

    private ConnectionGalaxy() {
        this.dimensions = new DimensionManager(StellurgyConfiguration.getCurrentConfig().minDimension);
        this.spaceObjects = new SpaceObjectManager();
    }

    /**
     * The galaxy of the connection this client has open.
     *
     * @throws IllegalStateException when the client has no open connection — there is then no server
     *                               whose galaxy could be meant
     */
    public static ConnectionGalaxy current() {
        INetHandler handler = FMLClientHandler.instance().getClientPlayHandler();
        if (handler instanceof NetHandlerPlayClient) {
            ConnectionGalaxy galaxy = of(((NetHandlerPlayClient) handler).getNetworkManager());
            if (galaxy != null) {
                return galaxy;
            }
        }
        throw new IllegalStateException("No open connection to a server: there is no galaxy on this client");
    }

    /**
     * The galaxy kept on {@code manager}'s channel while that connection is still the client's, or
     * {@code null}. A connection is the client's while its channel is open AND for the moment after it
     * closes in which the client still shows its world: the channel closes on the network thread, and
     * the game thread renders that world until it processes the disconnect — the sky reads the galaxy
     * every frame of that gap.
     */
    public static ConnectionGalaxy of(NetworkManager manager) {
        if (manager == null || !(manager.isChannelOpen() || Minecraft.getMinecraft().world != null)) {
            return null;
        }
        return keptOn(manager);
    }

    /**
     * The galaxy kept on {@code manager}'s channel whether or not it is still open, or {@code null}
     * when it has none — for the disconnect, which runs as the channel closes.
     */
    public static ConnectionGalaxy keptOn(NetworkManager manager) {
        return manager.channel().attr(KEY).get();
    }

    /** Which events build it. Static handlers, per the repo's subscriber shape. */
    public static final class Events {

        private Events() {
        }

        @SubscribeEvent
        public static void onConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
            if (event.getManager().channel().attr(KEY).setIfAbsent(new ConnectionGalaxy()) != null) {
                throw new IllegalStateException("This connection already has a galaxy; it is built once, on connect");
            }
        }

        /** Moves the bodies of the open connection's galaxy along their orbits, for the sky. */
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            INetHandler handler = FMLClientHandler.instance().getClientPlayHandler();
            ConnectionGalaxy galaxy = handler instanceof NetHandlerPlayClient
                    ? of(((NetHandlerPlayClient) handler).getNetworkManager()) : null;
            if (galaxy != null) {
                galaxy.dimensions.tickDimensionsClient();
            }
        }

    }
}
