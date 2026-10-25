package dev.stannismod.stellurgy.mixin;

import dev.stannismod.stellurgy.world.TimeSkip;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Where vanilla asks, once per world tick, whether a world's night ends — {@code areAllPlayersAsleep()} in
 * {@code WorldServer#tick} — the answer is the time skip's ({@link TimeSkip#nightEnds}): a sleep moves the
 * universe (C16 CLOCK-7), not one world's counter. Always woven: unlike the per-dimension WorldInfo mixins it
 * does not depend on {@code perDimWorldInfo}.
 */
@Mixin(WorldServer.class)
public abstract class MixinWorldServerTimeSkip {

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/WorldServer;areAllPlayersAsleep()Z"), require = 1)
    private boolean stellurgy$theNightEndsForTheUniverse(WorldServer self) {
        return TimeSkip.nightEnds(self);
    }
}
