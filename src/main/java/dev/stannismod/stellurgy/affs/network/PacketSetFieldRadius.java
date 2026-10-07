package dev.stannismod.stellurgy.affs.network;

import dev.stannismod.stellurgy.affs.gui.ContainerFieldGenerator;
import dev.stannismod.stellurgy.affs.te.TileEntityFieldGenerator;
import dev.stannismod.stellurgy.libvulpes.network.PacketSenderCheck;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class PacketSetFieldRadius implements IMessage {

    private BlockPos pos;
    private int radius;

    public PacketSetFieldRadius() {
    }

    public PacketSetFieldRadius(BlockPos pos, int radius) {
        this.pos = pos;
        this.radius = radius;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        radius = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(pos.getX());
        buf.writeInt(pos.getY());
        buf.writeInt(pos.getZ());
        buf.writeInt(radius);
    }

    public static class Handler implements IMessageHandler<PacketSetFieldRadius, IMessage> {
        @Override
        public IMessage onMessage(PacketSetFieldRadius message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> apply(player, message));
            return null;
        }

        /**
         * Only the generator's own screen sends this, so the generator is the one that screen shows, and
         * only while the sender may still use it: the position the client names must match it, and is
         * never looked up on its own.
         */
        private static void apply(EntityPlayerMP player, PacketSetFieldRadius message) {
            TileEntityFieldGenerator generator = player.openContainer instanceof ContainerFieldGenerator
                    ? ((ContainerFieldGenerator) player.openContainer).generatorAt(message.pos, player) : null;
            if (generator == null) {
                PacketSenderCheck.refuse(player, "field radius packet for " + message.pos,
                        "the sender does not have that generator's screen open");
                return;
            }
            // Сервер валидирует радиус и пересобирает поле сам.
            generator.setRadius(message.radius);
        }
    }
}
