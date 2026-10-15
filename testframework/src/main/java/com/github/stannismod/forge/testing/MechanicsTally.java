package com.github.stannismod.forge.testing;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a {@link ChildMode#MECHANICS} child's judge was asked to do: every distinct command key it saw, with
 * how often, written to {@code <counting dir>/<side>-<pid>.txt} ({@link ChildMode#COUNTING_DIR_PROPERTY}).
 * One per judge, made with it and living as long as it — the client bridge, for the client's life.
 *
 * <p>The file is rewritten whenever a key is seen for the first time, every {@value #REWRITE_EVERY} calls,
 * and once more when the JVM exits — so the SET of keys is exact whenever the child dies, and a count can
 * lag by less than {@value #REWRITE_EVERY} only for a child killed without exiting. A child launched with no
 * counting directory writes nothing and says so once, on its standard output.</p>
 */
public final class MechanicsTally {

    /** How many calls may pass between two writes of the file. */
    private static final int REWRITE_EVERY = 100;

    private final Map<String, Integer> seen = new TreeMap<>();
    private final Path file;
    private int calls;

    public MechanicsTally(String side) {
        String dir = System.getProperty(ChildMode.COUNTING_DIR_PROPERTY);
        if (dir == null || dir.isEmpty()) {
            this.file = null;
            System.out.println("[mechanics mode] " + side + ": no " + ChildMode.COUNTING_DIR_PROPERTY
                    + " -- what this child is asked is NOT counted");
            return;
        }
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        this.file = Paths.get(dir).resolve(side + "-" + pid + ".txt");
        Runtime.getRuntime().addShutdownHook(new Thread(this::write, "mechanics-tally-" + side));
    }

    /** One call the judge saw, by its key. */
    public void saw(String key) {
        boolean first;
        boolean due;
        synchronized (this) {
            first = seen.merge(key, 1, Integer::sum) == 1;
            due = ++calls % REWRITE_EVERY == 0;
        }
        if (first || due) {
            write();
        }
    }

    private synchronized void write() {
        if (file == null) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Integer> e : seen.entrySet()) {
            text.append(e.getValue()).append('\t').append(e.getKey()).append('\n');
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, text.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.out.println("[mechanics mode] the tally could not be written to " + file + ": " + e);
        }
    }
}
