package com.github.stannismod.forge.testing.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One server game directory, which may outlive several boots — the only handle a test holds on it.
 *
 * <p>It hands out no path. What a test may do to the directory is the harness's to perform, each at the one
 * moment it means something, and each refused loudly at any other:</p>
 * <ul>
 *   <li>{@link #seed} — the START: files written before the FIRST boot, from a typed {@link StartFiles};</li>
 *   <li>{@link #editWhileDown} — a change BETWEEN two boots, applied while no server runs here, from a typed
 *       {@link BetweenBootsEdit};</li>
 *   <li>{@link #keepAside} — a copy of the last boot's log and player files, kept from the next boot;</li>
 *   <li>reads ({@link #exists}, {@link #readText}, {@link #sizesUnder}) — text and numbers, never a path.</li>
 * </ul>
 * <p>A server boots on it through {@link RealDedicatedServerHarness}; while one runs, the directory is the
 * server's, and nothing here writes into it.</p>
 */
public final class ServerRoot {

    /** The world folder every harness server writes ({@code level-name} in its {@code server.properties}). */
    public static final String LEVEL_NAME = "world";

    private final Path dir;
    /** The server running on this directory now, or null. */
    private RealDedicatedServerHarness live;
    private int boots;
    private boolean seeded;
    private boolean deleted;

    private ServerRoot(Path dir) {
        this.dir = dir;
    }

    /** A new, empty game directory under the system temp directory, named with {@code prefix}. */
    public static ServerRoot fresh(String prefix) throws IOException {
        if (prefix.indexOf('/') >= 0 || prefix.indexOf('\\') >= 0 || prefix.contains("..")) {
            throw new IllegalArgumentException("a game directory's prefix is a name, not a path: " + prefix);
        }
        return new ServerRoot(Files.createTempDirectory(prefix));
    }

    /**
     * Write {@code start}'s files. Only before the first boot, and once: a start is what the first boot
     * reads, and a second one would overwrite part of the first without either saying so.
     */
    public ServerRoot seed(StartFiles start) throws IOException {
        requireUsable("seed a start");
        if (boots > 0) {
            throw new IllegalStateException("REFUSED: " + this + " has already been booted " + boots + " time(s); a"
                    + " start is written before the FIRST boot. A change after one is editWhileDown.");
        }
        if (seeded) {
            throw new IllegalStateException("REFUSED: " + this + " is already seeded; a game directory has ONE"
                    + " start -- merge the declarations into one StartFiles.");
        }
        Map<String, String> files = start.files();
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path target = resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue().getBytes(StandardCharsets.UTF_8));
        }
        seeded = true;
        System.out.println("[server root] " + dir.getFileName() + " seeded:" + start.describe());
        return this;
    }

    /**
     * Apply {@code edit} to the file the last boot left. Only between two boots: after at least one, and
     * while none runs. Refuses a missing file and an edit that changes nothing.
     */
    public void editWhileDown(BetweenBootsEdit edit) throws IOException {
        requireUsable("edit a file");
        if (boots == 0) {
            throw new IllegalStateException("REFUSED: nothing has been booted on " + this + " yet, so there is no"
                    + " 'between two boots' -- a file the first boot reads is part of the start (seed).");
        }
        Path target = resolve(edit.file());
        if (!Files.isRegularFile(target)) {
            throw new IllegalStateException("REFUSED: the last boot on " + this + " left no " + edit.file()
                    + " to edit");
        }
        String saved = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
        String edited = edit.edit(saved);
        if (edited == null || edited.equals(saved)) {
            throw new IllegalStateException("REFUSED: the edit of " + edit.file() + " on " + this + " changes"
                    + " nothing, so it arranges nothing");
        }
        Files.write(target, edited.getBytes(StandardCharsets.UTF_8));
        System.out.println("[server root] " + dir.getFileName() + " edited " + edit.file() + " between boots ("
                + saved.length() + " -> " + edited.length() + " chars)");
    }

    /**
     * Copy the last boot's {@code logs/latest.log} to {@code logs/<label>.log} and its player files to
     * {@code world/playerdata-<label>}, so the next boot (which rewrites both) does not destroy them.
     * Diagnostics: answers what was kept, or why not, and never fails the test over a copy.
     */
    public String keepAside(String label) {
        requireUsable("keep a boot's record aside");
        if (label.indexOf('/') >= 0 || label.indexOf('\\') >= 0 || label.contains("..")) {
            throw new IllegalArgumentException("a label is a name, not a path: " + label);
        }
        try {
            List<String> kept = new ArrayList<>();
            Path log = dir.resolve("logs").resolve("latest.log");
            if (Files.isRegularFile(log)) {
                Files.copy(log, dir.resolve("logs").resolve(label + ".log"), StandardCopyOption.REPLACE_EXISTING);
                kept.add("logs/" + label + ".log");
            }
            Path players = dir.resolve(LEVEL_NAME).resolve("playerdata");
            if (Files.isDirectory(players)) {
                Path into = dir.resolve(LEVEL_NAME).resolve("playerdata-" + label);
                Files.createDirectories(into);
                try (Stream<Path> files = Files.list(players)) {
                    for (Path f : files.collect(Collectors.toList())) {
                        Files.copy(f, into.resolve(f.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                kept.add(LEVEL_NAME + "/playerdata-" + label);
            }
            return kept.isEmpty() ? "nothing to keep (no log, no player files)" : "kept " + kept;
        } catch (IOException e) {
            return "not kept: " + e;
        }
    }

    /** Whether {@code relative} exists in the directory. */
    public boolean exists(String relative) {
        return Files.exists(resolve(relative));
    }

    /** The text of the file {@code relative}, read as UTF-8; throws when there is none. */
    public String readText(String relative) throws IOException {
        return new String(Files.readAllBytes(resolve(relative)), StandardCharsets.UTF_8);
    }

    /**
     * Every file named {@code fileName} under the directory {@code relativeDir}, by its path relative to
     * {@code relativeDir} (forward slashes), with its size in bytes; empty when there is no such directory
     * (ask {@link #exists} to tell the two apart).
     */
    public Map<String, Long> sizesUnder(String relativeDir, String fileName) throws IOException {
        Path under = resolve(relativeDir);
        Map<String, Long> sizes = new TreeMap<>();
        if (!Files.isDirectory(under)) {
            return sizes;
        }
        try (Stream<Path> tree = Files.walk(under)) {
            for (Path p : tree.filter(p -> p.getFileName().toString().equals(fileName)).collect(Collectors.toList())) {
                sizes.put(under.relativize(p).toString().replace('\\', '/'), Files.size(p));
            }
        }
        return sizes;
    }

    /** Delete the directory. Refused while a server runs on it; afterwards nothing here can be used. */
    public void delete() throws IOException {
        if (live != null) {
            throw new IllegalStateException("REFUSED: a server is running on " + this + "; stop it first");
        }
        if (deleted) {
            return;
        }
        deleted = true;
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public String toString() {
        return "the game directory " + dir.getFileName();
    }

    // ------------------------------------------------------------------------------------------------
    // the harness's side

    /** The directory itself: the harness's, never a test's. */
    Path dir() {
        return dir;
    }

    /** A boot is starting here: refused when another runs or the directory is gone. */
    void bootStarting() {
        requireUsable("boot a server");
    }

    /** The boot that started here is up. */
    void booted(RealDedicatedServerHarness harness) {
        live = harness;
        boots++;
    }

    /** The server that ran here has stopped (or never came up). */
    void bootEnded() {
        live = null;
    }

    private void requireUsable(String what) {
        if (deleted) {
            throw new IllegalStateException("REFUSED to " + what + ": " + this + " has been deleted");
        }
        if (live != null) {
            throw new IllegalStateException("REFUSED to " + what + ": a server is running on " + this
                    + " -- while it runs, the directory is the server's");
        }
    }

    /** {@code relative} inside the directory; refuses an absolute path and one that leaves it. */
    private Path resolve(String relative) {
        Path asked = Paths.get(relative);
        Path target = dir.resolve(relative).normalize();
        if (asked.isAbsolute() || !target.startsWith(dir.normalize()) || target.equals(dir.normalize())) {
            throw new IllegalArgumentException("REFUSED: " + relative + " is not a file inside " + this);
        }
        return target;
    }
}
