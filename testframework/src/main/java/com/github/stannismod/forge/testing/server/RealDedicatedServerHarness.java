package com.github.stannismod.forge.testing.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class RealDedicatedServerHarness implements AutoCloseable {

    private final Path root;
    private final int port;
    private final TestClient client;
    private final Thread readerThread;
    private final boolean cleanupOnClose;
    /**
     * The listening socket the server child's control bridge dials back on. Held for the life of
     * the harness rather than closed after the accept, so a child whose bridge starts late (a mod
     * whose server-starting handler runs after the ready marker) still finds the port open instead
     * of failing its connect with a stack trace nobody reads.
     */
    private final java.net.ServerSocket controlSocket;

    private RealDedicatedServerHarness(Path root, int port, TestClient client, Thread readerThread,
                                       boolean cleanupOnClose, java.net.ServerSocket controlSocket) {
        this.root = root;
        this.port = port;
        this.client = client;
        this.readerThread = readerThread;
        this.cleanupOnClose = cleanupOnClose;
        this.controlSocket = controlSocket;
    }

    /**
     * Starts a fresh server in a temporary work directory. The directory is
     * deleted recursively when {@link #close()} is called — use this for
     * scenarios that don't need to inspect or reuse the world after close.
     */
    public static RealDedicatedServerHarness start() throws IOException, InterruptedException {
        Path root = Files.createTempDirectory("forge-dedicated-server-");
        return startInternal(root, /*bootstrap=*/true, /*cleanupOnClose=*/true);
    }

    /**
     * Starts a server using the supplied work directory.
     *
     * <p>Useful for persistence-restart scenarios: start a fresh server, mutate
     * world state, close it, then start again with the same {@code root} to
     * verify the state survived save/load.</p>
     *
     * @param root           directory to use as the server's gameDir / world root.
     *                       If empty, framework files (eula.txt, server.properties)
     *                       are bootstrapped automatically. If it contains a
     *                       {@code server.properties} from a previous run, the
     *                       file is rewritten with a fresh port; the rest of the
     *                       directory (world, config, mods) is preserved.
     * @param cleanupOnClose if {@code true}, recursively deletes {@code root} on
     *                       {@link #close()}. Pass {@code false} when you intend
     *                       to restart against the same dir.
     */
    public static RealDedicatedServerHarness startWith(Path root, boolean cleanupOnClose)
            throws IOException, InterruptedException {
        Files.createDirectories(root);
        boolean bootstrap = !Files.exists(root.resolve("eula.txt"));
        return startInternal(root, bootstrap, cleanupOnClose);
    }

    private static RealDedicatedServerHarness startInternal(Path root, boolean bootstrap,
                                                            boolean cleanupOnClose)
            throws IOException, InterruptedException {
        if (bootstrap) {
            writeEula(root);
        }
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_PORT_BIND_ATTEMPTS; attempt++) {
            int port = reservePort();
            // Rewrite server.properties with the freshly reserved port on every
            // attempt — needed both for the first iteration and for retries
            // after a child JVM lost the TOCTOU race to bind it.
            Files.write(root.resolve("server.properties"),
                    buildServerProperties(port).getBytes(StandardCharsets.UTF_8));
            // Bind the control port and KEEP the socket, exactly as the client harness does: the
            // bound port is read off it, so nothing can steal the port between a probe and a rebind.
            java.net.ServerSocket controlSocket = openControlSocket();
            Process process = launchServer(root, port, controlSocket.getLocalPort());
            List<String> transcript = new ArrayList<>();
            Thread readerThread = startReader(process, transcript);
            TestClient client = new TestClient(process, TestClient.newWriter(process), transcript);
            startBridgeAcceptor(controlSocket, client);
            BootOutcome outcome;
            try {
                // Load-scaled: N concurrent modded boots contend on disk + CPU.
                outcome = awaitReadyOrBindFailure(process, transcript,
                        com.github.stannismod.forge.testing.TestTimeouts.scaled(Duration.ofMinutes(3)));
            } catch (RuntimeException | InterruptedException failure) {
                destroyAndJoin(process, readerThread);
                closeQuietly(controlSocket);
                throw failure;
            }
            if (outcome == BootOutcome.READY) {
                try {
                    awaitBridge(client);
                } catch (IOException | RuntimeException | InterruptedException bridgeFailure) {
                    // The server booted; only its control channel did not. Tear the child down here
                    // rather than letting the throw leak a live server process and a bound port —
                    // an orphaned dedicated server outlives the fork and holds the world directory.
                    destroyAndJoin(process, readerThread);
                    closeQuietly(controlSocket);
                    throw bridgeFailure;
                }
                return new RealDedicatedServerHarness(root, port, client, readerThread, cleanupOnClose,
                        controlSocket);
            }
            destroyAndJoin(process, readerThread);
            closeQuietly(controlSocket);
            lastFailure = new IOException("BindException on port " + port
                    + " (attempt " + attempt + " of " + MAX_PORT_BIND_ATTEMPTS + ")");
        }
        throw new IOException("Failed to start dedicated server after "
                + MAX_PORT_BIND_ATTEMPTS + " port-bind attempts", lastFailure);
    }

    private static final int MAX_PORT_BIND_ATTEMPTS = 3;

    /** Fixed world seed, so harness terrain is identical from run to run. See {@link #levelSeed()}. */
    private static final String DEFAULT_LEVEL_SEED = "forge-test-framework";

    private enum BootOutcome { READY, BIND_FAILED }

    private static BootOutcome awaitReadyOrBindFailure(Process process, List<String> transcript,
                                                       Duration timeout) throws InterruptedException {
        final String readyMarker = "For help, type \"help\" or \"?\"";
        final String bindMarker = "BindException";
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        int index = 0;
        while (System.nanoTime() < deadlineNanos) {
            synchronized (transcript) {
                while (index < transcript.size()) {
                    String line = transcript.get(index++);
                    if (line.contains(readyMarker)) {
                        return BootOutcome.READY;
                    }
                    if (line.contains(bindMarker)) {
                        return BootOutcome.BIND_FAILED;
                    }
                }
                if (!process.isAlive()) {
                    // Child exited without printing the ready marker. If a bind
                    // failure is visible in the tail, treat the attempt as a
                    // port collision and let the caller retry; otherwise this
                    // is a real crash and we surface it as before.
                    for (int i = transcript.size() - 1;
                         i >= Math.max(0, transcript.size() - 50); i--) {
                        if (transcript.get(i).contains(bindMarker)) {
                            return BootOutcome.BIND_FAILED;
                        }
                    }
                    throw new AssertionError("Server process exited (code="
                            + process.exitValue() + ") before becoming ready. Recent output: "
                            + tailOf(transcript));
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                long waitMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                transcript.wait(Math.min(waitMillis, 250L));
            }
        }
        throw new AssertionError("Timed out waiting for server to become ready. Recent output: "
                + tailOf(transcript));
    }

    private static String tailOf(List<String> transcript) {
        synchronized (transcript) {
            int from = Math.max(0, transcript.size() - 25);
            StringBuilder builder = new StringBuilder();
            for (int i = from; i < transcript.size(); i++) {
                if (i > from) {
                    builder.append(System.lineSeparator());
                }
                builder.append(transcript.get(i));
            }
            return builder.toString();
        }
    }

    private static void destroyAndJoin(Process process, Thread readerThread) {
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
        try {
            readerThread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    public Path root() {
        return root;
    }

    public int port() {
        return port;
    }

    public TestClient client() {
        return client;
    }

    @Override
    public void close() throws IOException {
        try {
            // Closes the bridge connection too; the control port itself is ours to release.
            client.close();
        } finally {
            closeQuietly(controlSocket);
            try {
                readerThread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
            // Preserve the server log at a stable location BEFORE the temp dir is wiped. The
            // client harness has done this for its side for a while; the server side had nothing,
            // so every diagnostic the SERVER writes — the mod's own ERROR/WARN reports about a
            // crossing, a reseat, a tick that gave up — died with the directory, and a failing
            // client e2e could only ever be read from the client's half of the story. `close()`
            // has already stopped the child (TestClient.close sends `stop` and waits), so the log
            // is flushed by the time we copy.
            try {
                Path serverLog = root.resolve("logs").resolve("latest.log");
                if (Files.isRegularFile(serverLog)) {
                    Files.copy(serverLog, preservedLogPath(), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    // No log4j file appender in this launcher layout: fall back to the console
                    // transcript we have been reading all along, so the preserved path is never
                    // silently empty (an absent file reads as "the server said nothing").
                    Files.write(preservedLogPath(), client.transcriptSnapshot(),
                            StandardCharsets.UTF_8);
                }
            } catch (IOException ignored) {
                // Best-effort only — never block close on preserve failure.
            }
            if (cleanupOnClose) {
                deleteRecursively(root);
            }
        }
    }

    /**
     * The stable post-mortem location of the last server log, one file PER TEST-JVM (the PID
     * suffix), mirroring the client harness's {@code forge-test-client-last-pid*.log}: a single
     * fixed name would let concurrent forks clobber each other's diagnostics, exactly when
     * parallel runs make failures interesting.
     *
     * <p>Last-boot-wins within one JVM. A class whose failing test is not the last one to run
     * therefore loses its log — filter to the single method when the log is the evidence.</p>
     */
    public static Path preservedLogPath() {
        String jvm = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int at = jvm.indexOf('@');
        String pid = at > 0 ? jvm.substring(0, at) : jvm;
        return Paths.get(System.getProperty("java.io.tmpdir"),
                "forge-test-server-last-pid" + pid + ".log");
    }

    /**
     * System property naming the launcher main class. Default {@code GradleStartServer}
     * (RFG / FG4 layout). Set to e.g. {@code net.minecraftforge.legacydev.MainServer}
     * for ForgeGradle 6 projects.
     */
    public static final String PROP_LAUNCHER_CLASS = "forge.test.launcher.class.server";

    /**
     * System property naming the assets dir passed via {@code --assetsDir}. Default
     * resolves to {@code <gradle-user-home>/caches/retro_futura_gradle/assets} for
     * RFG. Set to {@code <gradle-user-home>/caches/forge_gradle/assets} for FG6.
     * Ignored when {@link #PROP_LEGACY_ARGS} is {@code false}.
     */
    public static final String PROP_ASSETS_DIR = "forge.test.assets.dir";

    /**
     * System property toggling the RFG-style {@code --version / --assetsDir / --username / ...}
     * arg list. Default {@code true} (RFG behavior). Set to {@code false} for
     * launchers that take no args (e.g. FG6's {@code MainServer} which reads cwd).
     */
    public static final String PROP_LEGACY_ARGS = "forge.test.launcher.legacyArgs";

    private static Process launchServer(Path root, int port, int bridgePort) throws IOException {
        String javaExe = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String javaName = windows ? "java.exe" : "java";
        Path javaBinary = javaExe == null
                ? Paths.get(javaName)
                : Paths.get(javaExe, "bin", javaName);
        String launcherClass = System.getProperty(PROP_LAUNCHER_CLASS, "GradleStartServer");
        boolean legacyArgs = Boolean.parseBoolean(System.getProperty(PROP_LEGACY_ARGS, "true"));

        List<String> command = new ArrayList<>();
        command.add(javaBinary.toString());
        // Cap the child heap: without an explicit -Xmx, Java 8 ergonomics grant EACH server child
        // ~1/4 of physical RAM, which is what makes concurrent forks memory-infeasible. 1g fits a
        // modded 1.12 dedicated server; override per machine via the property.
        command.add("-Xmx" + System.getProperty("forge.test.server.xmx", "1g"));
        command.add("-Djava.awt.headless=true");
        command.add("-Dforge.test.server=true");
        // The control port for the child's in-JVM bridge, mirroring -Dforge.test.client.port on the
        // client side. A child with no bridge-starting mod simply ignores it.
        command.add("-D" + PROP_BRIDGE_PORT + "=" + bridgePort);
        // The harness's OWN coremod, so test-only mixin configurations are queued while mixin still
        // accepts them — the same arrangement the client child already uses. This is what lets an
        // observation a test needs live in the harness instead of in production code: a test mixin
        // reaches from the test source set INTO the product, and a shipped game, which never sets
        // this property and never carries the class, pays nothing for it.
        command.add("-Dfml.coreMods.load=com.github.stannismod.forge.testing.mixin.ForgeTestCoreMod");
        command.add("-D" + com.github.stannismod.forge.testing.TestTimeouts.PROP_FACTOR + "="
                + com.github.stannismod.forge.testing.TestTimeouts.factor());
        command.add("-cp");
        command.add(Objects.requireNonNull(System.getProperty("java.class.path"), "java.class.path"));
        command.add(launcherClass);

        if (legacyArgs) {
            String assetsDirProp = System.getProperty(PROP_ASSETS_DIR);
            Path assetsDir = assetsDirProp != null
                    ? Paths.get(assetsDirProp)
                    : gradleUserHome().resolve("caches").resolve("retro_futura_gradle").resolve("assets");
            command.add("--nogui");
            command.add("--gameDir");
            command.add(root.toAbsolutePath().toString());
            command.add("--assetsDir");
            command.add(assetsDir.toAbsolutePath().toString());
            command.add("--version");
            command.add("FML_DEV");
            command.add("--assetIndex");
            command.add("1.12.2");
            command.add("--username");
            command.add("Developer");
            command.add("--accessToken");
            command.add("FML");
            command.add("--userProperties");
            command.add("{}");
            command.add("--uuid");
            command.add(UUID.randomUUID().toString().replace("-", ""));
            command.add("--port");
            command.add(String.valueOf(port));
            command.add("--universe");
            command.add(root.toAbsolutePath().toString());
            command.add("--world");
            command.add("world");
        } else {
            // FG6's net.minecraftforge.legacydev.MainServer takes no args — it reads
            // working directory + server.properties. Port comes from server.properties
            // (already written above in startInternal) and gameDir is the cwd.
            command.add("--nogui");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private static Path gradleUserHome() {
        String env = System.getenv("GRADLE_USER_HOME");
        if (env != null && !env.trim().isEmpty()) {
            return Paths.get(env.trim());
        }
        return Paths.get(System.getProperty("user.home"), ".gradle");
    }

    /**
     * System property naming the control port handed to the server child. The child's in-JVM half
     * ({@code ForgeTestServerBootstrap}) reads exactly this, and does nothing when it is absent.
     */
    public static final String PROP_BRIDGE_PORT = "forge.test.server.port";

    /**
     * System property (milliseconds) bounding how long {@code start} waits after the ready marker
     * for the child's bridge to dial back. Default 15 s, load-scaled.
     *
     * <p>It bounds the wait; it cannot WAIVE it. There is no server this harness starts that has no
     * bridge — the child opens it from its own test-mode registration — so an escape hatch here
     * would only ever be used to keep running on a channel that no longer exists.</p>
     */
    public static final String PROP_BRIDGE_WAIT_MILLIS = "forge.test.server.bridge.waitMillis";

    /**
     * Bind an ephemeral control port on loopback and keep it — mirrors the client harness's
     * {@code openControlSocket}, and for the same reason: reserving a port and rebinding it later
     * leaves a window in which another process (a sibling fork churning ephemeral ports) takes it.
     */
    private static java.net.ServerSocket openControlSocket() throws IOException {
        java.net.ServerSocket socket = new java.net.ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
        return socket;
    }

    /**
     * Accept the child's control connection in the background and hand it to {@code client}.
     *
     * <p>Background rather than inline because the connection is made from the mod's
     * {@code FMLServerStartingEvent} handler, which on a dedicated server runs AFTER the
     * {@code For help, type "help"} line this harness boots on — and because a server carrying no
     * bridge at all must not stall the boot. The {@code READY} line the child writes first is
     * consumed here, so the socket handed over is idle.</p>
     */
    private static void startBridgeAcceptor(java.net.ServerSocket controlSocket, TestClient client) {
        Thread acceptor = new Thread(() -> {
            try {
                // Load-scaled like the client bot's handshake: a contended child reaches its
                // server-starting handlers late.
                controlSocket.setSoTimeout(com.github.stannismod.forge.testing.TestTimeouts
                        .scaledMillis(TimeUnit.MINUTES.toMillis(2)));
                java.net.Socket socket = controlSocket.accept();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                String ready = reader.readLine();
                if (!"READY".equals(ready)) {
                    System.out.println("[forge-test] server bridge said '" + ready
                            + "' instead of READY — ignoring it and staying on the console channel");
                    socket.close();
                    return;
                }
                client.attachBridge(socket, reader, writer);
                System.out.println("[forge-test] server bridge connected");
            } catch (IOException ignored) {
                // No bridge in this child (or the harness closed the port on shutdown). The
                // console channel is the documented fallback; awaitBridge says so out loud.
            }
        }, "forge-dedicated-server-bridge-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /**
     * Give the child's bridge a bounded chance to connect before the first command is issued, so a
     * suite does not silently send its first few commands down the console channel (each of which
     * broadcasts a {@code say} sentinel into every player's chat) and the rest down the bridge.
     *
     * <p>Announces the outcome either way: a channel this different is not something a reader of a
     * failing log should have to infer.</p>
     */
    /**
     * Wait for the child's bridge, and FAIL if it does not come.
     *
     * <p>This used to print a warning and carry on over the console. That is the shape of a silent
     * degradation: the console channel works, so a bridge that never attached produced green runs
     * on a channel whose replies are log slices, whose concurrent callers can take each other's
     * lines, and whose completion sentinel is broadcast into every player's chat — the four defects
     * the bridge exists to remove. A warning in a thirty-thousand-line build log is not an
     * announcement; nobody reads it, and every measurement taken afterwards is about the fallback.
     *
     * <p>There is no opt-out. A "server with no bridge" was the justification for keeping the
     * console channel, and no such server exists in this tree: every child this harness launches
     * carries the mod that opens one. A branch no caller reaches is not a fallback.
     */
    private static void awaitBridge(TestClient client) throws InterruptedException, IOException {
        long budgetMillis = Math.max(1_000L, com.github.stannismod.forge.testing.TestTimeouts
                .scaledMillis(Long.getLong(PROP_BRIDGE_WAIT_MILLIS, 15_000L).longValue()));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        while (System.nanoTime() < deadline) {
            if (client.hasBridge()) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new IOException("the server control bridge never dialled back within " + budgetMillis
                + " ms. The console channel would still work, which is why this is thrown rather"
                + " than warned about: it is the only command channel, so a run without it is not a"
                + " degraded run, it is no run at all. The child opens the bridge from its own"
                + " test-mode registration - check that the child reached that point.");
    }

    private static void closeQuietly(java.net.ServerSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing left to do.
        }
    }

    private static int reservePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    /**
     * Substrings of a child line that mean "this JVM is already broken, and every failure you are
     * about to read is a consequence rather than a cause".
     *
     * <p>A mixin that cannot be applied is FATAL and names itself — but only in the CHILD's log,
     * which nothing forwards. From the test's side its target class then fails to load and the
     * scenario reds somewhere far away, looking exactly like an arrangement problem. Measured
     * 2026-08-21: four runs were spent on a re-seat scenario that failed with "could not read the
     * seated craft's ship identity" while the child had already printed
     * {@code Critical injection failure: LVT … has incompatible changes}. The loudness existed the
     * whole time; nobody could hear it.
     */
    private static final String[] FATAL_MARKERS = {
        "Critical injection failure",
        "InvalidMixinException",
        "InvalidInjectionException",
        "Mixin apply for mod",
        "MixinTransformerError",
    };

    /** Put a child-side fatal on the TEST runner's own stdout, where a failure report can see it. */
    private static void echoIfFatal(String line) {
        for (String marker : FATAL_MARKERS) {
            if (line.contains(marker)) {
                System.out.println("[forge-test] CHILD FATAL — every later failure in this run is "
                        + "probably a consequence of this: " + line);
                return;
            }
        }
    }

    private static Thread startReader(Process process, List<String> transcript) {
        Thread reader = new Thread(() -> {
            try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    echoIfFatal(line);
                    synchronized (transcript) {
                        transcript.add(line);
                        transcript.notifyAll();
                    }
                }
            } catch (IOException ignored) {
                // The process is terminating or the stream has already been closed.
            }
        }, "forge-dedicated-server-log-reader");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private static void writeEula(Path root) throws IOException {
        Files.write(root.resolve("eula.txt"),
                java.util.Collections.singletonList("eula=true"), StandardCharsets.UTF_8);
    }

    /**
     * The world seed every harness world is generated from.
     *
     * <p>Each harness boot creates a FRESH world directory (see the temp root above), so an
     * empty {@code level-seed} — the previous behaviour — meant vanilla rolled a NEW RANDOM
     * seed for every single test run. Terrain under a fixture's coordinates then varied
     * run to run: a spot that was open air in one run sat inside a hillside in the next, which
     * surfaces as a test that "flakes" while the code under test is perfectly deterministic.
     * That cost real diagnosis time (a player suffocating inside terrain read as a space suit
     * failing to grant vacuum immunity).</p>
     *
     * <p>Pinning it makes terrain reproducible: a fixture that collides with the landscape now
     * fails EVERY run — loud and fixable — instead of one run in five. Override with
     * {@code -Dforge.test.level.seed=<value>} for a different (still fixed) landscape, or with
     * {@code random} to restore vanilla's per-run roll — which is how you check that a fixture is
     * not quietly seed-dependent, and how the determinism guard test proves it can still fail.</p>
     */
    private static String levelSeed() {
        String seed = System.getProperty("forge.test.level.seed");
        if (seed == null || seed.isEmpty()) {
            return DEFAULT_LEVEL_SEED;
        }
        // An empty level-seed is vanilla's "roll a new one"; this is the explicit way to ask.
        return "random".equalsIgnoreCase(seed.trim()) ? "" : seed;
    }

    private static String buildServerProperties(int port) {
        String newline = System.lineSeparator();
        StringBuilder builder = new StringBuilder();
        builder.append("enable-command-block=true").append(newline);
        builder.append("allow-nether=true").append(newline);
        builder.append("difficulty=1").append(newline);
        builder.append("gamemode=1").append(newline);
        builder.append("generate-structures=false").append(newline);
        builder.append("hardcore=false").append(newline);
        builder.append("level-name=world").append(newline);
        builder.append("level-seed=").append(levelSeed()).append(newline);
        builder.append("level-type=DEFAULT").append(newline);
        builder.append("max-tick-time=-1").append(newline);
        builder.append("motd=Forge Test").append(newline);
        builder.append("network-compression-threshold=256").append(newline);
        builder.append("online-mode=false").append(newline);
        builder.append("op-permission-level=4").append(newline);
        builder.append("pvp=false").append(newline);
        builder.append("spawn-animals=false").append(newline);
        builder.append("spawn-monsters=false").append(newline);
        builder.append("spawn-npcs=false").append(newline);
        builder.append("spawn-protection=0").append(newline);
        builder.append("server-ip=").append(newline);
        builder.append("server-port=").append(port).append(newline);
        builder.append("snooper-enabled=false").append(newline);
        builder.append("use-native-transport=false").append(newline);
        builder.append("view-distance=4").append(newline);
        return builder.toString();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

