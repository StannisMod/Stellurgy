package com.github.stannismod.forge.testing.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * The command channel to one real client's bridge: one request, one reply, in order.
 *
 * <p>Every typed view of the client — {@link ClientBot}, {@link PlayerInput}, the reads — sends through
 * one of these, so what a view CAN send is decided by which view a test holds, and what the client
 * ANSWERS is decided by the client (in e2e mode it refuses what its kind does not admit, whatever view
 * sent it).</p>
 */
final class ClientChannel implements Closeable {

    private final Socket socket;
    private final BufferedReader reader;
    private final BufferedWriter writer;

    /**
     * What the channel was last asked, when it last ANSWERED, and how many answers it has given.
     *
     * <p>These exist for one purpose: a read that times out must be able to say something about the
     * SUBJECT. Measured 2026-09-21 — a full-tier run lost a test to a bare
     * {@code SocketTimeoutException} whose stack named {@code SocketInputStream.read} and nothing
     * else, which is indistinguishable from the outside between a busy box and a client wedged by
     * the very thing under test. Every occurrence then costs a triage that cannot conclude.</p>
     *
     * <p>Written and read only under the {@code writer} monitor or immediately around the read that
     * follows it, on the one thread that owns this channel.</p>
     */
    private volatile String lastCommandSent = "(none yet)";
    private volatile long lastAnswerNanos = System.nanoTime();
    private volatile long answersReceived;

    ClientChannel(Socket socket) throws IOException {
        this.socket = socket;
        this.socket.setTcpNoDelay(true);
        this.socket.setSoTimeout((int) ClientBot.READ_TIMEOUT_MILLIS);
        this.reader = new BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        awaitReady(Duration.ofMinutes(2));
    }

    /** A request naming {@code command}, to which a view adds its arguments. */
    static JsonObject command(String command) {
        JsonObject object = new JsonObject();
        object.addProperty("command", Objects.requireNonNull(command, "command"));
        return object;
    }

    /**
     * Whether the head has turned since the crosshair's pick was last asked: set by a {@code set_look} or a
     * {@code turn_look} this channel carried, cleared by a {@code report_mouse_over}. Both the input and the
     * reads talk through this one channel, so this is where a look and its confirmation meet. Lives with
     * the channel, which lives with its client connection.
     */
    private volatile boolean lookUnconfirmed;

    /** Send {@code command} and answer its reply, which must be {@code ok}. */
    JsonObject call(JsonObject command) throws IOException {
        JsonObject reply = assertOk(execute(command));
        String name = command.has("command") ? command.get("command").getAsString() : "";
        if ("set_look".equals(name) || "turn_look".equals(name)) {
            lookUnconfirmed = true;
        } else if ("report_mouse_over".equals(name)) {
            lookUnconfirmed = false;
        }
        return reply;
    }

    /** Whether the head turned after the crosshair's pick was last read -- see {@link #lookUnconfirmed}. */
    boolean lookUnconfirmed() {
        return lookUnconfirmed;
    }

    /** Send {@code command} and answer its reply as it came, {@code ok} or not. */
    JsonObject execute(JsonObject command) throws IOException {
        String sent = command.toString();
        synchronized (writer) {
            lastCommandSent = sent;
            writer.write(sent);
            writer.newLine();
            writer.flush();
        }

        String line;
        try {
            line = reader.readLine();
        } catch (java.net.SocketTimeoutException timedOut) {
            throw new IOException(describeTimeout(sent), timedOut);
        }
        if (line == null) {
            throw new IOException("Client bridge closed unexpectedly after " + answersReceived
                    + " answered command(s); the last one asked was " + abbreviate(sent), null);
        }
        lastAnswerNanos = System.nanoTime();
        answersReceived++;

        JsonElement parsed = new JsonParser().parse(line);
        if (!parsed.isJsonObject()) {
            throw new IOException("Malformed client bridge response: " + line);
        }
        return parsed.getAsJsonObject();
    }

    /**
     * Is the client still answering? Answers within {@code timeoutMillis} whatever the client does — see
     * {@link ClientBot#isAlive(int)}.
     */
    boolean isAlive(int timeoutMillis) {
        int previous;
        try {
            previous = socket.getSoTimeout();
        } catch (Throwable alreadyGone) {
            return false;
        }
        try {
            socket.setSoTimeout(Math.max(1, timeoutMillis));
            execute(command("report_state"));
            return true;
        } catch (Throwable dead) {
            return false;
        } finally {
            try {
                socket.setSoTimeout(previous);
            } catch (Throwable ignored) {
                // The socket is gone; the caller already has its answer.
            }
        }
    }

