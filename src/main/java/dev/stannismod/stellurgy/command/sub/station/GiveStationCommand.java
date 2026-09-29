package dev.stannismod.stellurgy.command.sub.station;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import dev.stannismod.stellurgy.api.StellurgyItems;
import dev.stannismod.stellurgy.command.sub.StellurgyCommand;
import dev.stannismod.stellurgy.item.ItemStationChip;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

public class GiveStationCommand extends StellurgyCommand {
    @Override
    public String getName() {
        return "give";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.stellurgy.station.give.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1 || args.length > 2) {
            throw wrongUsage(sender);
        }
        EntityPlayerMP player;
        if (args.length == 2) {
            player = getPlayer(server, sender, args[1]);
        } else {
            player = getCommandSenderAsPlayer(sender);
        }
        int stationId = parseInt(args[0]);
        ItemStack stack = new ItemStack(StellurgyItems.itemSpaceStationChip);
        ItemStationChip.setUUID(stack, stationId);
        player.inventory.addItemStackToInventory(stack);
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        return args.length == 2 ? getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames()) : Collections.emptyList();
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return args.length == 2 && index == 2;
    }
}
