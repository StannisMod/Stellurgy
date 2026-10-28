package dev.stannismod.stellurgy.command.sub;

import dev.stannismod.stellurgy.affs.AdvancedForceFieldSystem;
import dev.stannismod.stellurgy.affs.te.TileEntityFieldGenerator;
import dev.stannismod.stellurgy.affs.util.CodeUtils;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.block.BlockOmniThruster;
import dev.stannismod.stellurgy.libvulpes.api.LibVulpesBlocks;
import dev.stannismod.stellurgy.libvulpes.block.BlockFullyRotatable;
import dev.stannismod.stellurgy.ship.ShipAssembly;
import dev.stannismod.stellurgy.tile.TileAdvancedFlightComputer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /stellurgy fixture ship-1}: builds a finished tier-2 ship in the air in front of the operator and
 * makes it a craft where it stands ({@link ShipAssembly}) — no pad and no assembler, as code builds a craft.
 * An operator's shortcut for trying the ship systems, not a gameplay path — it clears the box it builds in.
 *
 * <p>Its shield carries access code {@code 1}, and the operator is handed a code device holding it: a
 * shield with no code admits nobody, and its shells cut through the cabin.</p>
 *
 * <p>Ship 1 is fed by creative sources and carries every system a ship can have: lift and aft motors,
 * omnidirectional thrusters on every corner, a reaction wheel, a jump drive, a shield and two beam guns,
 * flown from a glazed cockpit whose seat has the flight computer, navigation computer and both consoles
 * within reach. Forward is north, the side the cockpit faces.</p>
 */
public class FixtureCommand extends StellurgyCommand {

    private static final String SHIP_1 = "ship-1";

    /** The access code ship 1's shield is laid with, and the code on the device the operator is handed. */
    private static final String SHIELD_CODE = "1";

    /** How far ahead of the operator the pad's near edge is laid, blocks. */
    private static final int STANDOFF = 3;

