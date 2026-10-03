package com.github.stannismod.forge.testing.server.bridge;

/**
 * Implemented on {@code MinecraftServer} by the harness's own mixin, {@code MixinMinecraftServerBridge}:
 * whether this server's test bridge has been started is remembered by the server itself, so it is
 * created with the server and released with it. A duck interface — nothing implements it in source.
 */
public interface ServerBridgeOwner {

    /** {@code true} exactly once per server: for the caller that gets to start its bridge. */
    boolean forgeTest$claimServerBridgeStart();
}
