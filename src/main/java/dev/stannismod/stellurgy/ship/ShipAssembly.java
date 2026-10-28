package dev.stannismod.stellurgy.ship;

import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.entity.EntityDummy;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import dev.stannismod.stellurgy.tile.TileAdvancedFlightComputer;
import dev.stannismod.stellurgy.tile.TileNavigationComputer;
import dev.stannismod.stellurgy.tile.TilePilotSeat;
import dev.stannismod.stellurgy.tile.TileShipComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns the blocks standing in a region into a tier-2 ship, in place: the one way a build becomes a craft,
 * whether a player's assembler asks for it or code builds a craft itself (a fixture, a ship met in space).
 *
 * <p>The region's machines are bound to its flight computer before the substrate takes the blocks, since
 * the bindings are offsets from the computer and only offsets survive the relocation; the craft's durable
 * name is minted on that computer, and its hull extent recorded there.</p>
 */
public final class ShipAssembly {

    /** Why a region was not made into a craft. Nothing in the world has been changed when one is answered. */
    public enum Refusal {
        /** No flight computer stands in the region, so the craft would have nothing to be named by. */
        NO_FLIGHT_COMPUTER,
        /** Two flight computers would both steer the one body. */
        MULTIPLE_FLIGHT_COMPUTERS,
        /** Only one pilot seat is bound to the computer; a pilot in another would have dead controls. */
        MULTIPLE_PILOT_SEATS,
        /** More blocks than the physics substrate takes in one ship. */
        TOO_LARGE,
        /** The hull reaches past the substrate's search from its flight computer, so it would be cut short. */
        REACH_EXCEEDED
    }

    /** What {@link #assemble} did: the durable name of the craft it handed over, or why it handed over none. */
    public static final class Result {

        private final UUID ship;
        private final Refusal refusal;

        private Result(UUID ship, Refusal refusal) {
            this.ship = ship;
            this.refusal = refusal;
        }

        /** The durable name of the assembled craft — its flight computer's — or {@code null} when refused. */
        public UUID ship() {
            return ship;
        }

        /** Why no craft was made, or {@code null} when one was. */
        public Refusal refusal() {
            return refusal;
        }
    }

    private ShipAssembly() {
    }

    /**
     * Whether the region's machines make one craft: one flight computer, at most one pilot seat. A reading
     * of the blocks alone, safe on either side; the substrate's own limits are asked only by
     * {@link #assemble}. Both coordinates of the region are inclusive.
     */
    public static Refusal componentRefusal(World world, AxisAlignedBB region) {
        return Components.of(world, region).refusal();
    }

    /**
     * Hand the blocks standing in {@code region} (both coordinates inclusive) to the physics substrate as one
     * craft, where they stand. The substrate takes them on a later tick of its own; the answer is the name
     * the craft will carry. Server side only.
     */
    public static Result assemble(World world, AxisAlignedBB region) {
        Components parts = Components.of(world, region);
        Refusal refusal = parts.refusal();
        if (refusal == null) {
            VSIntegration.AssemblyRefusal substrate = VSIntegration.builtTier2ShipRefusal(world, region);
            if (substrate != null) {
                refusal = substrate == VSIntegration.AssemblyRefusal.NO_FLIGHT_COMPUTER ? Refusal.NO_FLIGHT_COMPUTER
                        : substrate == VSIntegration.AssemblyRefusal.TOO_LARGE ? Refusal.TOO_LARGE
                        : Refusal.REACH_EXCEEDED;
            }
        }
        if (refusal != null) {
            return new Result(null, refusal);
        }
        BlockPos computer = parts.computers.get(0);
        for (BlockPos seat : parts.seats) {
            ((TilePilotSeat) world.getTileEntity(seat)).linkToFlightComputer(computer);
        }
        for (BlockPos nav : parts.navigationComputers) {
            ((TileNavigationComputer) world.getTileEntity(nav)).linkToFlightComputer(computer);
        }
        // A jump gate finds a ship's machines by asking each which computer it answers to, so an unbound
        // machine is invisible to its own ship.
        for (BlockPos machine : parts.machines) {
            ((TileShipComponent) world.getTileEntity(machine)).linkToFlightComputer(computer);
        }
        TileAdvancedFlightComputer afc = (TileAdvancedFlightComputer) world.getTileEntity(computer);
        UUID name = afc.getOrCreateShipId();
        // The extent of the blocks, not of the region a caller drew round them: after the hand-over the
        // blocks are a body whose extent is only recoverable by walking it, and the jump window is checked
        // against it.
        afc.setHullExtent(parts.min.getX() - computer.getX(), parts.min.getY() - computer.getY(),
                parts.min.getZ() - computer.getZ(), parts.max.getX() - computer.getX(),
                parts.max.getY() - computer.getY(), parts.max.getZ() - computer.getZ());
        VSIntegration.assembleBuiltTier2Ship(world, region);
        rebindSeatedPilots(world, region, parts, computer, name);
        return new Result(name, null);
    }

