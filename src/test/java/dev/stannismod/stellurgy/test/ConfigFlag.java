package dev.stannismod.stellurgy.test;

/**
 * One whitelisted config field flipped for the length of a scenario through
 * {@code stellurgytest config set}, and put back to the value it was found at.
 *
 * <p>Put back to what was READ, never to a remembered default: a shared harness world's seed decides
 * what a field starts at (see {@code GameDirSeed.forTheSharedHarness}), and a scenario that "restored"
 * a value it assumed would leave the next scenario standing on its own assumption. Use it as a
 * try-with-resources.</p>
 */
public final class ConfigFlag implements AutoCloseable {

    private final ServerCommands commands;
    private final String key;
    private final String found;

    private ConfigFlag(ServerCommands commands, String key, String found) {
        this.commands = commands;
        this.key = key;
        this.found = found;
    }

    /** Set {@code key} to {@code value}, remembering the value it held. */
    public static ConfigFlag set(ServerCommands commands, String key, Object value) throws Exception {
        Reply before = commands.read("stellurgytest config get " + key).requireOk("config get " + key);
        String found = before.text("value");
        commands.arrange("stellurgytest config set " + key + " " + value).requireOk("config set " + key);
        return new ConfigFlag(commands, key, found);
    }

    /** Put the field back to the value it was found at. */
    @Override
    public void close() throws Exception {
        commands.arrange("stellurgytest config set " + key + " " + found).requireOk("config set " + key + " (restore)");
    }
}
