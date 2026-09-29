package dev.stannismod.stellurgy.command.sub.teleport;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.DimensionManager;
import dev.stannismod.stellurgy.command.sub.StellurgyCommand;
import dev.stannismod.stellurgy.world.util.TeleporterSeekBlock;

import java.util.Arrays;
import java.util.List;

public class GoToDimensionCommand extends StellurgyCommand {
    @Override
    public String getName() {
        return "dimension";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("dim", "d");
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.stellurgy.goto.dimension.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 1) {
            throw wrongUsage(sender);
        }
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        int dim = parseInt(args[0]);
        if (DimensionManager.isDimensionRegistered(dim)) {
            if (DimensionManager.getWorld(dim) == null) {
                DimensionManager.initDimension(dim);
            }
            player.changeDimension(dim, new TeleporterSeekBlock(player.getPosition()));
        } else {
            throw invalidValue(getName(), dim);
        }
    }
}