    /**
     * A pilot who took the seat before assembly rides a mount bound to the seat's build-time position, which
     * the relocation is about to vacate. His boarding is re-expressed on the relocated seat once the
     * substrate has moved it — queued, because the relocation happens on a later tick.
     */
    private static void rebindSeatedPilots(World world, AxisAlignedBB region, Components parts, BlockPos computer,
                                           UUID name) {
        if (parts.seats.isEmpty()) {
            return;
        }
        BlockPos seat = parts.seats.get(0);
        for (EntityDummy mount : world.getEntitiesWithinAABB(EntityDummy.class, region.grow(2.0))) {
            if (!seat.equals(mount.getSeatPos())) {
                continue;
            }
            for (Entity passenger : mount.getPassengers()) {
                if (passenger instanceof EntityPlayerMP) {
                    Stellurgy.spaceSubsystem().crewRebind.enqueue((WorldServer) world, (EntityPlayerMP) passenger,
                            mount.getEntityId(), computer, computer.getX() - seat.getX(),
                            computer.getY() - seat.getY(), computer.getZ() - seat.getZ(), name);
                }
            }
        }
    }

    /** The machines of a region that a craft binds to its flight computer, and the box its blocks fill. */
    private static final class Components {

        final List<BlockPos> computers = new ArrayList<>();
        final List<BlockPos> seats = new ArrayList<>();
        final List<BlockPos> navigationComputers = new ArrayList<>();
        final List<BlockPos> machines = new ArrayList<>();
        /** The corners of the tightest box round the region's non-air blocks; {@code null} when it has none. */
        BlockPos min;
        BlockPos max;

        static Components of(World world, AxisAlignedBB region) {
            Components c = new Components();
            for (BlockPos at : BlockPos.getAllInBox(new BlockPos(region.minX, region.minY, region.minZ),
                    new BlockPos(region.maxX, region.maxY, region.maxZ))) {
                if (!world.isAirBlock(at)) {
                    c.min = c.min == null ? at : new BlockPos(Math.min(c.min.getX(), at.getX()),
                            Math.min(c.min.getY(), at.getY()), Math.min(c.min.getZ(), at.getZ()));
                    c.max = c.max == null ? at : new BlockPos(Math.max(c.max.getX(), at.getX()),
                            Math.max(c.max.getY(), at.getY()), Math.max(c.max.getZ(), at.getZ()));
                }
                TileEntity tile = world.getTileEntity(at);
                if (tile instanceof TileAdvancedFlightComputer) {
                    c.computers.add(at);
                } else if (tile instanceof TilePilotSeat) {
                    c.seats.add(at);
                } else if (tile instanceof TileNavigationComputer) {
                    c.navigationComputers.add(at);
                } else if (tile instanceof TileShipComponent) {
                    c.machines.add(at);
                }
            }
            return c;
        }

        Refusal refusal() {
            if (computers.isEmpty()) {
                return Refusal.NO_FLIGHT_COMPUTER;
            }
            if (computers.size() > 1) {
                return Refusal.MULTIPLE_FLIGHT_COMPUTERS;
            }
            return seats.size() > 1 ? Refusal.MULTIPLE_PILOT_SEATS : null;
        }
    }
}
