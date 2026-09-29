package dev.stannismod.stellurgy.command.sub.star;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;
import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.command.sub.StellurgyCommand;
import dev.stannismod.stellurgy.dimension.DimensionManager;

public class StarListCommand extends StellurgyCommand {
    @Override
    public String getName() {
        return "list";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.stellurgy.star.list.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        for (StellarBody star : DimensionManager.getInstance().getStars()) {
            sender.sendMessage(new TextComponentTranslation("commands.stellurgy.star.list.entry",
                    star.getId(), star.getName(), star.getNumPlanets()));
        }
    }
}
