package dev.stannismod.stellurgy.integration.vs;

import java.util.UUID;

import javax.annotation.Nullable;

import dev.stannismod.stellurgy.ship.mass.ShipMassFrame;
import dev.stannismod.stellurgy.ship.mass.ShipMassSource;
import dev.stannismod.stellurgy.tile.TileAdvancedFlightComputer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The mass source for the crafts of one world, as it stands today: a craft is ours to weigh exactly
 * when it carries a flight computer.
 *
 * <p>A hull with no flight computer is not a craft anyone controls, and nothing that reads mass
 * needs one for it: ships do not collide with each other, so the physics engine's own mass is a
 * complete answer for such a hull. This source therefore answers {@code null} for it and leaves that
 * mass alone. The day something needs our mass for a computer-less hull, it is this class that
 * changes, and no caller does.</p>
 *
 * <p>The computer is read from the physics engine's record of the ship's force controllers
 * ({@link VSIntegration#flightComputerOf}), never found by walking blocks: the flight computer's
 * background round asks this every few seconds for every craft.</p>
 *
 * <p>One instance per world, made where it is needed; it holds the world and nothing else.</p>
 */
public final class FlightComputerMassSource implements ShipMassSource {

    private final World world;

    public FlightComputerMassSource(World world) {
        this.world = world;
    }

    /**
     * The mass frame of the craft whose durable id is {@code shipId}, measured from its hull, or
     * {@code null} when this world holds no such craft, the craft has no flight computer, or its hull
     * weighs nothing.
     */
    @Override
    @Nullable
    public ShipMassFrame massFrame(UUID shipId) {
        if (shipId == null || world == null || world.isRemote) {
            return null;
        }
        UUID physicsId = VSIntegration.shipUuidOfDurableId(world, shipId.toString());
        if (physicsId == null || flightComputerOf(physicsId) == null) {
            return null;
        }
        return ShipHullMass.frameOf(world, physicsId);
    }

    /**
     * {@link #massFrame} for a caller that holds the physics engine's id for the craft rather than its
     * durable one — the ship manager's events and the flight computer's own rounds.
     *
     * <p>The durable id is read from the physics record first, and from the flight computer where the
     * record carries none. The record is a reverse index; the computer's own NBT is the copy that rides
     * every relocation, so an unbound record is a missing index entry, not a craft without a name.</p>
     */
    @Nullable
    public ShipMassFrame massFrameOfPhysicsShip(UUID physicsId) {
        if (physicsId == null || world == null || world.isRemote) {
            return null;
        }
        UUID durable = VSIntegration.durableIdOfShip(world, physicsId);
        if (durable == null) {
            TileEntity te = tileAt(flightComputerOf(physicsId));
            durable = te instanceof TileAdvancedFlightComputer
                    ? ((TileAdvancedFlightComputer) te).shipIdOrNull()
                    : null;
        }
        return durable == null ? null : massFrame(durable);
    }

    /** The subspace position of the loaded ship's flight computer, or {@code null}. */
    @Nullable
    private BlockPos flightComputerOf(UUID physicsId) {
        return VSBridge.flightComputerOfLoadedShip(world, physicsId);
    }

    @Nullable
    private TileEntity tileAt(@Nullable BlockPos pos) {
        return pos == null ? null : world.getTileEntity(pos);
    }
}
