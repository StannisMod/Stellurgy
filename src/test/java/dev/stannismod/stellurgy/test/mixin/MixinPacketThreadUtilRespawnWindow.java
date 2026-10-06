package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketThreadUtil;
import net.minecraft.network.play.server.SPacketRespawn;
import net.minecraft.util.IThreadListener;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.libvulpes.network.BasePacket;
import dev.stannismod.stellurgy.test.trace.PlayerProbePacket;
import dev.stannismod.stellurgy.test.trace.RespawnPacketArming;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * Opens the window between a client respawn being QUEUED and being APPLIED, and hands a mod packet
 * into it: {@code client_respawn_queued}.
 *
 * <p>Vanilla's {@code checkThreadAndEnqueue} is how every play packet leaves the network thread: it
 * queues the packet's processing on the client thread and returns. For {@code SPacketRespawn} the
 * processing builds a new player in a new world, so between this call and the client thread reaching
 * the queued task the client still holds the OLD player. A mod packet that arrives in that window is
 * the case the channel's client handler must get right, and nothing in a game holds the window open
 * long enough to land one in it on purpose. This injects right AFTER the respawn's task is queued,
 * still on the network thread, and — when a scenario has armed it ({@code RespawnPacketArming}) —
 * hands a {@code PlayerProbePacket} to {@code BasePacket.BasePacketHandlerClient#onMessage}, the
 * production handler, exactly as the channel would on a packet's arrival.</p>
 *
 * <p>The record carries {@code toDim} (the respawn's destination) and {@code playerDim}, the dimension
 * of the world of the player the client holds at that moment — the evidence that the window was open.
 * Written only for an armed respawn. Client log. SILENT about every respawn nobody armed, and about
 * the decode a real arrival runs before the handler.</p>
 */
@Mixin(PacketThreadUtil.class)
public abstract class MixinPacketThreadUtilRespawnWindow {

    private static final String INSTRUMENT = "client_respawn_window_events";

    @Inject(method = "checkThreadAndEnqueue", require = 1,
            at = @At(value = "INVOKE", shift = At.Shift.AFTER,
                    target = "Lnet/minecraft/util/IThreadListener;addScheduledTask(Ljava/lang/Runnable;)Lcom/google/common/util/concurrent/ListenableFuture;"))
    private static void stellurgyTest$respawnQueued(Packet<?> packetIn, INetHandler processor,
                                                    IThreadListener scheduler, CallbackInfo ci) {
        if (!(packetIn instanceof SPacketRespawn) || !(scheduler instanceof Minecraft)) {
            return;
        }
        TestTrace.instrumentHere(INSTRUMENT);
        if (!RespawnPacketArming.take()) {
            return;
        }
        EntityPlayerSP current = ((Minecraft) scheduler).player;
        TestTrace.recordHere("client_respawn_queued",
                "\"toDim\":" + ((SPacketRespawn) packetIn).getDimensionID()
                        + ",\"playerDim\":" + (current == null || current.world == null ? "null"
                        : String.valueOf(current.world.provider.getDimension())));
        new BasePacket.BasePacketHandlerClient().onMessage(new PlayerProbePacket(), null);
    }
}
