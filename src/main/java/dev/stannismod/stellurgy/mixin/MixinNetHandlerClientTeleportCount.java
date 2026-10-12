package dev.stannismod.stellurgy.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.SPacketPlayerPosLook;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.integration.vs.DeckHeld;

/**
 * Counts every teleport the client applies that puts the player it plays somewhere new, on that
 * player.
 *
 * <p>Not counted: a position-look that puts him where the previous one did. The server sends those
 * as vanilla's re-send of an unconfirmed teleport and as its "moved too quickly" correction after a
 * long one — both aimed at the point the last teleport already gave him, while he may have moved a
 * hair since, which is why the comparison is with that point and not with where he stands. The
 * handler's first line re-queues it from the network thread onto the client thread by throwing, so
 * the TAIL is reached only by the pass that applied the position.</p>
 */
@Mixin(NetHandlerPlayClient.class)
public abstract class MixinNetHandlerClientTeleportCount {

    /** Where the last applied position-look put the player; NaN before the first. */
    @Unique
    private double stellurgy$lastX = Double.NaN, stellurgy$lastY = Double.NaN, stellurgy$lastZ = Double.NaN;

    @Inject(method = "handlePlayerPosLook", at = @At("TAIL"))
    private void stellurgy$countTeleport(SPacketPlayerPosLook packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) {
            return;
        }
        double x = mc.player.posX, y = mc.player.posY, z = mc.player.posZ;
        if (x != stellurgy$lastX || y != stellurgy$lastY || z != stellurgy$lastZ) {
            ((DeckHeld) mc.player).stellurgy$countTeleport();
        }
        stellurgy$lastX = x;
        stellurgy$lastY = y;
        stellurgy$lastZ = z;
    }
}
