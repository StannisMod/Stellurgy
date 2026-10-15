package com.github.stannismod.forge.testing;

/**
 * The e2e half of the harness's protocol with the consuming mod's server gate. Which mode a child serves is
 * {@link ChildMode}'s; this holds what an e2e child's server and its player input say to each other.
 *
 * <p><b>Why a mode and not a scan of the test's text.</b> A check that reads a test's source has to guess
 * what each call means — a string assembled at run time, a helper on an instance, a call by reflection,
 * an argument that looks like a verb — and every guess is a form that can be written around. A child
 * that knows it serves an e2e decides on what is actually CALLED: the client bridge refuses a command
 * whose kind is not admitted, and the consuming mod's server gate refuses a command whose kind is not
 * admitted. Neither can be told something else by the way the test was written.</p>
 *
 * <p>What a static call may reach in that mode is no package rule: each side names its instruments,
 * (class, method), in one table in code — the server's beside the consuming mod's probe kinds, the
 * client's in the bridge ({@code ClientInstruments}).</p>
 */
public final class E2EMode {

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
}
