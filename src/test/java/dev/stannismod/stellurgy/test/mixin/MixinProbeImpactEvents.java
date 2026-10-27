package dev.stannismod.stellurgy.test.mixin;

import dev.stannismod.stellurgy.probe.ProbeImpact;
import dev.stannismod.stellurgy.test.trace.TestTrace;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The probe's crater laid, as the server record {@code probe_laid}: the world {@code dim} and the landing
 * block {@code x}/{@code y}/{@code z} {@code ProbeImpact#lay} was handed, at its tail — once the bowl is dug
 * and the wreck set.
 *
 * <p>SILENT about where the wreck ended up: that is the world's to answer, read it with {@code block at}.</p>
 */
@Mixin(ProbeImpact.class)
public abstract class MixinProbeImpactEvents {

    private static final String LAID = "probe_laid";

    @Inject(method = "lay", at = @At("TAIL"), require = 1)
    private static void stellurgyTest$laid(World world, BlockPos landing, NBTTagCompound record, CallbackInfo ci) {
        TestTrace.instrument(world, LAID);
        TestTrace.record(world, LAID, "\"dim\":" + world.provider.getDimension()
                + ",\"x\":" + landing.getX() + ",\"y\":" + landing.getY() + ",\"z\":" + landing.getZ());
    }
}
