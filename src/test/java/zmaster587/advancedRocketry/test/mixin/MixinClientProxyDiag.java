package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.util.SoundCategory;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.client.ClientProxy;
import zmaster587.advancedRocketry.command.test.ClientDiag;

/**
 * Reads back the master volume the harness client actually ended up with.
 *
 * <p>Production mutes the client and, until 2026-08-21, also published the resulting level into a
 * static so a test could confirm the mute TOOK rather than merely RAN. The reading is worth having;
 * publishing it is not production's job. This asks {@code GameSettings} the same question at the
 * same instant, from the test side.</p>
 *
 * <p>TAIL, so it runs after the mute and only on the path that actually muted — the method returns
 * early both when the work is already done and when this is not a harness client.</p>
 */
@Mixin(ClientProxy.class)
public abstract class MixinClientProxyDiag {

    @Inject(method = "muteTestClientSound", at = @At("TAIL"))
    private static void arTest$readBackMasterVolume(TickEvent.ClientTickEvent event, CallbackInfo ci) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.gameSettings != null) {
            ClientDiag.masterVolume(mc.gameSettings.getSoundLevel(SoundCategory.MASTER));
        }
    }
}
