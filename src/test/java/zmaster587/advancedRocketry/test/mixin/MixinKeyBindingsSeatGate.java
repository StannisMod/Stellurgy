package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.client.KeyBindings;
import zmaster587.advancedRocketry.entity.EntityDummy;
import zmaster587.advancedRocketry.test.trace.SeatGateWindow;

/**
 * The CLIENT half of the pilot-input chain: whether this client even tried to send.
 *
 * <p>The gate refuses silently — a mount that resolves no linked seat simply produces no packet,
 * which from the server's side is indistinguishable from a packet that was sent and lost. So the
 * decision is worth counting — into every open {@link SeatGateWindow}.</p>
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
            SeatGateWindow.gate(true);
        } else if (player != null && player.getRidingEntity() instanceof EntityDummy) {
            SeatGateWindow.gate(false);
        }
    }

    @Inject(method = "handleShipPilotInput",
            at = @At(value = "INVOKE",
                    target = "Lzmaster587/libVulpes/network/PacketHandler;"
                            + "sendToServer(Lzmaster587/libVulpes/network/BasePacket;)V"))
    // CallbackInfoReturnable, not CallbackInfo: the TARGET returns a boolean, and mixin requires the
    // returnable form for ANY injection into it — including one in the middle of the method, which is
    // where this one sits. Measured 2026-08-21: the plain form is refused with "Invalid descriptor …
    // CallbackInfoReturnable is required!", KeyBindings then fails to load, and the whole client
    // crashes at postInit registering keybinds.
    private void arTest$inputSent(Minecraft mc, EntityPlayerSP player,
                                  CallbackInfoReturnable<Boolean> cir) {
        SeatGateWindow.sent();
    }
}
