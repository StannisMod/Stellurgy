package dev.stannismod.stellurgy.command.sub;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import org.apache.commons.lang3.StringUtils;

public abstract class StellurgyCommand extends CommandBase {
    protected CommandException invalidValue(String name, int value) {
        return new CommandException("commands.stellurgy.invalid", StringUtils.capitalize(name), value);
    }

    protected CommandException invalidValue(String name, String value) {
        return new CommandException("commands.stellurgy.invalid", StringUtils.capitalize(name), value);
    }

    protected WrongUsageException wrongUsage(ICommandSender sender) {
        return new WrongUsageException(getUsage(sender));
    }
}
