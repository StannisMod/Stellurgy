package dev.stannismod.stellurgy.command.sub.teleport;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.stations.ISpaceObject;
import dev.stannismod.stellurgy.command.sub.StellurgyCommand;
import dev.stannismod.stellurgy.stations.SpaceObjectManager;
import dev.stannismod.stellurgy.world.util.BasicTeleporter;
import dev.stannismod.stellurgy.libvulpes.util.HashedBlockPosition;

import java.util.Collections;
import java.util.List;

public class GoToStationCommand extends StellurgyCommand {
    @Override
    public String getName() {
        return "station";
    }

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("s");
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.stellurgy.goto.station.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 1) {
            throw wrongUsage(sender);
        }
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        int dim = StellurgyConfiguration.getCurrentConfig().spaceDimId;
        int stationId = parseInt(args[0]);
        ISpaceObject spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStation(stationId);

        if (spaceObject != null) {
            if (player.world.provider.getDimension() != StellurgyConfiguration.getCurrentConfig().spaceDimId) {
                player.changeDimension(dim, new BasicTeleporter(player.getPosition()));
            }
            HashedBlockPosition vec = spaceObject.getSpawnLocation();
            player.setPositionAndUpdate(vec.x, vec.y, vec.z);
        } else {
            throw invalidValue(getName(), stationId); // station <stationIs> doesnt exist
        }
    }
}