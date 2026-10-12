package dev.stannismod.stellurgy.test.mixin;

import mcjty.theoneprobe.apiimpl.ProbeInfo;
import mcjty.theoneprobe.network.PacketReturnInfo;
import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to what TheOneProbe's server sent a client about one block — its dimension, the
 * position and the probe info — for {@link MixinTopProbeReplyEvents}. The packet keeps all three
 * private and exposes none; this reads the fields it already carries and changes nothing.
 */
@Mixin(value = PacketReturnInfo.class, remap = false)
public interface TopProbeReplyAccessor {

    @Accessor(value = "dim", remap = false)
    int stellurgyTest$dim();

    @Accessor(value = "pos", remap = false)
    BlockPos stellurgyTest$pos();

    @Accessor(value = "probeInfo", remap = false)
    ProbeInfo stellurgyTest$probeInfo();
}
