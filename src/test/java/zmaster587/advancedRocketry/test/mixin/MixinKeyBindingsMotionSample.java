package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.client.KeyBindings;
import zmaster587.advancedRocketry.command.test.MotionTrace;

/**
 * One flight-recorder sample per CLIENT TICK.
 *
 * <p>HEAD, where production took it, and for its reason: a tick is a tick whether or not a screen is
 * up, so the sample is taken ahead of the GUI gate further down the method.</p>
 *
 * <p>Needs nothing production had computed — the client player is reachable from {@code Minecraft} —
 * which is why this moved without touching a local.</p>
 */
@Mixin(KeyBindings.class)
public abstract class MixinKeyBindingsMotionSample {

    @Inject(method = "onClientTick", at = @At("HEAD"))
    private void arTest$clientTickSample(TickEvent.ClientTickEvent event, CallbackInfo ci) {
        // ONE PHASE. `ClientTickEvent` is posted twice per client tick — START and END — and this
        // sampler took both from the day it was written, so the channel ran at 40 Hz against a
        // 20 Hz clock and every per-sample displacement on it was half a tick's worth of ground.
        // Nothing said so: a wall-clock summary reports a doubled rate as a rate, and the two
        // readings built on this channel (a hitch total and an evenness ratio) were computed over
        // the doubled series. Measured 2026-09-21 by the per-TICK index, which named it at once:
        // 120 samples across 61 ticks, maxPerTick 2.
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc == null ? null : mc.player;
        if (player != null) {
            // The PLAYER's own tick count, not the world's time. Both advance once per client tick
            // and neither is driven by this sampler — but the world's is also OVERWRITTEN by the
            // server's periodic time packet, so it repeats a value or skips one whenever the two
            // sides differ by a tick. Measured 2026-09-21: 60 samples across a 60-tick span landed
            // in 59 distinct world-time values, one of them carrying two, which reads as a missed
            // tick and a lurch that never happened. `ticksExisted` is incremented by
            // `Entity.onUpdate` on the client and nothing else writes it.
            net.minecraft.entity.Entity mount = player.getRidingEntity();
            MotionTrace.clientTick(player.ticksExisted,
                    player.posX, player.posY, player.posZ,
                    mount != null,
                    mount == null ? 0.0 : mount.posX,
                    mount == null ? 0.0 : mount.posY,
                    mount == null ? 0.0 : mount.posZ);
        }
    }
}
