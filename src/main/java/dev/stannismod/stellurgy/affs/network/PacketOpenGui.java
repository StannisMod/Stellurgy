package dev.stannismod.stellurgy.affs.network;

import dev.stannismod.stellurgy.affs.AdvancedForceFieldSystem;
import dev.stannismod.stellurgy.affs.gui.ContainerShieldConsole;
import dev.stannismod.stellurgy.affs.te.TileEntityShieldConsole;
import dev.stannismod.stellurgy.libvulpes.network.PacketSenderCheck;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class PacketOpenGui implements IMessage {

    private int guiId;
    private BlockPos pos;

    public PacketOpenGui() {
    }

    public static PacketOpenGui forBlock(int guiId, BlockPos pos) {
        PacketOpenGui packet = new PacketOpenGui();
        packet.guiId = guiId;
        packet.pos = pos;
        return packet;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        guiId = buf.readInt();
        pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(guiId);
        BlockPos targetPos = pos == null ? BlockPos.ORIGIN : pos;
        buf.writeInt(targetPos.getX());
        buf.writeInt(targetPos.getY());
        buf.writeInt(targetPos.getZ());
    }

    public static class Handler implements IMessageHandler<PacketOpenGui, IMessage> {

        @Override
        public IMessage onMessage(PacketOpenGui message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> apply(player, message));
            return null;
        }

        /**
         * A client asks for one screen only: a console's network map, from that console's own screen.
         * So the console is the one the sender's open screen shows, and only while he may still use it;
         * the position he names must match it and is never looked up on its own — opening a screen
         * builds its container, and building one for any position he named would load that chunk.
         */
        private static void apply(EntityPlayerMP player, PacketOpenGui message) {
            if (message.guiId != AdvancedForceFieldSystem.GUI_NETWORK_MAP) {
                PacketSenderCheck.refuse(player, "open-screen packet " + message.guiId + " for " + message.pos,
                        "no screen of the client's asks the server to open that one");
                return;
            }
            TileEntityShieldConsole console = player.openContainer instanceof ContainerShieldConsole
                    ? ((ContainerShieldConsole) player.openContainer).consoleAt(message.pos, player) : null;
            if (console == null) {
                PacketSenderCheck.refuse(player, "open-screen packet " + message.guiId + " for " + message.pos,
                        "the sender does not have that console's screen open");
                return;
            }
            BlockPos at = console.getPos();
            AdvancedForceFieldSystem.openAffsGui(player, message.guiId, player.getServerWorld(),
                    at.getX(), at.getY(), at.getZ());
        }
    }
}
