package dev.stannismod.stellurgy.test.mixin;

import java.util.List;

import mcjty.theoneprobe.api.IElement;
import mcjty.theoneprobe.apiimpl.elements.AbstractElementPanel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to the elements a probe-info panel holds, for {@link MixinTopProbeReplyEvents}: the
 * panel renders them and lists them to nobody.
 */
@Mixin(value = AbstractElementPanel.class, remap = false)
public interface TopElementPanelAccessor {

    @Accessor(value = "children", remap = false)
    List<IElement> stellurgyTest$children();
}
