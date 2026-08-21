package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.client.KeyBindings;
import zmaster587.advancedRocketry.command.test.SeatDiag;
import zmaster587.advancedRocketry.entity.EntityDummy;

/**
 * The CLIENT half of the pilot-input chain: whether this client even tried to send.
 *
 * <p>The gate refuses silently — a mount that resolves no linked seat simply produces no packet,
 * which from the server's side is indistinguishable from a packet that was sent and lost. So the
 * decision is worth counting, and the counters used to live on the keybind handler itself.</p>
 *
 * <p>Read off the method's own RETURN rather than from inside its branches: it returns {@code true}
 * exactly when the player is piloting a ship this tick, which IS the gate. The closed side is
 * counted only while he rides a seat mount — a walking tick is not a refusal, it is noise, and
 * production drew the same distinction.</p>
 */
@Mixin(KeyBindings.class)
public abstract class MixinKeyBindingsSeatGate {

    @Inject(method = "handleShipPilotInput", at = @At("RETURN"))
    private void arTest$gateDecision(Minecraft mc, EntityPlayerSP player,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            SeatDiag.clientGate(true);
        } else if (player != null && player.getRidingEntity() instanceof EntityDummy) {
            SeatDiag.clientGate(false);
        }
    }

    @Inject(method = "handleShipPilotInput",
            at = @At(value = "INVOKE",
                    target = "Lzmaster587/libVulpes/network/PacketHandler;"
                            + "sendToServer(Lzmaster587/libVulpes/network/BasePacket;)V"))
    private void arTest$inputSent(Minecraft mc, EntityPlayerSP player, CallbackInfo ci) {
        SeatDiag.clientSent();
    }
}
