package dev.stannismod.stellurgy.test;

/**
 * A test's server commands in their three roles, handed to a helper that sends commands of more than one
 * role over its life (it lays a fixture, then triggers an assembly, then reads the result) — where a single
 * role-less probe would have to pick one. The test bases hand theirs out as {@code commands()}; the role of
 * each command is chosen inside the helper, at the line that knows what the command is.
 *
 * <p>A helper that sends commands of ONE role takes its own one-method probe instead, given a role by the
 * caller ({@code c -> read(c).raw()}), so the role stays visible where the test calls it.</p>
 */
public interface ServerCommands {

    /** A command that observes. */
    Reply read(String command) throws Exception;

    /** A command that sets the scene: an arrangement, a build, a decided outcome. */
    Reply arrange(String command) throws Exception;

    /** A command that calls a production entry a player, a tick or a packet would reach. */
    Reply act(String command) throws Exception;
}
