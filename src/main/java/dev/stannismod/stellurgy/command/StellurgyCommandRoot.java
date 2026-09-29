package dev.stannismod.stellurgy.command;

import net.minecraft.command.ICommandSender;
import net.minecraftforge.server.command.CommandTreeBase;
import net.minecraftforge.server.command.CommandTreeHelp;
import dev.stannismod.stellurgy.command.sub.AddSealantCommand;
import dev.stannismod.stellurgy.command.sub.AddTorchCommand;
import dev.stannismod.stellurgy.command.sub.FillDataCommand;
import dev.stannismod.stellurgy.command.sub.ReloadRecipesCommand;
import dev.stannismod.stellurgy.command.sub.SetGravityCommand;
import dev.stannismod.stellurgy.command.sub.dev.DevCommand;
import dev.stannismod.stellurgy.command.sub.planet.PlanetCommand;
import dev.stannismod.stellurgy.command.sub.redirect.WeatherCommand;
import dev.stannismod.stellurgy.command.sub.star.StarCommand;
import dev.stannismod.stellurgy.command.sub.station.StationCommand;
import dev.stannismod.stellurgy.command.sub.teleport.FetchCommand;
import dev.stannismod.stellurgy.command.sub.teleport.GoToCommand;
import dev.stannismod.stellurgy.command.sub.universe.UniverseCommand;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class StellurgyCommandRoot extends CommandTreeBase {
    private final List<String> aliases;

    public StellurgyCommandRoot() {
        aliases = new ArrayList<>();
        aliases.add("stellurgy");
        aliases.add("advrocketry");
        aliases.add("ar");
        aliases.add("stellurgy");

        addSubcommand(new WeatherCommand());
        addSubcommand(new AddSealantCommand());
        addSubcommand(new AddTorchCommand());
        addSubcommand(new ReloadRecipesCommand());
        addSubcommand(new SetGravityCommand());
        addSubcommand(new FetchCommand());
        addSubcommand(new PlanetCommand());
        addSubcommand(new StarCommand());
        addSubcommand(new StationCommand());
        addSubcommand(new GoToCommand());
        addSubcommand(new FillDataCommand());
        addSubcommand(new UniverseCommand());
        addSubcommand(new DevCommand());

        addSubcommand(new CommandTreeHelp(this));
    }

    @Override
    public String getName() {
        return "stellurgy";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/stellurgy [subcommand]";
    }

    @Override
    public List<String> getAliases() {
        return aliases;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    public static String[] shiftArgs(@Nullable String[] s, int shift)
    {
        if(s == null || s.length - shift <= 0)
        {
            return new String[0];
        }

        String[] s1 = new String[s.length - shift];
        System.arraycopy(s, shift, s1, 0, s1.length);
        return s1;
    }
}
