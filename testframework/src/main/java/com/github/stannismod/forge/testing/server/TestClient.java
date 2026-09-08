package com.github.stannismod.forge.testing.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
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

    /**
     * Whether this client was promised a bridge — i.e. the harness opened a control port and waited
     * for the child to dial back.
     *
     * <p>When it is true the console channel is NOT a fallback, it is a failure. The two channels
     * are not equivalent: the console's reply is a slice of the server log, two concurrent calls can
     * steal each other's lines, and its completion sentinel is broadcast into every player's chat.
     * Degrading onto it silently means a broken bridge produces green runs, and every measurement
     * taken afterwards describes the channel nobody chose. Measured 2026-09-08: a run taken to
     * decide whether the bridge was live could not answer, because both states look the same from
     * outside.</p>
     */
    private volatile boolean bridgeRequired;

    /**
     * Declare that a bridge is expected, so its absence becomes an error instead of a quiet
     * downgrade. Called by the harness when — and only when — it has handed the child a control
     * port and intends to wait for it.
     */
    void requireBridge() {
        this.bridgeRequired = true;
    }

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
     * Run a server command and return its reply.
     *
     * <p>Two channels, and which one is used is a property of the SERVER, not of the caller:</p>
     * <ul>
     *   <li><b>the bridge</b>, when the server JVM carries a mod that started
     *       {@code ForgeTestServerBootstrap}. The reply is exactly the messages the command
     *       addressed to its sender — nothing is broadcast to chat, nothing is sliced out of the
     *       log, and concurrent calls cannot take each other's lines.</li>
     *   <li><b>the console</b> otherwise, unchanged: the command and a {@code say} sentinel go into
     *       the child's stdin and the reply is every transcript line in between. That sentinel IS
     *       broadcast to every connected player, which is why the bridge exists — but it is also the
     *       only completion signal available without one, so it stays.</li>
     * </ul>
     *
     * <p>The two replies are not identical: console lines carry the log's own
     * {@code [HH:MM:SS] [Server thread/INFO]:} prefix and include anything else the server printed
     * in the window, while bridge lines are the bare message text. A caller matching with
     * {@code contains} sees no difference; one matching whole lines does.</p>
     */
    public List<String> execute(String command) throws IOException, InterruptedException {
        List<String> overBridge = executeOverBridge(command);
        if (overBridge != null) {
            return overBridge;
        }
        if (bridgeRequired) {
            // A bridge was ASKED FOR and is not here. The console path below would work — that is
            // exactly the problem: it would work, quietly, on a degraded channel, and every run
            // afterwards would be evidence about the fallback rather than about the bridge. A
            // harness that requested a bridge and did not get one has failed to start, and says so
            // here rather than passing on a channel nobody chose.
            throw new IOException("the server control bridge was required and is not connected;"
                    + " refusing to fall back to the console channel, whose replies are log slices"
                    + " and whose completion sentinel is broadcast to chat. Set -D"
                    + com.github.stannismod.forge.testing.server.RealDedicatedServerHarness
                            .PROP_BRIDGE_WAIT_MILLIS
                    + "=0 to run a server that genuinely has no bridge.");
        }
        String marker = "FORGE_TEST_DONE " + UUID.randomUUID();
        int startIndex = snapshotSize();
        sendRaw(command);
        sendRaw("say " + marker);
        // Load-scaled: under concurrent forks a starved server thread stretches command latency.
        return awaitMarker(startIndex, marker,
                com.github.stannismod.forge.testing.TestTimeouts.scaled(Duration.ofSeconds(30)));
    }

    /**
     * One request/response exchange over the bridge, or {@code null} when no bridge is connected —
     * which is the ONLY silent path here, and it means the caller falls back to the console.
     *
     * <p>A bridge that is connected but fails mid-exchange is never downgraded silently: the socket
     * is torn down, the demotion is printed, and this call fails. Returning console lines from a
     * broken bridge would make a degraded channel indistinguishable from a healthy one.</p>
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

    public List<String> awaitOutputContaining(String token, Duration timeout) throws InterruptedException {
        int startIndex = snapshotSize();
        return awaitMarker(startIndex, token, timeout);
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

    private int snapshotSize() {
        synchronized (transcript) {
            return transcript.size();
        }
    }

    private List<String> awaitMarker(int startIndex, String token, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        int index = startIndex;
        List<String> captured = new ArrayList<>();

        while (System.nanoTime() < deadlineNanos) {
            String line = null;
            synchronized (transcript) {
                if (index < transcript.size()) {
                    line = transcript.get(index++);
                    captured.add(line);
                    if (line.contains(token)) {
                        captured.remove(captured.size() - 1);
                        return captured;
                    }
                } else {
                    // Short-circuit: if the underlying process died before printing the
                    // marker, no amount of waiting will help. Return the captured tail
                    // immediately so callers see the actual crash instead of a timeout.
                    if (!process.isAlive()) {
                        throw new AssertionError("Server process exited (code=" + process.exitValue()
                                + ") before marker '" + token + "' appeared. Recent output: " + tail());
                    }
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    long waitMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                    transcript.wait(Math.min(waitMillis, 250L));
                    continue;
                }
            }
        }

        throw new AssertionError("Timed out waiting for marker '" + token + "'. Recent output: " + tail());
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

