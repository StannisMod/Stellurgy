package dev.stannismod.stellurgy.test.client;

import java.io.IOException;

import com.github.stannismod.forge.testing.client.ClientBot;

/**
 * What Forge's dimension registry holds in the CLIENT JVM — the registrations a client connected to a
 * remote server makes for the planets it hears of.
 *
 * <p>Test source set: absent from a released jar.</p>
 */
public final class ClientDimensions {

    private ClientDimensions() {
    }

    /** Whether {@code bot}'s client has dimension {@code dim} registered with Forge. */
    public static boolean forgeRegistered(ClientBot bot, int dim) throws IOException {
        return Boolean.parseBoolean(bot.invokeStaticInt(ClientDimensions.class.getName(),
                "forgeRegisteredOnClient", dim).get("returned").getAsString());
    }

    /** Runs in the client JVM, on its client thread — reached only through {@link #forgeRegistered}. */
    static boolean forgeRegisteredOnClient(int dim) {
        return net.minecraftforge.common.DimensionManager.isDimensionRegistered(dim);
    }
}
