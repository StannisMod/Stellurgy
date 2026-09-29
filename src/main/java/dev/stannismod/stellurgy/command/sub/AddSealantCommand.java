package dev.stannismod.stellurgy.command.sub;

import net.minecraft.block.Block;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.util.SealableBlockHandler;

import java.util.Collections;
import java.util.List;

public class AddSealantCommand extends StellurgyCommand {
    @Override
    public String getName() {
        return "addSealant";
    }

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("addsealant");
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.stellurgy.addsealant.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 0) {
            throw wrongUsage(sender);
        }
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        Block block = Block.getBlockFromItem(player.getHeldItemMainhand().getItem());
        if (block == Blocks.AIR) {
            throw new CommandException("commands.stellurgy.addsealant.invalid");
        }
        if (SealableBlockHandler.INSTANCE.getOverriddenSealableBlocks().contains(block)) {
            throw new CommandException("commands.stellurgy.addsealant.exists", block.getLocalizedName());
        }
        StellurgyConfiguration.getCurrentConfig().addSealedBlock(block);
        sender.sendMessage(new TextComponentTranslation("commands.stellurgy.addsealant.success", block.getLocalizedName()));
    }
}
