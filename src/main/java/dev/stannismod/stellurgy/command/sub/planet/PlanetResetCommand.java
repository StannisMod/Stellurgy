package dev.stannismod.stellurgy.command.sub.planet;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import dev.stannismod.stellurgy.command.sub.StellurgyCommand;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.network.PacketDimInfo;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;

public class PlanetResetCommand extends StellurgyCommand {
    @Override
    public String getName() {
        return "reset";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.stellurgy.planet.reset.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length > 1) {
            throw wrongUsage(sender);
        }
        int dimId;
        if (args.length == 0) {
            Entity entity = sender.getCommandSenderEntity();
            if (entity == null) {
                throw wrongUsage(sender);
            }
            dimId = entity.dimension;
        } else {
            dimId = parseInt(args[0]);
        }
        if (!DimensionManager.getInstance().isDimensionCreated(dimId)) {
            throw invalidValue("Planet with id", dimId);
        }
        DimensionManager.getInstance().getDimensionProperties(dimId).resetProperties();
        PacketHandler.sendToAll(new PacketDimInfo(dimId, DimensionManager.getInstance().getDimensionProperties(dimId)));
    }
}
