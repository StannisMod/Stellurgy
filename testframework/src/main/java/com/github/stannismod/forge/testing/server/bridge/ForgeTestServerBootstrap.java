package com.github.stannismod.forge.testing.server.bridge;

import com.github.stannismod.forge.testing.TestTimeouts;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.command.CommandResultStats;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The in-JVM half of the SERVER control bridge — the mirror of
 * {@code ForgeTestClientBootstrap} for a dedicated server child.
 *
 * <p>It exists so the harness can run a command and read that command's OWN reply. The console
 * channel it replaces has no request/response framing, so a "reply" there is every line the server
 * logged between the command and a {@code say} sentinel: it mixes in whatever else the server
 * printed in that window, two concurrent calls can take each other's lines, and the sentinel itself
 * is broadcast into every connected player's chat — the instrument writing into the channel tests
 * read. Here the command is executed with a sender of this bridge's own
 * ({@link CollectingCommandSender}), so the reply is exactly the messages that command addressed to
 * its sender, and nothing else.</p>
 *
 * <p>Protocol, line-delimited JSON, one object per line, identical in shape to the client bridge:
 * the child writes the literal line {@code READY} once on connect; a request is
 * {@code {"command":"artest events since 12"}}; a response is {@code {"ok":true,"lines":[…]}} or
 * {@code {"ok":false,"error":"…"}}.</p>
 *
 * <p>What it is silent about: it reports only what the command sent to its sender. Anything the
 * command wrote to the LOG instead (a mod's own {@code logger.info}) is not in {@code lines} — read
 * the preserved server log for that. It also carries no world state of its own, exposes no verb
 * except {@code command}, and does not stop the server (the harness still does that over stdin).</p>
 */
public final class ForgeTestServerBootstrap {

    /** System property carrying the harness's control port. Absent ⇒ this class does nothing. */
    public static final String PROP_PORT = "forge.test.server.port";

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private ForgeTestServerBootstrap() {
    }

    /**
     * Start the bridge thread, once per JVM.
     *
     * <p>Called reflectively from the consuming mod's {@code FMLServerStartingEvent} handler, which
     * is the first point at which {@code FMLCommonHandler.getMinecraftServerInstance()} is
     * guaranteed non-null. A no-op when {@link #PROP_PORT} is unset, so a production launch that
     * somehow carries this class still pays nothing.</p>
     */
    public static void bootstrap() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        Integer port = Integer.getInteger(PROP_PORT);
        if (port == null || port.intValue() <= 0) {
            return;
        }
        Thread bridgeThread = new Thread(ForgeTestServerBootstrap::runBridge, "forge-test-server-bridge");
        bridgeThread.setDaemon(true);
        bridgeThread.start();
    }

    /**
     * Connect to the harness, announce {@code READY}, then answer one request per line until the
     * harness closes the connection.
     *
     * <p>Every failure is contained here: a malformed line, a command that throws, a server that
     * never drains its task queue — each becomes an {@code ok:false} response rather than a silent
     * stall on the harness side. Only an I/O failure on the socket itself ends the loop, and then
     * the harness's next request fails loudly instead of blocking forever.</p>
     */
    private static void runBridge() {
        int port = Integer.getInteger(PROP_PORT, 0).intValue();
        Socket socket = null;
        try {
            socket = connectWithRetry(port);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            writer.write("READY");
            writer.newLine();
            writer.flush();

            String line;
            while ((line = reader.readLine()) != null) {
                JsonObject response;
                try {
                    JsonElement parsed = new JsonParser().parse(line);
                    if (!parsed.isJsonObject()) {
                        response = error("Malformed command payload");
                    } else {
                        response = handleRequest(parsed.getAsJsonObject());
                    }
                } catch (RuntimeException exception) {
                    response = error(exception.getMessage() == null
                            ? exception.toString() : exception.getMessage());
                }

                writer.write(response.toString());
                writer.newLine();
                writer.flush();
            }
        } catch (IOException exception) {
            exception.printStackTrace();
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Nothing left to do.
                }
            }
        }
    }

    /**
     * Dial the harness's already-bound control port, retrying while it is still starting up.
     *
     * <p>Load-scaled like the client half: under concurrent forks the test JVM that must accept the
     * connection is itself contended.</p>
     */
    private static Socket connectWithRetry(int port) throws IOException {
        IOException last = null;
        long deadline = System.nanoTime() + TestTimeouts.scaledNanos(TimeUnit.MINUTES.toNanos(2));

        while (System.nanoTime() < deadline) {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                return socket;
            } catch (IOException exception) {
                last = exception;
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for the server test bridge socket",
                            interruptedException);
                }
            }
        }

        throw new IOException("Timed out connecting the Forge test server bridge", last);
    }

    /** Dispatch one parsed request object. The only verb is {@code command}. */
    private static JsonObject handleRequest(JsonObject request) {
        if (!request.has("command")) {
            return error("Missing required key: command");
        }
        return executeCommand(request.get("command").getAsString());
    }

    /**
     * Run one command on the SERVER THREAD and return what it said to its sender.
     *
     * <p>The command is scheduled through {@link MinecraftServer#addScheduledTask(Runnable)} — never
     * executed on this socket thread, where it would touch world state concurrently with the tick
     * loop — and waited for with a ceiling. A server that has stopped draining its task queue
     * therefore answers {@code ok:false} instead of hanging the test JVM on a read that will never
     * be satisfied. On timeout the task is cancelled, so a wedged server that later recovers does
     * not execute a command whose caller has already given up.</p>
     *
     * <p>A command that FAILS is still {@code ok:true}: vanilla's command handler reports a
     * {@code CommandException} by messaging the sender, so the failure text arrives as a captured
     * line exactly as it would on the console. {@code ok:false} means the bridge could not run the
     * command at all.</p>
     *
     * <p>What it is silent about: once the server is STOPPED,
     * {@code MinecraftServer.callFromMainThread} runs the task inline on the caller's thread rather
     * than queueing it, so a command issued during shutdown executes on this socket thread. That is
     * vanilla's behaviour for every scheduled task and not something this bridge can override; a
     * caller sending commands after {@code stop} is already outside the guarantee.</p>
     */
    private static JsonObject executeCommand(final String command) {
        final MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return error("No MinecraftServer instance yet");
        }

        final CollectingCommandSender sender = new CollectingCommandSender(server);
        Callable<Integer> call = () -> Integer.valueOf(
                server.getCommandManager().executeCommand(sender, command));
        FutureTask<Integer> task = new FutureTask<Integer>(call);
        server.addScheduledTask(task);

        try {
            task.get(TestTimeouts.scaledMillis(TimeUnit.SECONDS.toMillis(30)), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            task.cancel(false);
            return error("Timed out waiting for the server thread to run: " + command);
        } catch (InterruptedException interrupted) {
            task.cancel(false);
            Thread.currentThread().interrupt();
            return error("Interrupted while running: " + command);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            return error("Command threw: " + cause);
        }

        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        JsonArray lines = new JsonArray();
        for (String captured : sender.captured()) {
            lines.add(captured);
        }
        response.add("lines", lines);
        return response;
    }

    private static JsonObject error(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", false);
        response.addProperty("error", message == null ? "unknown" : message);
        return response;
    }

    /**
     * An {@link ICommandSender} whose "chat" is a list — one per request, so two concurrent requests
     * cannot take each other's lines.
     *
     * <p>Modelled on vanilla's own {@code RConConsoleSource}, which exists for the same reason (a
     * command run for a remote caller, replying to that caller and not to the world): op-level
     * permission, the server's overworld as its world, feedback enabled. Nothing here writes to
     * chat, to the log, or to any player.</p>
     *
     * <p>What it is silent about: position. Like the console sender it leaves
     * {@link ICommandSender#getPosition()} at {@link BlockPos#ORIGIN}, so a relative coordinate
     * ({@code ~}) resolves exactly as it does when the command is typed into the server console —
     * which is a deliberate mirror, not an oversight, and means a caller that wants a position must
     * pass absolute coordinates.</p>
     */
    private static final class CollectingCommandSender implements ICommandSender {

        private final MinecraftServer server;
        private final List<String> lines = new ArrayList<String>();

        CollectingCommandSender(MinecraftServer server) {
            this.server = server;
        }

        /** The captured reply, in the order the command produced it. */
        List<String> captured() {
            synchronized (lines) {
                return new ArrayList<String>(lines);
            }
        }

        @Override
        public String getName() {
            return "ForgeTestBridge";
        }

        @Override
        public ITextComponent getDisplayName() {
            return new TextComponentString(getName());
        }

        /**
         * THE seam this whole bridge exists for: a reply addressed to the sender lands in a list
         * owned by one request instead of in the server log and every player's chat.
         *
         * <p>Synchronized because a command may hand work to another thread that replies late; the
         * list is read after the scheduled task completes, so anything arriving after that is lost
         * rather than corrupting the reply.</p>
         */
        @Override
        public void sendMessage(ITextComponent component) {
            String text = component == null ? "" : component.getUnformattedText();
            synchronized (lines) {
                lines.add(text);
            }
        }

        @Override
        public boolean canUseCommand(int permLevel, String commandName) {
            return true;
        }

        @Override
        public BlockPos getPosition() {
            return BlockPos.ORIGIN;
        }

        @Override
        public Vec3d getPositionVector() {
            return Vec3d.ZERO;
        }

        @Override
        public World getEntityWorld() {
            return server.getEntityWorld();
        }

        @Override
        public Entity getCommandSenderEntity() {
            return null;
        }

        /** True, like RCon's: a caller asking for a command's output wants its feedback too. */
        @Override
        public boolean sendCommandFeedback() {
            return true;
        }

        @Override
        public void setCommandStat(CommandResultStats.Type type, int amount) {
            // Command stats are a command-block concept; nothing here reads them back.
        }

        @Override
        public MinecraftServer getServer() {
            return server;
        }
    }
}
