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
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc == null ? null : mc.player;
        if (player != null) {
            MotionTrace.clientTick(player.posX, player.posY, player.posZ,
                    player.getRidingEntity() != null);
        }
    }
}
