package com.github.stannismod.forge.testing.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class TestClient implements Closeable {

    private final Process process;
    private final Writer stdin;
    private final List<String> transcript;

    /**
     * The control bridge into the server JVM, once its in-JVM half has connected, or {@code null}
     * while it has not. Guarded by {@link #bridgeLock} for the whole request/response exchange:
     * a line-framed protocol has no request ids, so two interleaved exchanges would read each
     * other's replies — the very defect the console channel has and this bridge exists to remove.
     */
    private Socket bridgeSocket;
    private BufferedReader bridgeReader;
    private BufferedWriter bridgeWriter;
    private final Object bridgeLock = new Object();

    TestClient(Process process, Writer stdin, List<String> transcript) {
        this.process = process;
        this.stdin = stdin;
        this.transcript = transcript;
    }

    /**
     * Adopt the accepted control connection to the server JVM's bridge; from here on
     * {@link #execute(String)} goes over it.
     *
     * <p>The caller is {@code RealDedicatedServerHarness}, which has already read the child's
     * {@code READY} line, so the socket handed here is live and idle.</p>
     *
     * <p>A read ceiling is installed here rather than left to the caller: the in-JVM half answers
     * a wedged server with {@code ok:false} inside its own 30 s ceiling, so a read that outlasts
     * DOUBLE that means the bridge thread itself is gone, and only a socket timeout turns that into
     * a failed test instead of a hung suite.</p>
     */
    void attachBridge(Socket socket, BufferedReader reader, BufferedWriter writer) throws IOException {
        socket.setSoTimeout(com.github.stannismod.forge.testing.TestTimeouts
                .scaledMillis(TimeUnit.SECONDS.toMillis(60)));
        synchronized (bridgeLock) {
            this.bridgeSocket = socket;
            this.bridgeReader = reader;
            this.bridgeWriter = writer;
        }
    }

    /** Whether a bridge is connected — i.e. whether {@link #execute(String)} is on the socket path. */
    public boolean hasBridge() {
        synchronized (bridgeLock) {
            return bridgeSocket != null;
        }
    }

    /**
     * Run a server command and return its reply — over the control bridge, which is the only channel.
     *
     * <p>The reply is exactly the messages the command addressed to its sender: nothing is broadcast
     * to chat, nothing is sliced out of the log, and two concurrent calls cannot take each other's
     * lines.</p>
     *
     * <p><b>There is deliberately no second channel.</b> There used to be: the command and a
     * {@code say} sentinel went into the child's stdin and the reply was every transcript line in
     * between — a sentinel broadcast to every connected player, a reply that was a time slice of the
     * whole server log, and two callers able to steal each other's lines. It was kept as a fallback
     * "for a server with no bridge", and no such server exists: every server this harness starts
     * carries the mod that opens the bridge, and nothing in the tree ever selected the other path.
     * A branch no caller reaches is not a fallback, and keeping it kept all four defects alive
     * behind a flag nobody set.</p>
     */
    public List<String> execute(String command) throws IOException, InterruptedException {
        List<String> overBridge = executeOverBridge(command);
        if (overBridge != null) {
            return overBridge;
        }
        throw new IOException("the server control bridge is not connected, and it is the only command"
                + " channel. The server child starts it from its own test-mode registration, so its"
                + " absence means the child never reached that point or the connection was lost -"
                + " both of which are failures to report, not conditions to work around.");
    }

    /**
     * One request/response exchange over the bridge, or {@code null} when none is connected — which
     * {@link #execute(String)} turns into a failure, since there is no other channel to fall to.
     *
     * <p>A bridge that is connected but fails mid-exchange is torn down and the call fails: a broken
     * channel must not be indistinguishable from a healthy one.</p>
     */
    private List<String> executeOverBridge(String command) {
        Objects.requireNonNull(command, "command");
        synchronized (bridgeLock) {
            if (bridgeSocket == null) {
                return null;
            }
            JsonObject request = new JsonObject();
            request.addProperty("command", command);
            try {
                bridgeWriter.write(request.toString());
                bridgeWriter.newLine();
                bridgeWriter.flush();

                String line = bridgeReader.readLine();
                if (line == null) {
                    throw new IOException("Server bridge closed the connection");
                }
                JsonElement parsed = new JsonParser().parse(line);
                if (!parsed.isJsonObject()) {
                    throw new IOException("Malformed bridge response: " + line);
                }
                JsonObject response = parsed.getAsJsonObject();
                if (!response.has("ok") || !response.get("ok").getAsBoolean()) {
                    throw new AssertionError("Server command failed over the bridge: " + command
                            + " -> " + (response.has("error")
                                    ? response.get("error").getAsString() : line));
                }
                List<String> lines = new ArrayList<>();
                if (response.has("lines")) {
                    JsonArray array = response.getAsJsonArray("lines");
                    for (int i = 0; i < array.size(); i++) {
                        lines.add(array.get(i).getAsString());
                    }
                }
                return lines;
            } catch (IOException | RuntimeException failure) {
                closeBridgeLocked();
                System.out.println("[forge-test] server bridge lost (" + failure
                        + ") — later commands fall back to the console channel, whose replies are"
                        + " log slices and whose sentinel is broadcast to chat");
                throw new AssertionError("Server bridge exchange failed for: " + command, failure);
            }
        }
    }

    /** Close the bridge streams and forget them. Caller holds {@link #bridgeLock}. */
    private void closeBridgeLocked() {
        Socket socket = bridgeSocket;
        bridgeSocket = null;
        bridgeReader = null;
        bridgeWriter = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Nothing left to do.
            }
        }
    }

    public void sendRaw(String command) throws IOException {
        Objects.requireNonNull(command, "command");
        synchronized (stdin) {
            stdin.write(command);
            stdin.write('\n');
            stdin.flush();
        }
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() throws IOException {
        // The bridge goes first and unconditionally: it is a live socket even when the child has
        // already exited, and a leaked one keeps a thread parked in every fork of a long run.
        synchronized (bridgeLock) {
            closeBridgeLocked();
        }
        if (!process.isAlive()) {
            return;
        }
        try {
            sendRaw("stop");
        } catch (IOException ignored) {
            // If stdin is already closed, fall through and destroy the process.
        }
        try {
            process.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (stdin) {
                stdin.close();
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    List<String> transcriptSnapshot() {
        synchronized (transcript) {
            return new ArrayList<>(transcript);
        }
    }

    private String tail() {
        List<String> snapshot = transcriptSnapshot();
        int from = Math.max(0, snapshot.size() - 25);
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < snapshot.size(); i++) {
            if (i > from) {
                builder.append(System.lineSeparator());
            }
            builder.append(snapshot.get(i));
        }
        return builder.toString();
    }

    static BufferedWriter newWriter(Process process) {
        return new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    }
}

