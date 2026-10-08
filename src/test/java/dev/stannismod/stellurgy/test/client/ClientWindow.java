package dev.stannismod.stellurgy.test.client;

import com.github.stannismod.forge.testing.client.ClientBot;

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

    private final ClientBot bot;
    private final String windowClass;
    private final int handle;
    private boolean closed;

    private ClientWindow(ClientBot bot, String windowClass, int handle) {
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
     * handed, so a window handle is how an e2e could otherwise reach a decision of the mod by
     * reflection. The e2e census admits this method on that guard.</p>
     */
    public static ClientWindow open(ClientBot bot, String windowClass, int... args) throws Exception {
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
