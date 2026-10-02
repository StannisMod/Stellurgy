package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import dev.stannismod.stellurgy.test.trace.ShipLoadHold;

/**
 * Gives a test server its {@link ShipLoadHold} switch, created with the server and released with it.
 * Test source set.
 */
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServerShipLoadHold implements ShipLoadHold {

    @Unique
    private boolean stellurgyTest$shipsHeldLoaded = true;

    @Override
    public boolean stellurgyTest$shipsHeldLoaded() {
        return stellurgyTest$shipsHeldLoaded;
    }

    @Override
    public void stellurgyTest$holdShipsLoaded(boolean hold) {
        stellurgyTest$shipsHeldLoaded = hold;
    }
}
