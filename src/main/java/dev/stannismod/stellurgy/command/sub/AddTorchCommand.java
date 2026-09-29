package dev.stannismod.stellurgy.command.sub;

import net.minecraft.block.Block;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;

import java.util.Collections;
import java.util.List;

public class AddTorchCommand extends StellurgyCommand {
    @Override
    public String getName() {
        return "addTorch";
    }

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("addtorch");
    }
    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.stellurgy.addtorch.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 0) {
            throw wrongUsage(sender);
        }
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        Block block = Block.getBlockFromItem(player.getHeldItemMainhand().getItem());
        if (block == Blocks.AIR) {
            throw new CommandException("commands.stellurgy.addtorch.invalid");
        }
        if (StellurgyConfiguration.getCurrentConfig().torchBlocks.contains(block)) {
            throw new CommandException("commands.stellurgy.addtorch.exists", block.getLocalizedName());
        }
        StellurgyConfiguration.getCurrentConfig().addTorchblock(block);
        sender.sendMessage(new TextComponentTranslation("commands.stellurgy.addtorch.success", block.getLocalizedName()));
    }
}
