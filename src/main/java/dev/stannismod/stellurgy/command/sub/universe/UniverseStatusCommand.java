package dev.stannismod.stellurgy.command.sub.universe;

import java.util.List;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;
import dev.stannismod.stellurgy.command.sub.StellurgyCommand;
import dev.stannismod.stellurgy.universe.GalaxyGenConfig;
import dev.stannismod.stellurgy.universe.UniverseRegistry;
import dev.stannismod.stellurgy.universe.UniverseSchemas;

/**
 * What world model this save runs on, what the pack currently states, and how much of the universe has
 * already been frozen by being seen.
 *
 * <p>Read-only, and the first thing to run when a load has been refused: it names both sides of the
 * comparison that refused it.</p>
 */
public class UniverseStatusCommand extends StellurgyCommand {

    @Override
    public String getName() {
        return "status";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.stellurgy.universe.status.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {
        if (args.length > 0) {
            throw wrongUsage(sender);
        }
        UniverseRegistry registry = UniverseRegistry.get(server);
        if (registry == null) {
            throw new CommandException("commands.stellurgy.universe.unavailable");
        }
        GalaxyGenConfig pack = dev.stannismod.stellurgy.Stellurgy.serverDimensions().getPackGalaxyConfig();
        String packFingerprint = UniverseRegistry.fingerprintOf(pack);

        sender.sendMessage(new TextComponentTranslation(
                "commands.stellurgy.universe.status.schema",
                registry.schemaVersion(), UniverseSchemas.CURRENT));
        registry.activeSchema().ifPresent(schema -> sender.sendMessage(
                new TextComponentTranslation(schema.isStable()
                        ? "commands.stellurgy.universe.status.stable"
                        : "commands.stellurgy.universe.status.alpha", schema.label())));
        sender.sendMessage(new TextComponentTranslation(
                "commands.stellurgy.universe.status.config",
                registry.configFingerprint(), packFingerprint));
        sender.sendMessage(new TextComponentTranslation(
                registry.configFingerprint().equals(packFingerprint)
                        ? "commands.stellurgy.universe.status.agrees"
                        : "commands.stellurgy.universe.status.differs"));
        sender.sendMessage(new TextComponentTranslation(
                "commands.stellurgy.universe.status.frozen", registry.pinnedSystemCount()));
        sender.sendMessage(new TextComponentTranslation(
                "commands.stellurgy.universe.status.released",
                UniverseSchemas.released().toString()));
        if (registry.isUpgradeArmed()) {
            sender.sendMessage(new TextComponentTranslation(
                    "commands.stellurgy.universe.status.armed"));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                                          net.minecraft.util.math.BlockPos targetPos) {
        return java.util.Collections.emptyList();
    }
}
