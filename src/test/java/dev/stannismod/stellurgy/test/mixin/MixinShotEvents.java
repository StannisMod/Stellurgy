package dev.stannismod.stellurgy.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.projectile.Shot;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * A thrown round paying energy, as an event: {@code shot_energy_spent}.
 *
 * <p>The HEAD of {@code Shot.setImpactEnergy}, recorded only when the value it is handed differs from
 * the one the round carries: {@code from}, {@code to} (clamped at zero, as the setter clamps) and the
 * round's {@code age}. It is the one writer of the field after launch, so a tick with no record is a
 * tick the round paid nothing. A round only lives on the server, so the record lands in the SERVER
 * log stamped with the overworld clock — the {@code tick} a reader orders it by against
 * {@code shot_ended} ({@link MixinShotRegistryEvents}). Read by
 * {@code ShotBoresOverTimeTest#aRoundKeepsBoringAcrossTicksInsteadOfEndingAtTheSurface}.</p>
 *
 * <p>SILENT about the final tick of a bore that stops the round outright — the substrate returns
 * before writing the residual, and the ending is {@code shot_ended}'s to say. {@code shot} is the
 * round's id in its world's registry, unique per WORLD rather than per server; the round does not know
 * its world, so a reader in more than one dimension must narrow by something else.</p>
 */
@Mixin(Shot.class)
public abstract class MixinShotEvents {

    /** The same name {@link MixinShotRegistryEvents} reports under: both seams are method HEADs
     *  required to match, so neither can go quiet alone. Private because Mixin refuses a non-private
     *  static in a mixin class; the two spellings are the one thing to keep equal by hand. */
    private static final String INSTRUMENT = "shot_events";

    @Inject(method = "setImpactEnergy", at = @At("HEAD"), require = 1)
    private void stellurgyTest$energySpent(int newImpactEnergy, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        Shot self = (Shot) (Object) this;
        int to = Math.max(0, newImpactEnergy);
        if (to == self.getImpactEnergy()) {
            return;
        }
        TestTrace.recordHere("shot_energy_spent", "\"shot\":" + self.getId()
                + ",\"age\":" + self.getAge()
                + ",\"from\":" + self.getImpactEnergy()
                + ",\"to\":" + to);
    }
}
