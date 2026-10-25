package dev.stannismod.stellurgy.mixin;

import dev.stannismod.stellurgy.world.StellurgyPlanetWorldInfo;
import dev.stannismod.stellurgy.world.TimeOfDay;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalLong;

/**
 * Installs Stellurgy's per-dimension {@link net.minecraft.world.storage.WorldInfo} on a Stellurgy
 * dimension, at the only moment early enough to matter.
 *
 * <p>{@code WorldProvider.setWorld} is where vanilla caches this world's terrain type and generator
 * options into private fields ({@code WorldProvider:52-53}) and then calls {@code init()}; the
 * enclosing {@code WorldServer} constructor builds the chunk provider on the very next line. Anything
 * that swaps the {@code WorldInfo} later — a {@code WorldEvent.Load} handler, say — arrives after the
 * biome provider and the chunk generator have already been built from the OVERWORLD's values.
 * Injecting at HEAD puts the right info in place before any of that reads it.</p>
 *
 * <p>Deliberately NOT gated by the {@code perDimWorldInfo} config flag: that flag governs per-planet
 * weather and time, and which terrain a planet generates is not weather's business. The guard lives
 * in {@link StellurgyPlanetWorldInfo#installIfNeeded(World)} instead, which touches only server-side Stellurgy
 * dimensions whose info is still vanilla's shared-overworld one.</p>
 */
@Mixin(WorldProvider.class)
public abstract class MixinWorldProvider {

    @Shadow
    protected World world;

    @Inject(method = "setWorld", at = @At("HEAD"))
    private void ar$installPerDimensionWorldInfo(World worldIn, CallbackInfo ci) {
        StellurgyPlanetWorldInfo.installIfNeeded(worldIn);
    }

    /**
     * A body world's time of day is its body's turn at the space clock (C16 CLOCK-6), not a counter. Forge
     * routes every time-of-day read through here — {@code World#getWorldTime}, the celestial angle, the moon
     * phase — so the sky, daylight and everything vanilla derives from them follow the body. Vanilla's stored
     * counter is still written by the tick, the sleep and {@code /time}, and is not read for a body.
     */
    @Inject(method = "getWorldTime", at = @At("HEAD"), cancellable = true, remap = false)
    private void ar$timeOfDayIsTheBodysTurn(CallbackInfoReturnable<Long> cir) {
        OptionalLong time = TimeOfDay.of(world);
        if (time.isPresent()) {
            cir.setReturnValue(time.getAsLong());
        }
    }
}
