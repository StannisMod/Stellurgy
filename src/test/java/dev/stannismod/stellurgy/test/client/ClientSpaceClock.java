package dev.stannismod.stellurgy.test.client;

import com.github.stannismod.forge.testing.client.ClientBot;

import java.io.IOException;

/**
 * The space clock as the CLIENT holds it — production's own {@code ServerView.current().clock()}, read in the
 * client JVM through a named instrument.
 *
 * <p>Test source set: absent from a released jar.</p>
 */
public final class ClientSpaceClock {

    private ClientSpaceClock() {
    }

    /** The client's space clock now, as production's synced clock answers it. */
    public static long now(ClientBot bot) throws IOException {
        return Long.parseLong(bot.invokeStaticInt(ClientSpaceClock.class.getName(), "nowOnClient")
                .get("returned").getAsString().trim());
    }

    /** Whether the client's space clock has been synced from the server ({@code "true"} / {@code "false"}). */
    public static String hasSync(ClientBot bot) throws IOException {
        return bot.invokeStaticInt(ClientSpaceClock.class.getName(), "hasSyncOnClient")
                .get("returned").getAsString().trim();
    }

    /** Runs in the client JVM, on its client thread — reached only through {@link #now}. */
    static long nowOnClient() {
        return dev.stannismod.stellurgy.client.ServerView.current().clock().now();
    }

    /** Runs in the client JVM, on its client thread — reached only through {@link #hasSync}. */
    static boolean hasSyncOnClient() {
        return dev.stannismod.stellurgy.client.ServerView.current().clock().hasSync();
    }
}
