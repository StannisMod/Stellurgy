package dev.stannismod.stellurgy.command.sub.planet;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.WorldServer;
import dev.stannismod.stellurgy.command.sub.StellurgyCommand;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.network.PacketDimInfo;
import zmaster587.libVulpes.network.PacketHandler;

public class PlanetDeleteCommand extends StellurgyCommand {
    @Override
    public String getName() {
        return "delete";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.stellurgy.planet.delete.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 1) {
            throw wrongUsage(sender);
        }
        int dimId = parseInt(args[0]);
        if (!DimensionManager.getInstance().isDimensionCreated(dimId)) {
            throw invalidValue("Planet with id", dimId);
        }
        WorldServer world = net.minecraftforge.common.DimensionManager.getWorld(dimId);
        if (world == null || world.playerEntities.isEmpty()) {
            DimensionManager.getInstance().deleteDimension(dimId);
            PacketHandler.sendToAll(new PacketDimInfo(dimId, null));
            sender.sendMessage(new TextComponentTranslation("commands.stellurgy.planet.delete.success", dimId));
        } else {
            //If the world still has players abort and list players
            ITextComponent message = new TextComponentTranslation("commands.stellurgy.planet.delete.invalid");
            for (EntityPlayer player : world.playerEntities) {
                message.appendText("\n");
                message.appendSibling(player.getDisplayName());
            }
            sender.sendMessage(message);
        }
    }
}
