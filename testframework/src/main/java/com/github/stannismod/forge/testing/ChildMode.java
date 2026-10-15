package com.github.stannismod.forge.testing;

import java.lang.management.ManagementFactory;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The MODE every harness child is launched in, and the only two there are: {@link #E2E}, a child that walks
 * a player's path and refuses every act a player could not have performed, and {@link #MECHANICS}, a child
 * of a mechanics test, which may arrange.
 *
 * <p><b>No mode, no child.</b> The harness puts the mode on the child's command line
 * ({@code -Dforge.test.mode=e2e|mechanics}), taken from the {@code HarnessBoot} the start was given, which
 * cannot be made without one. A child reads it from its own LAUNCH ARGUMENTS
 * ({@code RuntimeMXBean.getInputArguments}), which nothing running in that JVM can change, ONCE, when the
 * object that judges its commands is made for the child's life — the client bridge with {@code Minecraft},
 * the consuming mod's server gate with the server, its test-mixin plugin when mixin loads it — and holds
 * the answer there. A child launched with no mode, or whose system property disagrees with its launch
 * arguments, refuses to start: an ungoverned child does not exist.</p>
 *
 * <p><b>Counting.</b> A {@link #MECHANICS} child is also told where to write what it was asked to do
 * ({@value #COUNTING_DIR_PROPERTY}): each judge records every distinct verb, vanilla root and static call it
 * sees and writes the tally there, so the commands tests build at run time are measured, not guessed.</p>
 */
public enum ChildMode {

    /** A child that serves an e2e. */
    E2E("e2e"),
    /** A child of a mechanics test. */
    MECHANICS("mechanics");

    /** The system property the mode travels in. A protocol with every child's judge, which restates it. */
    public static final String PROPERTY = "forge.test.mode";

    /**
     * Where a {@link #MECHANICS} child writes its tally of what it was asked: a directory of the build the
     * test JVM runs in. A protocol with every child's judge, which restates it.
     */
    public static final String COUNTING_DIR_PROPERTY = "forge.test.counting.dir";

    /** The mode's word on the command line. A constant: written once by the constructor, a String. */
    private final String word;

    ChildMode(String word) {
        this.word = word;
    }

    /** The mode's word, as the child's property carries it. */
    public String word() {
        return word;
    }

    /** The JVM arguments that launch a child in this mode. */
    public List<String> childArguments() {
        List<String> out = new ArrayList<>();
        out.add("-D" + PROPERTY + "=" + word);
        if (this == MECHANICS) {
            out.add("-D" + COUNTING_DIR_PROPERTY + "=" + Paths.get(System.getProperty("user.dir", "."))
                    .resolve("build").resolve("mechanics-counting").toAbsolutePath());
        }
        return out;
    }

    /**
     * The mode THIS JVM was launched in, read off its own launch arguments. Throws — the child does not
     * start — when it was launched with none, with more than one, or when the system property now says
     * otherwise.
     */
    public static ChildMode launched() {
        String prefix = "-D" + PROPERTY + "=";
        ChildMode found = null;
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!argument.startsWith(prefix)) {
                continue;
            }
            ChildMode mode = byWord(argument.substring(prefix.length()));
            if (mode == null || (found != null && found != mode)) {
                throw new IllegalStateException("REFUSED to start: this JVM was launched with " + argument
                        + ", which names no single harness mode (" + E2E.word + " | " + MECHANICS.word + ")");
            }
            found = mode;
        }
        if (found == null) {
            throw new IllegalStateException("REFUSED to start: this harness child was launched with no " + prefix
                    + E2E.word + "|" + MECHANICS.word + " -- a child that does not know which mode it serves serves"
                    + " none, and an ungoverned child is not started");
        }
        if (!found.word.equals(System.getProperty(PROPERTY))) {
            throw new IllegalStateException("REFUSED to start: this JVM was launched in mode " + found.word
                    + " and its system property " + PROPERTY + " is now " + System.getProperty(PROPERTY)
                    + " -- something rewrote the mode before the game started");
        }
        return found;
    }

    private static ChildMode byWord(String word) {
        for (ChildMode mode : values()) {
            if (mode.word.equals(word)) {
                return mode;
            }
        }
        return null;
    }
}