    boolean isOpen() {
        return socket.isConnected() && !socket.isClosed();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    /**
     * What to say when the command channel stops answering.
     *
     * <p><b>It names the subject, because the exception cannot.</b> A bare
     * {@code SocketTimeoutException} says only that a socket read expired: the same stack is
     * produced by a loaded box, by a client that crashed mid-command, and by a client wedged by the
     * behaviour the test is about. This prints what distinguishes them — the command that was
     * outstanding, how long this read was allowed, how long the channel had been answering happily
     * before it, and how many commands it had answered.</p>
     *
     * <p><b>Nothing is probed here, deliberately.</b> {@link #isAlive} would write to the same
     * channel and read the next line — and after a timeout the next line may be the LATE reply to
     * the command that just expired, which the probe would then read as its own answer. So the
     * diagnosis is built from what is already known, and the caller is told plainly that the
     * channel is now out of step and must not be reused.</p>
     */
    private String describeTimeout(String sent) {
        long idleMillis = (System.nanoTime() - lastAnswerNanos) / 1_000_000L;
        int budgetMillis;
        try {
            budgetMillis = socket.getSoTimeout();
        } catch (IOException unreadable) {
            budgetMillis = -1;
        }
        // idleMillis is taken HERE, at the timeout, so it CONTAINS this wait. Saying "before the
        // read began" would overstate the silence by the whole budget — measured 2026-09-22 on a
        // real timeout that printed 120012 ms against a 120000 ms budget, i.e. the channel had in
        // fact answered 12 ms before the command went out and was healthy until it.
        return "the client bridge did not answer within " + budgetMillis + " ms."
                + " Outstanding command: " + abbreviate(sent) + "."
                + " It had answered " + answersReceived + " command(s), and had been silent for "
                + idleMillis + " ms when this read gave up — that figure INCLUDES this wait, so"
                + " subtract the budget to see how long the channel had been quiet before the"
                + " command went out (a small remainder means it was healthy until this one)."
                + " THE CHANNEL IS NOW OUT OF STEP: a late reply to this command would be read as"
                + " the answer to the next one, so this bot must not be reused."
                + " Three things produce this and they want different fixes — a client killed or"
                + " crashed mid-command (look for its process and its log tail), a client wedged by"
                + " the behaviour under test (the outstanding command above says which), and a box"
                + " so loaded that " + budgetMillis + " ms was not enough (the answered count and"
                + " the idle time above say whether it had been keeping up).";
    }

    /** A command line, short enough to read in a stack trace and long enough to name the verb. */
    private static String abbreviate(String line) {
        return line.length() <= 300 ? line : line.substring(0, 300) + "… (" + line.length() + " chars)";
    }

    /**
     * The reply, when it is {@code ok}. Otherwise an {@link IOException} naming the bridge's error — and,
     * when the client REFUSED the command in e2e mode, a {@link ClientCommandRefused} carrying the reply,
     * so a caller tells that refusal from any other failure by its fields, not its text.
     */
    private JsonObject assertOk(JsonObject response) throws IOException {
        if (!response.has("ok") || !response.get("ok").getAsBoolean()) {
            String message = response.has("error") ? response.get("error").getAsString() : "unknown client bridge error";
            if (response.has("refused") && response.get("refused").getAsBoolean()) {
                throw new ClientCommandRefused(message, response);
            }
            throw new IOException(message);
        }
        return response;
    }

    /**
     * Wait for the client bridge's {@code READY}.
     *
     * <p>{@code timeout} is APPLIED, and used to be ignored: the parameter was accepted and the
     * read ran on whatever the socket's own timeout happened to be, so a caller asking for thirty
     * seconds waited two minutes. The old code also reported a non-{@code READY} first line as
     * "Timed out waiting for readiness", which is a different fault wearing the timeout's name —
     * the two are separated here.</p>
     */
    private void awaitReady(Duration timeout) throws IOException {
        int previous = socket.getSoTimeout();
        String line;
        try {
            socket.setSoTimeout((int) Math.max(1L, timeout.toMillis()));
            line = reader.readLine();
        } catch (java.net.SocketTimeoutException timedOut) {
            throw new IOException("the client bridge never signalled READY within "
                    + timeout.toMillis() + " ms. Nothing has been asked of it yet, so this is the"
                    + " client failing to come up rather than a command wedging it: look at the"
                    + " client's own log for how far its start got.", timedOut);
        } finally {
            socket.setSoTimeout(previous);
        }
        if (line == null) {
            throw new IOException("Client bridge disconnected before signaling readiness");
        }
        if ("READY".equals(line)) {
            return;
        }
        throw new IOException("the client bridge's first line was not READY but "
                + abbreviate(line) + " — the channel is speaking, and saying something else");
    }
}
