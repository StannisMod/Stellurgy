package com.github.stannismod.forge.testing;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.List;

/**
 * The e2e MODE of a harness run: a child JVM launched with it refuses, at the call, every act a player
 * could not have performed.
 *
 * <p><b>Why a mode and not a scan of the test's text.</b> A check that reads a test's source has to guess
 * what each call means — a string assembled at run time, a helper on an instance, a call by reflection,
 * an argument that looks like a verb — and every guess is a form that can be written around. A child
 * that knows it serves an e2e decides on what is actually CALLED: the client bridge refuses a command
 * whose kind is not admitted, and the consuming mod's server gate refuses a command whose kind is not
 * admitted. Neither can be told something else by the way the test was written.</p>
 *
 * <p><b>Who sets it, and where a child reads it.</b> Only the e2e entry points of the two harnesses
 * ({@code RealDedicatedServerHarness.startE2E}, {@code RealClientHarness.startE2E}), which the e2e base
 * class calls, put it on the CHILD's command line, as a JVM argument. A child reads it from its own
 * LAUNCH ARGUMENTS ({@code RuntimeMXBean.getInputArguments}), which nothing running in that JVM can change
 * — not code that clears the system property, and not a launcher class that runs before the game — and it
 * refuses to start when the system property disagrees with them. Each child reads it ONCE, when the object
 * that judges its commands is created for that child's life — the client bridge with {@code Minecraft},
 * the consuming mod's server gate with the server, its test-mixin plugin when mixin loads it — and holds
 * the answer on that object.</p>
 *
 * <p>What a static call may reach in that mode is no package rule: each side names its instruments,
 * (class, method), in one table in code — the server's beside the consuming mod's probe kinds, the
 * client's in the bridge ({@code ClientInstruments}).</p>
 */
public final class E2EMode {

    /** {@code true} on a child JVM that serves an e2e. Absent on every other child. */
    public static final String PROPERTY = "forge.test.e2e";

    /** The one JVM argument that puts a child in e2e mode. */
    public static final String ARGUMENT = "-D" + PROPERTY + "=true";

    /**
     * The server command the e2e's {@code PlayerInput} sends once, before its FIRST input reaches the
     * client: from then on the walk has begun, and the consuming mod's gate refuses every build. A protocol
     * with that gate, which restates it.
     */
    public static final String WALK_BEGINS_COMMAND = "stellurgytest e2e walk-begins";

    /**
     * The prefix of the line the consuming mod's gate prints for every command typed into the server's
     * CONSOLE in e2e mode — {@code <prefix>admitted <command>} or {@code <prefix>refused <command> <json>} —
     * which {@code TestClient.sendRaw} waits for. A protocol with that gate, which restates it.
     */
    public static final String CONSOLE_JUDGED = "[e2e mode] console judged: ";

    private E2EMode() {
    }

    /**
     * Whether THIS JVM was launched to serve an e2e — for the one object per child that reads it when it
     * is created, and holds the answer for the child's life. Read from the JVM's own launch arguments;
     * throws, so the child does not start, when the system property says otherwise.
     */
    public static boolean launchedForAnE2E() {
        boolean launched = ManagementFactory.getRuntimeMXBean().getInputArguments().contains(ARGUMENT);
        boolean property = Boolean.getBoolean(PROPERTY);
        if (launched != property) {
            throw new IllegalStateException("REFUSED to start: this JVM was launched " + (launched ? "WITH" : "WITHOUT")
                    + " " + ARGUMENT + " and its system property " + PROPERTY + " is now " + System.getProperty(PROPERTY)
                    + " -- something rewrote the mode before the game started (a launcher class, a static"
                    + " initialiser), and a child that cannot tell which mode it serves serves none");
        }
        return launched;
    }

    /** The JVM arguments that put a child in e2e mode. */
    public static List<String> childArguments() {
        return Collections.singletonList(ARGUMENT);
    }
}
