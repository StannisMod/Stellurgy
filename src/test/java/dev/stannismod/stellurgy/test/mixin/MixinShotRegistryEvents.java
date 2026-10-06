package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.util.math.Vec3d;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.api.projectile.ShotEndReason;
import dev.stannismod.stellurgy.projectile.ShotRegistry;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * A thrown round leaving the air, as an event: {@code shot_ended}, with {@code reason}.
 *
 * <p>The HEAD of {@code ShotRegistry.end}, which every ending in the substrate goes through — an
 * impact, an expiry, a field that absorbed it, and the off switch emptying the air. Server log,
 * overworld clock, as {@link MixinShotEvents}. Read by
 * {@code ShotBoresOverTimeE2ETest#aRoundKeepsBoringAcrossTicksInsteadOfEndingAtTheSurface}.</p>
 */
@Mixin(ShotRegistry.class)
public abstract class MixinShotRegistryEvents {

    /** {@link MixinShotEvents}'s instrument name — see there for why it is spelled twice. */
    private static final String INSTRUMENT = "shot_events";

    @Inject(method = "end", at = @At("HEAD"), require = 1)
    private void stellurgyTest$ended(long id, ShotEndReason reason, Vec3d where, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordHere("shot_ended", "\"shot\":" + id + ",\"reason\":\"" + reason + "\"");
    }
}
