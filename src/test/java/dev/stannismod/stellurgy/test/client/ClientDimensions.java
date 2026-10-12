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

    /**
     * What Stellurgy's own dimension registry answers on {@code bot}'s client, through production's accessor:
     * {@code size=<n>} for the dimensions it holds, or {@code threw: <message>} when the accessor refuses (a
     * client connected to no server holds no galaxy, and production says so by throwing).
     */
    public static String stellurgyRegistry(ClientBot bot) throws IOException {
        return bot.invokeStaticInt(ClientDimensions.class.getName(), "stellurgyRegistryOnClient")
                .get("returned").getAsString();
    }

    /** Runs in the client JVM, on its client thread — reached only through {@link #stellurgyRegistry}. */
    static String stellurgyRegistryOnClient() {
        try {
            return "size=" + dev.stannismod.stellurgy.dimension.DimensionManager.getInstance()
                    .getRegisteredDimensions().length;
        } catch (RuntimeException refused) {
            return "threw: " + refused.getMessage();
        }
    }
}