    @Override
    public String getName() {
        return "fixture";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.stellurgy.fixture.usage";
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                                          @Nullable BlockPos targetPos) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, SHIP_1) : Collections.<String>emptyList();
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 1) {
            throw wrongUsage(sender);
        }
        if (!SHIP_1.equals(args[0])) {
            throw new CommandException("commands.stellurgy.fixture.unknown", args[0]);
        }
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        World world = player.world;
        EnumFacing ahead = player.getHorizontalFacing();
        BlockPos feet = new BlockPos(player);
        // The site's centre, far enough ahead that the operator stands outside its cleared box.
        BlockPos centre = feet.offset(ahead, STANDOFF + Ship1.SITE_DEPTH / 2 + 2);
        BlockPos origin = centre.add(-Ship1.SITE_WIDTH / 2, 0, -Ship1.SITE_DEPTH / 2);

        Ship1.lay(world, origin);
        // Laid in the air, it is handed over holding where it stands — Flight Assist on, a zero cruise —
        // as a ship its pilot stood up from would: a craft nobody has flown yet holds nothing and falls.
        TileEntity computer = world.getTileEntity(origin.add(Ship1.parts().get("computer")));
        if (computer instanceof TileAdvancedFlightComputer) {
            ((TileAdvancedFlightComputer) computer).commandCruise(0, 0, 0);
        }
        if (ShipAssembly.assemble(world, Ship1.region(origin)).refusal() != null) {
            throw new CommandException("commands.stellurgy.fixture.failed", SHIP_1);
        }
        ItemStack codeDevice = new ItemStack(AdvancedForceFieldSystem.ITEM_CODE_DEVICE);
        CodeUtils.setCode(codeDevice, SHIELD_CODE);
        if (!player.inventory.addItemStackToInventory(codeDevice)) {
            player.dropItem(codeDevice, false);
        }
        BlockPos cabin = origin.add(Ship1.CABIN_X, Ship1.CABIN_Y, Ship1.CABIN_Z);
        player.connection.setPlayerLocation(cabin.getX() + 0.5D, cabin.getY(), cabin.getZ() + 0.5D, 180F, 0F);
        sender.sendMessage(new TextComponentTranslation("commands.stellurgy.fixture.built", SHIP_1));
    }

    /**
     * Ship 1's layout, in site coordinates: x across the site, y up from its floor, z from the bow (north,
     * z = 0) to the stern. The site spans x 0..8 and z 0..10.
     */
    public static final class Ship1 {

        static final int SITE_WIDTH = 9;
        static final int SITE_DEPTH = 11;
        /** How high the site is cleared and the craft's region reaches: a course past the highest block. */
        static final int SITE_HEIGHT = 10;
        /** Where the operator is put: on the cockpit floor, behind the pilot seat. */
        static final int CABIN_X = 4;
        static final int CABIN_Y = 4;
        static final int CABIN_Z = 4;

        private Ship1() {
        }

        /**
         * Where each named part stands, in blocks from the flight computer. Assembly moves the ship as one
         * rigid body, so these name the same parts aboard; a reader of the assembled ship asks here rather
         * than re-deriving the layout.
         */
        public static Map<String, Vec3i> partsFromComputer() {
            Map<String, BlockPos> parts = parts();
            BlockPos computer = parts.get("computer");
            Map<String, Vec3i> out = new LinkedHashMap<>();
            for (Map.Entry<String, BlockPos> part : parts.entrySet()) {
                out.put(part.getKey(), part.getValue().subtract(computer));
            }
            return out;
        }

        /** The named parts, in pad coordinates: the one place their positions are written. */
        private static Map<String, BlockPos> parts() {
            Map<String, BlockPos> parts = new LinkedHashMap<>();
            parts.put("computer", new BlockPos(3, 4, 3));
            parts.put("pilotSeat", new BlockPos(4, 4, 3));
            parts.put("navigationComputer", new BlockPos(5, 4, 3));
            parts.put("weaponConsole", new BlockPos(4, 6, 3));
            parts.put("turretPort", new BlockPos(3, 6, 3));
            parts.put("turretStarboard", new BlockPos(5, 6, 3));
            parts.put("shieldGenerator", new BlockPos(5, 6, 5));
            parts.put("shieldConsole", new BlockPos(5, 5, 5));
            parts.put("shieldEmitterFore", new BlockPos(5, 6, 4));
            parts.put("shieldEmitterStarboard", new BlockPos(6, 6, 5));
            parts.put("driveGenerator", new BlockPos(5, 4, 8));
            parts.put("capacitor", new BlockPos(5, 5, 8));
            parts.put("jumpEmitter", new BlockPos(3, 4, 8));
            return parts;
        }

        /** Clear the site and lay the ship in it, its lowest blocks a block above the site's floor. */
        static void lay(World world, BlockPos o) {
            for (int x = -2; x <= SITE_WIDTH + 1; x++) {
                for (int z = -2; z <= SITE_DEPTH + 1; z++) {
                    for (int y = 0; y <= SITE_HEIGHT + 1; y++) {
                        world.setBlockToAir(o.add(x, y, z));
                    }
                }
            }
            hull(world, o);
            propulsion(world, o);
            cockpit(world, o);
            roof(world, o);
            jumpBay(world, o);
        }

        /** The box the ship is laid in, both corners inclusive: above the site's floor, so no ground joins it. */
        static AxisAlignedBB region(BlockPos o) {
            return new AxisAlignedBB(o.add(0, 1, 0), o.add(SITE_WIDTH - 1, SITE_HEIGHT, SITE_DEPTH - 1));
        }

        /** The deck every other part hangs from, so the assembly's flood fill reaches all of it. */
        private static void hull(World world, BlockPos o) {
            for (int x = 2; x <= 6; x++) {
                for (int z = 2; z <= 8; z++) {
                    set(world, o, x, 3, z, Blocks.IRON_BLOCK.getDefaultState());
                }
            }
        }

        private static void propulsion(World world, BlockPos o) {
            // Lift: four motors under fuel tanks hung from the deck; a tank above turns a motor's nozzle down.
            for (int x = 3; x <= 5; x += 2) {
                for (int z = 4; z <= 6; z += 2) {
                    set(world, o, x, 2, z, StellurgyBlocks.blockFuelTank.getDefaultState());
                    set(world, o, x, 1, z, StellurgyBlocks.blockAdvEngine.getDefaultState());
                }
            }
            // Aft: two motors on the stern edge, nozzles south, pushing the ship forward.
            for (int x = 3; x <= 5; x += 2) {
                set(world, o, x, 3, 9, StellurgyBlocks.blockAdvEngine.getDefaultState()
                        .withProperty(BlockFullyRotatable.FACING, EnumFacing.SOUTH));
            }
            // Manoeuvring: under each deck corner, and on its outer side face, every nozzle in open air.
            for (int x = 2; x <= 6; x += 4) {
                for (int z = 2; z <= 8; z += 6) {
                    set(world, o, x, 2, z, thruster(EnumFacing.UP));
                    set(world, o, x == 2 ? 1 : 7, 3, z, thruster(x == 2 ? EnumFacing.EAST : EnumFacing.WEST));
                }
            }
            set(world, o, 6, 4, 7, StellurgyBlocks.blockReactionWheel.getDefaultState());
        }

        /** Glazed walls round the bow half of the deck, a doorway aft; seat, flight and navigation computers. */
        private static void cockpit(World world, BlockPos o) {
            for (int y = 4; y <= 5; y++) {
                for (int x = 2; x <= 6; x++) {
                    for (int z = 2; z <= 6; z++) {
                        boolean wall = x == 2 || x == 6 || z == 2 || z == 6;
                        boolean doorway = x == 4 && z == 6;
                        if (wall && !doorway) {
                            set(world, o, x, y, z, Blocks.GLASS.getDefaultState());
                        }
                    }
                }
            }
            Map<String, BlockPos> parts = parts();
            world.setBlockState(o.add(parts.get("pilotSeat")), StellurgyBlocks.blockPilotSeat.getDefaultState());
            world.setBlockState(o.add(parts.get("computer")), StellurgyBlocks.blockAdvancedFlightComputer.getDefaultState());
            world.setBlockState(o.add(parts.get("navigationComputer")),
                    StellurgyBlocks.blockNavigationComputer.getDefaultState());
        }

        /**
         * The cockpit's roof carries the weapons and the shield: two beam guns either side of the weapon
         * console, which touches both and so joins them to one network, each fed by a creative source; the
         * shield generator with an emitter on two sides, its console hanging into the cabin and a creative
         * source on top. Glass and light everywhere else.
         */
        private static void roof(World world, BlockPos o) {
            for (int x = 2; x <= 6; x++) {
                for (int z = 2; z <= 6; z++) {
                    set(world, o, x, 6, z, Blocks.GLASS.getDefaultState());
                }
            }
            set(world, o, 3, 6, 5, Blocks.GLOWSTONE.getDefaultState());
            Map<String, BlockPos> parts = parts();
            world.setBlockState(o.add(parts.get("weaponConsole")), StellurgyBlocks.blockWeaponConsole.getDefaultState());
            for (String turret : new String[]{"turretPort", "turretStarboard"}) {
                BlockPos at = o.add(parts.get(turret));
                world.setBlockState(at, StellurgyBlocks.blockTurret.getDefaultState());
                world.setBlockState(at.up(), StellurgyBlocks.blockGunBeamEmitter.getDefaultState());
                // Its source on its outboard side, away from the console between the two guns.
                world.setBlockState(at.offset("turretPort".equals(turret) ? EnumFacing.WEST : EnumFacing.EAST),
                        AdvancedForceFieldSystem.BLOCK_ADMIN_ENERGY_SOURCE.getDefaultState());
            }
            BlockPos generator = o.add(parts.get("shieldGenerator"));
            world.setBlockState(generator, AdvancedForceFieldSystem.BLOCK_SHIELD_GENERATOR.getDefaultState());
            world.setBlockState(generator.up(), AdvancedForceFieldSystem.BLOCK_ADMIN_ENERGY_SOURCE.getDefaultState());
            world.setBlockState(o.add(parts.get("shieldConsole")), AdvancedForceFieldSystem.BLOCK_SHIELD_CONSOLE.getDefaultState());
            for (String emitter : new String[]{"shieldEmitterFore", "shieldEmitterStarboard"}) {
                BlockPos at = o.add(parts.get(emitter));
                world.setBlockState(at, AdvancedForceFieldSystem.BLOCK_FIELD_GENERATOR.getDefaultState());
                TileEntity tile = world.getTileEntity(at);
                if (tile instanceof TileEntityFieldGenerator) {
                    ((TileEntityFieldGenerator) tile).applyAccessCode(SHIELD_CODE);
                }
            }
        }

        /**
         * The jump drive aft of the cockpit: the generator, its capacitor bank on top of it with a creative
         * plug, four heat sinks the bank's walk reaches, and a hull emitter whose envelope wraps the ship.
         */
        private static void jumpBay(World world, BlockPos o) {
            Map<String, BlockPos> parts = parts();
            world.setBlockState(o.add(parts.get("driveGenerator")), StellurgyBlocks.blockHyperdriveGenerator.getDefaultState());
            BlockPos capacitor = o.add(parts.get("capacitor"));
            world.setBlockState(capacitor, StellurgyBlocks.blockJumpCapacitor.getDefaultState());
            world.setBlockState(capacitor.up(), LibVulpesBlocks.blockCreativeInputPlug.getDefaultState());
            set(world, o, 4, 5, 8, StellurgyBlocks.blockJumpHeatSink.getDefaultState());
            set(world, o, 6, 5, 8, StellurgyBlocks.blockJumpHeatSink.getDefaultState());
            set(world, o, 5, 5, 7, StellurgyBlocks.blockJumpHeatSink.getDefaultState());
            set(world, o, 5, 4, 7, StellurgyBlocks.blockJumpHeatSink.getDefaultState());
            world.setBlockState(o.add(parts.get("jumpEmitter")), StellurgyBlocks.blockJumpFieldEmitter.getDefaultState());
        }

        private static IBlockState thruster(EnumFacing mount) {
            return StellurgyBlocks.blockOmniThruster.getDefaultState().withProperty(BlockOmniThruster.MOUNT, mount);
        }

        private static void set(World world, BlockPos o, int x, int y, int z, IBlockState state) {
            world.setBlockState(o.add(x, y, z), state);
        }
    }
}
