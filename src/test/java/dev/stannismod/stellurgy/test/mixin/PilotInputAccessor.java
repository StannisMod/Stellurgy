package dev.stannismod.stellurgy.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import dev.stannismod.stellurgy.client.PilotInput;

/**
 * The held deck look of the local player, for the recorders. Production keeps it package-private on
 * {@link PilotInput} and exposes no getter, because a getter kept public for a test is a test's
 * surface in shipping code; an accessor mixin reads it from the test source set instead.
 */
@Mixin(PilotInput.class)
public interface PilotInputAccessor {

    @Accessor(value = "deckYawDeg", remap = false)
    double stellurgyTest$deckYawDeg();

    @Accessor(value = "deckPitchDeg", remap = false)
    double stellurgyTest$deckPitchDeg();
}
