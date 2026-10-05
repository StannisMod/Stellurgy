package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.CPacketPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.integration.vs.DeckFrameTick;
import dev.stannismod.stellurgy.test.trace.EntityTrace;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The other writer of a deck-held player's server {@code onGround}: his client's claim, one record per
 * movement packet the server thread handled while a deck held him ({@code deck_player_claim}). Read
 * by {@code VSCrewRidesRollingDeckE2ETest} beside its ground claims.
 *
 * <p>Read beside {@code deck_player_update} on the same server clock, it says which of the two a
 * single read of {@code onGround} saw. At the RETURN of {@code processPlayer}, so it reads what the
 * packet path left; {@code motion*} is the world velocity after it, which the next update rotates
 * into the deck's frame. Test source set: absent from a released jar.</p>
 */
@Mixin(NetHandlerPlayServer.class)
public abstract class MixinDeckPlayerClaim {

    @Shadow
    public EntityPlayerMP player;

    @Inject(method = "processPlayer", at = @At("HEAD"))
    private void stellurgyTest$claimHead(CPacketPlayer packet, CallbackInfo ci) {
        EntityPlayerMP self = this.player;
        if (self == null || !self.getServerWorld().isCallingFromMinecraftThread()) {
            return;
        }
        // What the packet itself declared, before the physics mod's HEAD rewrites the packet from it.
        org.valkyrienskies.mod.common.network.PlayerMovementData data =
                ((org.valkyrienskies.mod.common.network.IHasPlayerMovementData) packet).getPlayerMovementData();
        EntityTrace.DeckPlayerUpdate declared =
                EntityTrace.memory(self, EntityTrace.DeckPlayerUpdate.class, EntityTrace.DeckPlayerUpdate::new);
        declared.claimShip = data == null || data.getLastTouchedShipId() == null
                ? null : data.getLastTouchedShipId().toString();
        declared.claimTicksSinceTouched = data == null ? -1 : data.getTicksSinceTouchedLastShip();
        declared.claimInShipY = data == null ? Double.NaN : data.getPlayerPosInShip().y();
        EntityTrace.DeckPlayerUpdate memory =
                EntityTrace.memory(self, EntityTrace.DeckPlayerUpdate.class, EntityTrace.DeckPlayerUpdate::new);
        memory.claimHeadMotionX = self.motionX;
        memory.claimHeadMotionY = self.motionY;
        memory.claimHeadMotionZ = self.motionZ;
    }

    @Inject(method = "processPlayer", at = @At("RETURN"))
    private void stellurgyTest$claim(CPacketPlayer packet, CallbackInfo ci) {
        EntityPlayerMP self = this.player;
        if (self == null || !self.getServerWorld().isCallingFromMinecraftThread()) {
            return;
        }
        TestTrace.instrument(self, "deck_player_claim_events");
        if (!DeckFrameTick.holds(self)) {
            return;
        }
        // A packet that carries no position answers its default for every coordinate; NaN is never
        // equal to itself, so "moving" is whether the packet carried one.
        boolean moving = !Double.isNaN(packet.getX(Double.NaN));
        EntityTrace.DeckPlayerUpdate memory =
                EntityTrace.memory(self, EntityTrace.DeckPlayerUpdate.class, EntityTrace.DeckPlayerUpdate::new);
        TestTrace.record(self, "deck_player_claim", "\"e\":" + self.getEntityId()
                + ",\"headMotionX\":" + memory.claimHeadMotionX + ",\"headMotionY\":" + memory.claimHeadMotionY
                + ",\"headMotionZ\":" + memory.claimHeadMotionZ
                + ",\"declaredShip\":" + (memory.claimShip == null ? "null" : "\"" + memory.claimShip + "\"")
                + ",\"declaredTicksSinceTouched\":" + memory.claimTicksSinceTouched
                + ",\"declaredInShipY\":" + memory.claimInShipY
                + ",\"claimGround\":" + packet.isOnGround()
                + ",\"moving\":" + moving
                + ",\"y\":" + self.posY
                + ",\"motionX\":" + self.motionX + ",\"motionY\":" + self.motionY
                + ",\"motionZ\":" + self.motionZ
                + ",\"onGround\":" + self.onGround);
    }
}
