package dev.stannismod.stellurgy.affs.network;

import dev.stannismod.stellurgy.affs.gui.ContainerShieldConsole;
import dev.stannismod.stellurgy.affs.te.TileEntityShieldConsole;
import dev.stannismod.stellurgy.libvulpes.network.PacketSenderCheck;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class PacketSetShieldResistanceBias implements IMessage {

    private BlockPos pos;
    private double bias;

    public PacketSetShieldResistanceBias() {
    }

    public static PacketSetShieldResistanceBias forConsole(BlockPos pos, double bias) {
        PacketSetShieldResistanceBias packet = new PacketSetShieldResistanceBias();
        packet.pos = pos;
        packet.bias = bias;
        return packet;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        bias = buf.readDouble();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        BlockPos safePos = pos == null ? BlockPos.ORIGIN : pos;
        buf.writeInt(safePos.getX());
        buf.writeInt(safePos.getY());
        buf.writeInt(safePos.getZ());
        buf.writeDouble(bias);
    }

    public static class Handler implements IMessageHandler<PacketSetShieldResistanceBias, IMessage> {

        @Override
        public IMessage onMessage(PacketSetShieldResistanceBias message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> apply(player, message));
            return null;
        }

        /**
         * Only the console's own screen sends this, so the console is the one that screen shows, and only
         * while the sender may still use it: the position the client names must match it, and is never
         * looked up on its own.
         */
        private static void apply(EntityPlayerMP player, PacketSetShieldResistanceBias message) {
            TileEntityShieldConsole console = player.openContainer instanceof ContainerShieldConsole
                    ? ((ContainerShieldConsole) player.openContainer).consoleAt(message.pos, player) : null;
            if (console == null) {
                PacketSenderCheck.refuse(player, "shield resistance bias packet for " + message.pos,
                        "the sender does not have that console's screen open");
                return;
            }
            console.applyShieldEnergyResistanceBias(message.bias);
        }
    }
}
