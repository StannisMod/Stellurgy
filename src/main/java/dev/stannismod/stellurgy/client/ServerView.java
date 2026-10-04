package dev.stannismod.stellurgy.client;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.network.PacketSystemBodiesSync;
import dev.stannismod.stellurgy.space.SpaceClockSync;
import dev.stannismod.stellurgy.stations.SpaceObjectManager;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * What this client has been told about the server it is connected to: the galaxy (planets, stars,
 * stations), the configuration the server sent, the server's space clock, which dimension is its
 * hyperspace, and the sky it broadcasts for each space slot.
 *
 * <p>One per connection, held by {@link ClientProxy} from the moment the connection is made until the
 * client has unloaded the last world it showed of that server — so nothing of one server can be read
 * while connected to the next, and nothing has to be cleared in between: the whole object is dropped.
 * The galaxy and the configuration arrive before the join-game packet, while the client has no player
 * and no world yet, so the view exists from the connection rather than from either of those.</p>
 */
@SideOnly(Side.CLIENT)
public final class ServerView {

    /** The connection this view describes. */
    final NetworkManager connection;

    public final DimensionManager dimensions;
    public final SpaceObjectManager spaceObjects;
    private final SpaceClockSync clock = new SpaceClockSync();

    /** The configuration the server sent, or {@code null}: a local server sends none. */
    private volatile StellurgyConfiguration serverConfig;
    /** The server's hyperspace dimension, or {@link Integer#MIN_VALUE} until the server has said. */
    private volatile int hyperspaceDimId = Integer.MIN_VALUE;
    /** The sky of every space slot, as the server last broadcast it. Replaced whole, never edited. */
    private volatile Map<Integer, List<PacketSystemBodiesSync.RenderBody>> skyBodies = Collections.emptyMap();
    private volatile Map<Integer, List<PacketSystemBodiesSync.RenderNebula>> skyNebulae = Collections.emptyMap();
    /** Whether the galaxy changed since the client's recipe views were last rebuilt from it. */
    private volatile boolean recipeViewsStale;

    ServerView(NetworkManager connection) {
        this.connection = connection;
        this.dimensions = new DimensionManager(StellurgyConfiguration.getCurrentConfig().minDimension);
        this.spaceObjects = new SpaceObjectManager();
    }

    /**
     * The server this client is connected to.
     *
     * @throws IllegalStateException when the client has no connection — there is then no server whose
     *                               galaxy, clock or sky could be meant
     */
    public static ServerView current() {
        ServerView view = currentOrNull();
        if (view == null) {
            throw new IllegalStateException("No open connection to a server: there is no galaxy on this client");
        }
        return view;
    }

    /** The server this client is connected to, or {@code null} when it has none. */
    public static ServerView currentOrNull() {
        return ((ClientProxy) Stellurgy.proxy).serverView();
    }

    /** The client's copy of the server's space clock. */
    public SpaceClockSync clock() {
        return clock;
    }

    /** Whether the server is another process: false for the integrated server of single player. */
    public boolean remote() {
        return !connection.isLocalChannel();
    }

    /** The configuration the server sent, or {@code null} when it sent none. */
    public StellurgyConfiguration serverConfig() {
        return serverConfig;
    }

    /** Takes the configuration the server sent at login. It is sent once per connection. */
    public void adoptServerConfig(StellurgyConfiguration config) {
        if (serverConfig != null) {
            throw new IllegalStateException("This connection already has the server's configuration; it is sent once, at login");
        }
        serverConfig = config;
    }

    /** The server's hyperspace dimension, or {@link Integer#MIN_VALUE} when it has not said yet. */
    public int hyperspaceDimId() {
        return hyperspaceDimId;
    }

    /** Learn the server's hyperspace dim id. {@link Integer#MIN_VALUE} means "none yet" and is ignored. */
    public void adoptHyperspaceDimId(int id) {
        if (id != Integer.MIN_VALUE) {
            hyperspaceDimId = id;
        }
    }

    /** The bodies to draw in {@code slotDimId}'s sky; empty when the server has sent none for it. */
    public List<PacketSystemBodiesSync.RenderBody> skyBodies(int slotDimId) {
        List<PacketSystemBodiesSync.RenderBody> bodies = skyBodies.get(slotDimId);
        return bodies == null ? Collections.<PacketSystemBodiesSync.RenderBody>emptyList() : bodies;
    }

    /** The nebulae to draw in {@code slotDimId}'s sky; empty when the server has sent none for it. */
    public List<PacketSystemBodiesSync.RenderNebula> skyNebulae(int slotDimId) {
        List<PacketSystemBodiesSync.RenderNebula> clouds = skyNebulae.get(slotDimId);
        return clouds == null ? Collections.<PacketSystemBodiesSync.RenderNebula>emptyList() : clouds;
    }

    /** The server told this client about its planets; views derived from them must be rebuilt. */
    public void galaxyChanged() {
        recipeViewsStale = true;
    }

    /** Whether the recipe views derived from the galaxy are older than the galaxy. */
    public boolean recipeViewsStale() {
        return recipeViewsStale;
    }

    /** The recipe views were rebuilt from the galaxy as it stands. */
    public void recipeViewsRebuilt() {
        recipeViewsStale = false;
    }

    /** Take a sky broadcast: it describes every slot, so it replaces the previous one whole. */
    public void acceptSky(Map<Integer, List<PacketSystemBodiesSync.RenderBody>> bodies,
                          Map<Integer, List<PacketSystemBodiesSync.RenderNebula>> nebulae) {
        skyBodies = Collections.unmodifiableMap(bodies);
        skyNebulae = Collections.unmodifiableMap(nebulae);
    }
}
