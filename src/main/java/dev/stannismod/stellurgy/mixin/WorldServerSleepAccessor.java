package dev.stannismod.stellurgy.mixin;

import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Vanilla's own wake-up of a world's sleepers — which also resets that world's weather — for a sleep that
 * completes across worlds: the world whose tick completes it wakes its own through vanilla's block, and the
 * others it took are woken through this ({@code dev.stannismod.stellurgy.world.TimeSkip#nightEnds}).
 */
@Mixin(WorldServer.class)
public interface WorldServerSleepAccessor {

    @Invoker("wakeAllPlayers")
    void stellurgy$wakeAllPlayers();
}
