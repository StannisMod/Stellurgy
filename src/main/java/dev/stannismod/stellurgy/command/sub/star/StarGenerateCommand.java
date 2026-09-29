package dev.stannismod.stellurgy.command.sub.star;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;
import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.command.sub.StellurgyCommand;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.network.PacketStellarInfo;
import zmaster587.libVulpes.network.PacketHandler;

public class StarGenerateCommand extends StellurgyCommand {
    @Override
    public String getName() {
        return "generate";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.stellurgy.star.generate.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 4) {
            throw wrongUsage(sender);
        }
        String name = args[0];
        int temp = parseInt(args[1]);
        int x = parseInt(args[2]);
        int z = parseInt(args[3]);
        StellarBody star = new StellarBody();
        star.setTemperature(temp);
        star.setPosX(x);
        star.setPosZ(z);
        star.setName(name);
        star.setId(DimensionManager.getInstance().getNextFreeStarId());
        if (star.getId() != -1) {
            DimensionManager.getInstance().addStar(star);
            PacketHandler.sendToAll(new PacketStellarInfo(star.getId(), star));
            sender.sendMessage(new TextComponentTranslation("commands.stellurgy.star.generate.success"));
        } else {
            throw new CommandException("commands.stellurgy.star.generate.invalid");
        }
    }
}
