package com.github.stannismod.forge.testing.client.bridge;

/**
 * Implemented on {@code Minecraft} by the harness's own mixin, {@code MixinMinecraftClientBridge}: the
 * client's test bridge is an instance field of the client object, created with it and released with
 * it. A duck interface — nothing implements it in source, and a cast to it is how the bridge's static
 * entry points reach the client they run in.
 */
public interface ClientBridgeOwner {

    ForgeTestClientBootstrap forgeTest$clientBridge();
}
