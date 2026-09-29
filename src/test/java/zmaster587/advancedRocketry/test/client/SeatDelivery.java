package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.client.ClientBot;

import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.ServerWindow;

/**
 * The pilot-input delivery chain's two halves — the server's gates ({@code SeatDeliveryWindow}) and
 * the client's gate and sends ({@code SeatGateWindow}) — as ONE object a scenario opens and reads.
 *
 * <p>Diagnostic only: every reader prints {@link #reading()} into a failure message and none asserts
 * on it. What makes it worth having is that its numbers belong to the window that opened them — the
 * store it replaces was cumulative for the JVM's life, so a red in the fourteenth scenario of a shared
 * class printed the totals of all fourteen, and nothing ever went red on that.</p>
 */
public final class SeatDelivery {

    private static final String SERVER_WINDOW =
            "zmaster587.advancedRocketry.test.trace.SeatDeliveryWindow";
    private static final String CLIENT_WINDOW =
            "zmaster587.advancedRocketry.test.trace.SeatGateWindow";

    private final ServerWindow server;
    private final ClientWindow client;
    private final Events serverLog;
    private final Events clientLog;

    private SeatDelivery(ServerWindow server, ClientWindow client, Events serverLog,
                         Events clientLog) {
        this.server = server;
        this.client = client;
        this.serverLog = serverLog;
        this.clientLog = clientLog;
    }

    /** Open both halves now: from here on their counts are this reader's. */
    public static SeatDelivery open(ServerWindow.Probe probe, ClientBot bot, Events serverLog,
                                    Events clientLog) throws Exception {
        return new SeatDelivery(ServerWindow.open(probe, SERVER_WINDOW),
                ClientWindow.open(bot, CLIENT_WINDOW), serverLog, clientLog);
    }

    /**
     * Both halves as they stand, for a failure message. Never throws: it is read on a path that is
     * already failing, and a diagnostic that replaces the failure it was asked to explain is worse
     * than none — an unreadable half says so in its place.
     */
    public String reading() {
        return "{server:" + half(true) + ", client:" + half(false) + "}";
    }

    /**
     * The SERVER half alone — the {@code seat_delivery_window} record itself, whose fields
     * ({@code received}, {@code delivered}, …) a reader takes by name — or a parenthesised note when
     * it could not be read. {@link #reading()} nests both halves in one string, and a field of a
     * nested member is not readable off it by name.
     */
    public String server() {
        return half(true);
    }

    private String half(boolean serverSide) {
        try {
            Events log = serverSide ? serverLog : clientLog;
            long mark = log.mark();
            if (serverSide) {
                server.peek();
            } else {
                client.peek();
            }
            String rec = Events.lastRecord(log.since(mark,
                    serverSide ? "seat_delivery_window" : "seat_gate_window"));
            return rec == null ? "(no record after a peek)" : rec;
        } catch (Throwable t) {
            return "(unreadable: " + t + ")";
        }
    }
}
