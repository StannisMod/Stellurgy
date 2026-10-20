package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.stannismod.stellurgy.entity.EntityRocket;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The rocket's verdict on a client's packet, recorded where the rocket gives it.
 *
 * <p>{@code rocket_client_gate} is written as {@code EntityRocket#acceptsFromClient} returns: the one
 * place a rocket, or a rocket subclass, decides whether a packet a client sent is taken. The verdict
 * is the method's own return value, taken as it returns, never recomputed here.</p>
 *
 * <p>Fields: {@code e} the rocket's entity id; {@code id} the packet id; {@code sender} the sending
 * player's entity id, {@code -1} for none; {@code accepted} the verdict.</p>
 *
 * <p>SILENT about a client packet that never reaches the gate — dropped by the channel's own checks
 * first, or handled by a path that does not ask it. That silence is the subject a test of the gate's
 * reach reads: a packet delivered with no record here was decided by nobody.</p>
 */
@Mixin(EntityRocket.class)
public abstract class MixinEntityRocketClientGateEvents {

    private static final String INSTRUMENT = "rocket_client_gate";

    @Inject(method = "acceptsFromClient", at = @At("RETURN"), require = 1)
    private void stellurgyTest$clientGateDecided(EntityPlayer player, byte id,
                                                 CallbackInfoReturnable<Boolean> verdict) {
        EntityRocket self = (EntityRocket) (Object) this;
        TestTrace.instrument(self, INSTRUMENT);
        TestTrace.record(self, "rocket_client_gate", "\"e\":" + self.getEntityId()
                + ",\"id\":" + id
                + ",\"sender\":" + (player == null ? -1 : player.getEntityId())
                + ",\"accepted\":" + verdict.getReturnValue());
    }
}
