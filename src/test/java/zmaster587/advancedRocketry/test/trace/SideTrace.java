package zmaster587.advancedRocketry.test.trace;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Everything the test instruments keep on ONE side: the windows tests have open there, and the
 * memory an edge recorder needs between two frames.
 *
 * <h2>Who owns it, and what releases it</h2>
 *
 * <p>There are exactly two lifetimes that own state in this game, the client and the server, and
 * each is an object: {@code Minecraft} and {@code MinecraftServer}. One {@code SideTrace} is an
 * instance field of each — mixed in by {@code MixinMinecraftSideTrace} and
 * {@code MixinMinecraftServerSideTrace} through {@link SideTraceOwner} — so it is created with its
 * side and released with it, and no static anywhere points at it. A mixin on the render thread has no
 * test context and no injection point, and it does not need one: the side it runs on is always in
 * reach ({@link #client()}, {@link #of(World)}).</p>
 *
 * <h2>Windows: an object per test, addressed by handle</h2>
 *
 * <p>A window is created by a test through the harness's static-invoke bridge — each window class
 * has a static FACTORY {@code open(...)}, a method and not state — and registered here under an int
 * handle, which the test instance keeps and passes back to {@code peek}/{@code close}. So a window
 * belongs to the scenario that opened it: a reader that forgot to open has no handle to read with,
 * where the static accumulators this replaces handed it whatever the previous scenario left. Two
 * windows of one kind can be open at once and see the same frames independently.</p>
 *
 * <p>A handle that is not open is a LOUD failure ({@link #window}), never an empty reading: a test
 * asking a closed window about a frame has a bug of its own, and an empty record would look like the
 * subject went quiet. Windows a failed scenario left open are released by {@link #discardAll}, which
 * the shared client base calls before every scenario.</p>
 *
 * <h2>Memory: what a recorder needs between two frames</h2>
 *
 * <p>An edge recorder ("the camera engaged", "the HUD line changed") must remember the previous
 * frame whether or not any test is watching, so that memory is not a window's. It is the side's, and
 * lives here ({@link #memory}), one object per recorder class. Where some other object already owns
 * the thing a recorder watches — a craft's interpolator, a pilot seat — the memory is a field on
 * that object instead, and not here.</p>
 *
 * <p>Test source set: absent from a released jar.</p>
 */
public final class SideTrace {

    private final String side;
    private final Map<Integer, TraceWindow> byHandle = new ConcurrentHashMap<>();
    private final Map<Class<?>, List<TraceWindow>> byType = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> memory = new ConcurrentHashMap<>();
    private int nextHandle = 1;

    public SideTrace(String side) {
        this.side = side;
    }

    // ---- which side ---------------------------------------------------------------------------

    /** The client's trace. Only ever called on a client code path; see {@link Client}. */
    public static SideTrace client() {
        return Client.get();
    }

    /** The trace of the server {@code server} is. */
    public static SideTrace server(MinecraftServer server) {
        return ((SideTraceOwner) server).arTest$sideTrace();
    }

    /** The trace of the side {@code world} belongs to. */
    public static SideTrace of(World world) {
        return world.isRemote ? client() : server(world.getMinecraftServer());
    }

    /** The trace of the side the calling thread runs, for an observation point with no world. */
    public static SideTrace here() {
        if (FMLCommonHandler.instance().getEffectiveSide() == Side.CLIENT) {
            return client();
        }
        return server(FMLCommonHandler.instance().getMinecraftServerInstance());
    }

    /**
     * The only reference to {@code Minecraft} in this class, in a class of its own: a dedicated
     * server has no {@code Minecraft}, and a nested class is loaded only when its method first runs,
     * which on a dedicated server is never.
     */
    private static final class Client {
        static SideTrace get() {
            return ((SideTraceOwner) net.minecraft.client.Minecraft.getMinecraft())
                    .arTest$sideTrace();
        }
    }

    // ---- windows ------------------------------------------------------------------------------

    /** Register a window a test just created and hand back its handle. */
    public synchronized int open(TraceWindow window) {
        int handle = nextHandle++;
        byHandle.put(handle, window);
        byType.computeIfAbsent(window.getClass(), k -> new CopyOnWriteArrayList<>()).add(window);
        return handle;
    }

    /** The open window under {@code handle}; throws when there is none, or it is another kind. */
    public <T extends TraceWindow> T window(int handle, Class<T> type) {
        TraceWindow w = byHandle.get(handle);
        if (!type.isInstance(w)) {
            throw new IllegalStateException(side + " has no open " + type.getSimpleName()
                    + " under handle " + handle + " (found " + (w == null ? "nothing"
                    : w.getClass().getSimpleName()) + ") — a window is read with the handle its own"
                    + " open() returned, in the scenario that opened it");
        }
        return type.cast(w);
    }

    /** Remove the window under {@code handle} and return it; throws like {@link #window}. */
    public synchronized <T extends TraceWindow> T close(int handle, Class<T> type) {
        T w = window(handle, type);
        byHandle.remove(handle);
        byType.get(type).remove(w);
        return w;
    }

    /**
     * Every open window of one kind, for the recorder feeding them. Read on every frame, so it
     * allocates nothing: the list is the live registry, and an index loop over it is the intended
     * reader.
     */
    @SuppressWarnings("unchecked")
    public <T extends TraceWindow> List<T> windows(Class<T> type) {
        List<TraceWindow> open = byType.get(type);
        return open == null ? Collections.<T>emptyList() : (List<T>) (List<?>) open;
    }

    /** {@link #discardAll} on the client — the static-invoke bridge's entry, which can only name a
     *  static method. Called by the shared client base before every scenario. */
    public static int discardClientWindows() {
        return client().discardAll();
    }

    /** {@link #discardAll} on the server this thread runs — the probe's {@code invoke-static}
     *  entry. Called by the shared client base before every scenario. */
    public static int discardServerWindows() {
        return here().discardAll();
    }

    /**
     * Drop every open window without recording its summary, and answer how many there were — the
     * release for a scenario that failed with a window open. Recorded when non-zero, so a leaked
     * window is visible in the log of the scenario that inherited it.
     */
    public synchronized int discardAll() {
        int n = byHandle.size();
        byHandle.clear();
        byType.clear();
        if (n > 0) {
            TestTrace.recordHere("trace_windows_discarded", "\"count\":" + n);
        }
        return n;
    }

    // ---- recorder memory ----------------------------------------------------------------------

    /** This side's one instance of a recorder's memory, created on first use. */
    public <T> T memory(Class<T> type, Supplier<T> create) {
        Object m = memory.get(type);
        if (m == null) {
            m = memory.computeIfAbsent(type, k -> create.get());
        }
        return type.cast(m);
    }
}
