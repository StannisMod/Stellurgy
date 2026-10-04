package dev.stannismod.stellurgy.command.sub.station;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.WorldServer;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.StellurgyItems;
import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.command.sub.StellurgyCommand;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.item.ItemStationChip;
import dev.stannismod.stellurgy.stations.SpaceObjectManager;
import dev.stannismod.stellurgy.stations.SpaceStationObject;
import dev.stannismod.stellurgy.world.util.BasicTeleporter;
import dev.stannismod.stellurgy.libvulpes.util.HashedBlockPosition;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

public class CreateStationCommand extends StellurgyCommand {
    @Override
    public String getName() {
        return "create";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.stellurgy.station.create.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1 || args.length > 3) {
            throw wrongUsage(sender);
        }
        int orbitDimId = parseInt(args[0]);
        DimensionProperties props = DimensionManager.getInstance().getDimensionProperties(orbitDimId);
        if (orbitDimId != Constants.INVALID_PLANET &&
                props == DimensionManager.getInstance().getOverworldProperties() && orbitDimId != props.getId()) {
            sender.sendMessage(new TextComponentTranslation("commands.stellurgy.station.create.tip"));
            throw new CommandException("commands.stellurgy.station.create.invalid", orbitDimId);
        }
        // Optional player + tp flag parsing
        EntityPlayerMP player = null;
        int idx = 1;

        if (args.length > idx && !args[idx].equalsIgnoreCase("tp")) {
            player = getPlayer(server, sender, args[idx]);
            idx++;
        }
        if (player == null) {
            player = getCommandSenderAsPlayer(sender);
        }

        boolean teleport = (args.length > idx && args[idx].equalsIgnoreCase("tp"));
        // Create + register station
        SpaceStationObject station = new SpaceStationObject();

        // MUST be true BEFORE registerSpaceObject sends PacketSpaceStationInfo
        station.beginTransition(0); // created=true

        SpaceObjectManager.getSpaceManager().registerSpaceObject(station, orbitDimId); // now the packet is correct

        int stationId = station.getId();
        HashedBlockPosition spawn = station.getSpawnLocation();

        // Ensure space world exists
        int spaceDim = StellurgyConfiguration.getCurrentConfig().spaceDimId;
        if (net.minecraftforge.common.DimensionManager.getWorld(spaceDim) == null) {
            net.minecraftforge.common.DimensionManager.initDimension(spaceDim);
        }
        WorldServer spaceWorld = server.getWorld(spaceDim);

        // Load chunk and build a 3x3 cobble platform under spawn
        BlockPos spawnPos = new BlockPos(spawn.x, spawn.y, spawn.z);
        spaceWorld.getChunkFromBlockCoords(spawnPos);

        BlockPos base = spawnPos.down();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                spaceWorld.setBlockState(base.add(dx, 0, dz), Blocks.COBBLESTONE.getDefaultState(), 2);
            }
        }
        // Ensure the spawn block is clear
        spaceWorld.setBlockState(spawnPos, Blocks.AIR.getDefaultState(), net.minecraftforge.common.util.Constants.BlockFlags.DEFAULT);

        // Give a station chip
        ItemStack chip = new ItemStack(StellurgyItems.itemSpaceStationChip);
        ItemStationChip.setUUID(chip, stationId);
        player.inventory.addItemStackToInventory(chip);

        sender.sendMessage(new TextComponentTranslation("commands.stellurgy.station.create.success",
                stationId, orbitDimId, spawn.x, spawn.y, spawn.z));

        // Optional teleport
        if (teleport) {
            if (player.world.provider.getDimension() != spaceDim) {
                player.changeDimension(spaceDim, new BasicTeleporter(spawnPos));
            } else {
                player.setPositionAndUpdate(spawn.x + 0.5, spawn.y + 2, spawn.z + 0.5);
            }
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 2) {
            if ("tp".startsWith(args[1].toLowerCase())) {
                return Collections.singletonList("tp");
            }
            return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
        }
        if (args.length == 3) {
            return Collections.singletonList("tp");
        }
        return Collections.emptyList();
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return index == 1;
    }
}
