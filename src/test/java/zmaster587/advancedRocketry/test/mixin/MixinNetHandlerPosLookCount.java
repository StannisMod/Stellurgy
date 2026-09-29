package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.SPacketPlayerPosLook;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.test.trace.DeckCameraState;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * Server PosLook packets actually applied on the client MAIN thread.
 *
 * <p>A smoothness discriminator: an applied PosLook collapses the client's prev-&gt;pos render
 * interpolation for that tick, because the handler writes {@code prev = pos}. A steadily climbing
 * count while a body walks or jumps a deck names the server echo as the writer of a felt
 * stepping.</p>
 *
 * <p>Production carried this as a counter on {@code ShipFrameCamera}, incremented from its own
 * camera-repin mixin. Nothing production-side read it. It is the same count, taken from the same
 * handler at the same point — this mixin targets the vanilla method directly rather than riding on
 * an AR mixin, so production's repin hook is left doing only its own job.</p>
 *
 * <p>The RETURN also fires on the netty-thread early return, which applies nothing; the thread
 * check is production's own and is what makes this count real applies.</p>
 *
 * <p>Client. Test source set.</p>
 */
@Mixin(NetHandlerPlayClient.class)
public abstract class MixinNetHandlerPosLookCount {

    @Inject(method = "handlePlayerPosLook", at = @At("RETURN"))
    private void arTest$posLookApplied(SPacketPlayerPosLook packet, CallbackInfo ci) {
        TestTrace.instrumentHere("poslook_applies");
        if (Minecraft.getMinecraft().isCallingFromMinecraftThread()) {
            DeckCameraState.notePosLookApplied();
        }
    }
}
