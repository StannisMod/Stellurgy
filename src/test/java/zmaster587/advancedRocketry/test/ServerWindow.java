package zmaster587.advancedRocketry.test;

/**
 * The TEST's end of a trace window it opened on the SERVER: the handle the server-side factory
 * returned, held by the scenario that asked. The server-side twin of the client's
 * {@code ClientWindow}, over the probe's {@code artest invoke-static} verb.
 *
 * <p>{@link #close()} is idempotent here, on the test's own object; on the server a stale handle is
 * a loud error.</p>
 */
public final class ServerWindow {

    /** Whatever sends a probe command to the server and returns its reply. */
    public interface Probe {
        String exec(String command) throws Exception;
    }

    private final Probe probe;
    private final String windowClass;
    private final int handle;
    private boolean closed;

    private ServerWindow(Probe probe, String windowClass, int handle) {
        this.probe = probe;
        this.windowClass = windowClass;
        this.handle = handle;
    }

    /** Open a window of {@code windowClass} on the server, passing its factory's own arguments. */
    public static ServerWindow open(Probe probe, String windowClass, int... args) throws Exception {
        StringBuilder cmd = new StringBuilder("artest invoke-static ").append(windowClass)
                .append(" open");
        for (int a : args) {
            cmd.append(' ').append(a);
        }
        Reply reply = Reply.of(probe.exec(cmd.toString()))
                .requireOk("opening a server-side " + windowClass);
        return new ServerWindow(probe, windowClass, Integer.parseInt(reply.text("returned").trim()));
    }

    /** Write the window's numbers as they stand, without ending it. */
    public void peek() throws Exception {
        Reply.of(probe.exec("artest invoke-static " + windowClass + " peek " + handle))
                .requireOk("peeking a server-side " + windowClass);
    }

    /** End the window and write its summary; a second call does nothing. */
    public void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        Reply.of(probe.exec("artest invoke-static " + windowClass + " close " + handle))
                .requireOk("closing a server-side " + windowClass);
    }
}
