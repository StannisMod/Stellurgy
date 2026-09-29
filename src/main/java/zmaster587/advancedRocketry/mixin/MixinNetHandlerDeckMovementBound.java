package zmaster587.advancedRocketry.mixin;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.CPacketPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.integration.vs.DeckMovementBound;

/**
 * The server's own opinion about where a body standing on a deck can be.
 *
 * <h2>Why here</h2>
 *
 * <p>A player's position is decided by his client and accepted by the server; that is the contract
 * of the vanilla movement packet and it is not being changed. What is added is a bound: a body that
 * AR is carrying on a deck can only have reached the region its own speed plus the deck's carry
 * covers, and a declared position outside that region is refused rather than ratified. Measured
 * before this existed: a body launched off an inverted hull declared thirty blocks a tick, and the
 * server wrote down every one of them.</p>
 *
 * <p>Injected at the TAIL of the packet's processing, after vanilla has moved the player: the
 * refusal is then the same correction vanilla applies to its own speed check — the accepted position
 * is restored and the client is told, which the client already knows how to obey.</p>
 *
 * <p>The bound itself lives in {@link DeckMovementBound}, with the reasons for its numbers. This
 * class is only the seam.</p>
 */
@Mixin(NetHandlerPlayServer.class)
public abstract class MixinNetHandlerDeckMovementBound {

    @Shadow
    public EntityPlayerMP player;

    @Shadow
    public abstract void setPlayerLocation(double x, double y, double z, float yaw, float pitch);

    /** Where the server had him standing before it handled this packet — the only anchor a refusal
     *  can honestly be measured against, because a SERVER-side move (a teleport, a dimension change)
     *  moves this reading too and so never looks like a client claim. */
    private double arBoundFromX, arBoundFromY, arBoundFromZ;
    private boolean arBoundHaveFrom;

    @Inject(method = "processPlayer", at = @At("HEAD"))
    private void arDeckMovementBoundBefore(CPacketPlayer packet, CallbackInfo ci) {
        final EntityPlayerMP subject = player;
        arBoundHaveFrom = subject != null;
        if (arBoundHaveFrom) {
            arBoundFromX = subject.posX;
            arBoundFromY = subject.posY;
            arBoundFromZ = subject.posZ;
        }
    }

    @Inject(method = "processPlayer", at = @At("TAIL"))
    private void arDeckMovementBound(CPacketPlayer packet, CallbackInfo ci) {
        final EntityPlayerMP subject = player;
        if (!arBoundHaveFrom || subject == null || subject.world == null) {
            return;
        }
        if (!DeckMovementBound.accepts(subject, arBoundFromX, arBoundFromY, arBoundFromZ,
                subject.posX, subject.posY, subject.posZ)) {
            // Refused: put him back where the server had him and tell his client so. Position only —
            // a refused MOVEMENT says nothing about where he was looking.
            setPlayerLocation(arBoundFromX, arBoundFromY, arBoundFromZ,
                    subject.rotationYaw, subject.rotationPitch);
        }
    }
}
