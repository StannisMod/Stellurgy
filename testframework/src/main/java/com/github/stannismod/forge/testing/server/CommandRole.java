package com.github.stannismod.forge.testing.server;

import java.util.Locale;

/**
 * The role a test sends a server command in. {@link TestClient} has one method per role and none without, so
 * every command a test sends says what it is for; the role travels with the command over the bridge, and a
 * consuming mod's server-side judge may refuse a command whose kind the role does not admit.
 *
 * <p>The harness itself attaches no meaning to the roles beyond carrying them: which commands each admits is
 * the consuming mod's table.</p>
 */
public enum CommandRole {

    /** The command observes. */
    READ,
    /** The command sets the scene: lays blocks, writes state, decides an outcome the test needs as its start. */
    ARRANGE,
    /** The command calls a production entry a player, a tick or a packet would reach. */
    ACT;

    /** The word the bridge carries: the role's name in lower case. */
    String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
