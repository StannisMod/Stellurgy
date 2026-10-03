package com.github.stannismod.forge.testing.mixin;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.github.stannismod.forge.testing.client.bridge.ClientBridgeOwner;
import com.github.stannismod.forge.testing.client.bridge.ForgeTestClientBootstrap;

/**
 * Gives the CLIENT its test bridge: an instance field of the client object, created with it and
 * released with it, so nothing the bridge keeps — its event log, its tick counter, the address a
 * disconnect left — lives in a static.
 *
 * <p>That it is woven at all is also the proof the harness's mixin configuration was applied: the
 * bridge reports {@code recording} from its own existence rather than from a flag the coremod set.</p>
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftClientBridge implements ClientBridgeOwner {

    @Unique
    private final ForgeTestClientBootstrap forgeTest$clientBridge = new ForgeTestClientBootstrap();

    @Override
    public ForgeTestClientBootstrap forgeTest$clientBridge() {
        return forgeTest$clientBridge;
    }
}
