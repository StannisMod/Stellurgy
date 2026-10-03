package dev.stannismod.stellurgy.test.trace;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import dev.stannismod.stellurgy.libvulpes.network.BasePacket;

/**
 * A mod packet that does nothing but say which player it was executed for: {@code client_mod_packet_ran}.
 *
 * <p>The stimulus for the question "is a mod packet executed for the player the client HAS, or for one
 * it had when the packet arrived". Its {@code executeClient} records the dimension of the world the
 * player it was handed lives in ({@code playerDim}), and whether that player is the client's current
 * one ({@code current}) — the two things a real packet goes on to use, since a machine packet looks its
 * tile up in {@code player.world}.</p>
 *
 * <p>Never registered on the channel and never sent over the wire: a scenario hands it straight to the
 * channel's client handler, which is the step under test. Client log. Read by
 * {@code ModPacketDeliveryClientGroupTest}. Test source set: absent from a released jar.</p>
 */
public final class PlayerProbePacket extends BasePacket {

    private static final String INSTRUMENT = "client_mod_packet_events";

    @Override
    public void write(ByteBuf out) {
    }

    @Override
    public void readClient(ByteBuf in) {
    }

    @Override
    public void read(ByteBuf in) {
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void executeClient(EntityPlayer thePlayer) {
        TestTrace.instrumentHere(INSTRUMENT);
        Minecraft mc = Minecraft.getMinecraft();
        TestTrace.recordHere("client_mod_packet_ran",
                "\"playerDim\":" + (thePlayer == null || thePlayer.world == null ? "null"
                        : String.valueOf(thePlayer.world.provider.getDimension()))
                        + ",\"current\":" + (thePlayer != null && thePlayer == mc.player));
    }

    @Override
    public void executeServer(EntityPlayerMP player) {
    }
}
