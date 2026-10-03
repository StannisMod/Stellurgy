package com.github.stannismod.forge.testing.mixin;

import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.github.stannismod.forge.testing.server.bridge.ServerBridgeOwner;

/**
 * Gives every SERVER the memory of whether its test bridge was started — an instance field of the
 * server object, so a second server in one JVM starts a bridge of its own and nothing outlives the
 * server that owned it. Both sides: an integrated server is a {@code MinecraftServer} too.
 */
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServerBridge implements ServerBridgeOwner {

    @Unique
    private final AtomicBoolean forgeTest$serverBridgeStarted = new AtomicBoolean(false);

    @Override
    public boolean forgeTest$claimServerBridgeStart() {
        return forgeTest$serverBridgeStarted.compareAndSet(false, true);
    }
}
