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

    /** How this reader reaches the probe. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    private final Probe probe;
    private final String key;
    private final String found;

    private ConfigFlag(Probe probe, String key, String found) {
        this.probe = probe;
        this.key = key;
        this.found = found;
    }

    /** Set {@code key} to {@code value}, remembering the value it held. */
    public static ConfigFlag set(Probe probe, String key, Object value) throws Exception {
        Reply before = Reply.of("stellurgytest config get", probe.exec("stellurgytest config get " + key))
                .requireOk("config get " + key);
        String found = before.text("value");
        Reply.of("stellurgytest config set", probe.exec("stellurgytest config set " + key + " " + value))
                .requireOk("config set " + key);
        return new ConfigFlag(probe, key, found);
    }

    /** Put the field back to the value it was found at. */
    @Override
    public void close() throws Exception {
        Reply.of("stellurgytest config set", probe.exec("stellurgytest config set " + key + " " + found))
                .requireOk("config set " + key + " (restore)");
    }
}
