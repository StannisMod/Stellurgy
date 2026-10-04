package dev.stannismod.stellurgy.test;

import net.minecraft.init.Bootstrap;

/**
 * Initializes vanilla Minecraft's static registries via {@link Bootstrap#register()} - and nothing
 * of the mod. Idempotent because {@code Bootstrap.register} is: vanilla guards it with its own
 * {@code alreadyRegistered} flag, so a second call does nothing and this class keeps no state.
 *
 * <p>That is the whole of what the unit and integration tiers may borrow. A test that needs the mod's
 * state - its proxy, its mod object, a server lifetime and the galaxy that server holds, the current
 * configuration - is asking for what a running game provides, and arranging it by hand is a boot
 * done badly: it builds the part its author thought of and leaves the rest as whatever the previous
 * test left. Such a test belongs in the harness tiers ({@code test/server}, {@code test/client}),
 * where the state is real.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 *   @BeforeClass public static void bootstrap() { MinecraftBootstrap.ensure(); }
 * }</pre>
 */
public final class MinecraftBootstrap {

    private MinecraftBootstrap() {}

    public static void ensure() {
        synchronized (MinecraftBootstrap.class) {
            Bootstrap.register();
        }
    }
}
