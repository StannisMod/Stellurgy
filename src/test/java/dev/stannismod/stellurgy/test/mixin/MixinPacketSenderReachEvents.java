package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.stannismod.stellurgy.libvulpes.network.PacketSenderCheck;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The server's verdict on whether a client could reach the block its packet names, recorded where it is
 * given.
 *
 * <p>{@code container_reach_judged} is written as {@code PacketSenderCheck#withinContainerReach}
 * returns — the check every machine packet from a client passes before any machine sees it. The verdict
 * is the method's own return value, never recomputed here.</p>
 *
 * <p>Fields: {@code player} the sender's name; {@code pos} the block as {@code x,y,z}; {@code reachable}
 * the verdict. SILENT about a packet refused before this check (wrong world, a header that did not
 * decode) — a far press with no record here was refused by something else, or never arrived.</p>
 */
@Mixin(value = PacketSenderCheck.class, remap = false)
public abstract class MixinPacketSenderReachEvents {

    private static final String INSTRUMENT = "container_reach_judged";

    @Inject(method = "withinContainerReach", at = @At("RETURN"), require = 1)
    private static void stellurgyTest$reachJudged(EntityPlayerMP sender, BlockPos pos,
                                                  CallbackInfoReturnable<Boolean> verdict) {
        TestTrace.instrument(sender.world, INSTRUMENT);
        TestTrace.record(sender.world, "container_reach_judged", "\"player\":\"" + sender.getName()
                + "\",\"pos\":\"" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                + "\",\"reachable\":" + verdict.getReturnValue());
    }
}
