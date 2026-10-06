package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.network.PacketDeckCapture;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * A deck seed ARRIVING at the client, before the client decides anything about it.
 *
 * <p>{@code deck_seed_decided} is recorded where the client weighs a pending seed, and a seed that
 * never becomes pending leaves no record there at all: "the client weighed it and did nothing" and
 * "the client never had it" were the same empty window. This record is taken at the head of the
 * packet's client handler, so its presence says the seed reached the client and its fields say what
 * the handler had to work with — whether there was a player to give it to, and on which thread.
 * Read by {@code VSCrewRelogPersistenceTest}'s relog links.</p>
 *
 * <p>SILENT about: what became of the seed afterwards (that is {@code deck_seed_decided}), and a
 * seed lost on the wire.</p>
 */
@Mixin(PacketDeckCapture.class)
public abstract class MixinPacketDeckCaptureEvents {

    private static final String INSTRUMENT = "deck_seed_packet_events";

    @Shadow
    private String shipId;

    @Shadow
    private boolean restore;

    @Inject(method = "executeClient", at = @At("HEAD"))
    private void stellurgyTest$seedArrived(EntityPlayer thePlayer, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordHere("deck_seed_arrived", "\"ship\":\"" + TestTrace.json(String.valueOf(shipId))
                + "\",\"restore\":" + restore
                + ",\"player\":" + (thePlayer != null)
                + ",\"thread\":\"" + TestTrace.json(Thread.currentThread().getName()) + "\"");
    }
}
