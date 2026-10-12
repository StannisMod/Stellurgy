package dev.stannismod.stellurgy.test.mixin;

import mcjty.theoneprobe.apiimpl.elements.ElementText;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to one probe line's text AS SENT — translation keys still wrapped in the probe's own
 * markers, before the client renders them — for {@link MixinTopProbeReplyEvents}.
 */
@Mixin(value = ElementText.class, remap = false)
public interface TopElementTextAccessor {

    @Accessor(value = "text", remap = false)
    String stellurgyTest$text();
}
