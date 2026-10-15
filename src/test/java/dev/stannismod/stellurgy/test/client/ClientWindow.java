package dev.stannismod.stellurgy.test.client;

import com.github.stannismod.forge.testing.client.ClientReads;

/**
 * The TEST's end of a trace window it opened on the client: the handle the client's factory returned,
 * held by the scenario that asked for it.
 *
 * <p>This object is what "a trace object per test" means on this side of the socket. The window
 * itself lives on the client (the frames it counts are drawn there, and a socket cannot poll a frame
 * history); this is how the scenario addresses THAT window and no other. A second scenario opening
 * the same kind gets a different handle and a different window.</p>
 *
 * <p>{@link #close()} is idempotent HERE, on the test's own object — several failure paths close a
 * window that the happy path also closes. On the client a stale handle is a loud error, which is
 * exactly why the idempotence lives on this side and not there.</p>
 */
public final class ClientWindow {

    private final ClientReads bot;
    private final String windowClass;
    private final int handle;
    private boolean closed;

    private ClientWindow(ClientReads bot, String windowClass, int handle) {
        this.bot = bot;
        this.windowClass = windowClass;
        this.handle = handle;
    }

    /** The only package a window may come from: the test source set's own recorders. */
    private static final String TRACE_PACKAGE = "dev.stannismod.stellurgy.test.trace.";

    /**
     * Open a window of {@code windowClass} on the client, passing its factory's own arguments.
     *
     * <p>Refuses a class outside {@link #TRACE_PACKAGE}: {@code invokeStaticInt} calls whatever it is
     * handed, so a window handle is how a test could otherwise reach a decision of the mod by
     * reflection. (A client in e2e mode refuses such a call itself unless its instrument table names the
     * window's method — the bridge's {@code ClientInstruments}.)</p>
     */
    public static ClientWindow open(ClientReads bot, String windowClass, int... args) throws Exception {
        if (!windowClass.startsWith(TRACE_PACKAGE)) {
            throw new IllegalArgumentException("a client window must be a test recorder under "
                    + TRACE_PACKAGE + ", not " + windowClass);
        }
        String returned = bot.invokeStaticInt(windowClass, "open", args).get("returned").getAsString();
        return new ClientWindow(bot, windowClass, Integer.parseInt(returned));
    }

    /** Write the window's numbers as they stand, without ending it. */
    public void peek() throws Exception {
        bot.invokeStaticInt(windowClass, "peek", handle);
    }

    /**
     * Call a verb of the window's own kind on THIS window — a static {@code verb(int handle)} beside
     * {@code peek} and {@code close}, for a kind with a lifecycle step of its own
     * ({@code ShipLoadDecisionHold.release}). Answers what the verb returned, as text.
     */
    public String call(String verb) throws Exception {
        return bot.invokeStaticInt(windowClass, verb, handle).get("returned").getAsString();
    }

    /** End the window and write its summary; a second call does nothing. */
    public void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        bot.invokeStaticInt(windowClass, "close", handle);
    }

    public boolean isOpen() {
        return !closed;
    }
}
