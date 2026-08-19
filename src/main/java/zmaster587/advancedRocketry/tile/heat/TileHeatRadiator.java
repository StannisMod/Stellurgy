package zmaster587.advancedRocketry.tile.heat;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.block.BlockHeatRadiator;
import zmaster587.advancedRocketry.subsystem.heat.HeatNetwork;
import zmaster587.advancedRocketry.subsystem.heat.IHeatExchanger;
import zmaster587.advancedRocketry.subsystem.hull.HullClearance;

/**
 * One cell of radiating surface: the only way heat gets off a ship for good.
 * <p>
 * <b>A cell, not a plate.</b> A radiator array is many of these, and its area is simply how many the
 * player built — which is what makes rejection linear in area without anything having to scan,
 * validate or approve a shape. It also gives the behaviour the clearance rule asks for: an array
 * with one obstructed cell loses that cell and keeps the rest, where a plate validated as a whole
 * would fail entirely over a single block in the way.
 * <p>
 * It radiates ONE way, along its facing, and needs open space ahead of it. Anything in front means
 * the heat comes back to the ship, and that case is deliberately not modelled — the cell simply does
 * not work, and says how far away the obstruction is so a player can go and remove it.
 */
public class TileHeatRadiator extends TileHeatLoopBlock implements IHeatExchanger {

    private static final String NBT_CLOSED = "heatSinkClosed";

    /**
     * Heat this cell shed on the last tick, for a readout. Per-tick, so never persisted. Negative when
     * the cell was absorbing more than it radiated, which is what a radiator under a star does.
     */
    private long rejectedThisTick;

    /**
     * Whether this cell has been shut. Persisted, because a ship left dark is still dark when its
     * chunks come back - a state a player chose and paid for may not be quietly undone by a reload.
     */
    private boolean closed;

    @Override
    public int getHeatCapacity() {
        if (!HeatNetwork.enabled())
            return 0;
        // A radiator is a plate, not a tank: it is part of the loop's mass, but a small part.
        return Math.max(0, ARConfiguration.getCurrentConfig().shipHeatPipeCapacity);
    }

    /** Which way this cell radiates — the side it was placed against points away from the hull. */
    public EnumFacing getRadiatingFacing() {
        if (world == null) {
            return EnumFacing.UP;
        }
        return BlockHeatRadiator.facingOf(world.getBlockState(pos));
    }

    /**
     * How far ahead the first obstruction sits, or 0 when this cell can see open space. Reported
     * rather than reduced to a boolean so a blocked array sends a player to the right block.
     */
    public int getObstruction() {
        if (world == null)
            return 1;
        return HullClearance.obstructionDistance(world, pos, getRadiatingFacing(),
                Math.max(1, ARConfiguration.getCurrentConfig().shipHeatRadiatorClearance));
    }

    @Override
    public int getExchangeCells() {
        if (!HeatNetwork.enabled() || world == null || world.isRemote)
            return 0;
        // A shut cell is not working surface, which is the same statement an obstructed one makes.
        // Everything downstream - what the loop sheds, what the environment can put back through it,
        // and what a passive sensor sees of it - reads this one number, so a ship running silent
        // stops radiating and stops being seen by the radiators through the same door it closed.
        return !closed && getObstruction() == 0 ? 1 : 0;
    }

    /** Whether this cell has been shut - see {@code ThermalBody.setSinksClosed}. */
    public boolean isClosed() {
        return closed;
    }

    /** Shut this cell, or open it. Ship-wide silence is many of these, decided in one place. */
    public void setClosed(boolean closed) {
        if (this.closed == closed) {
            return;
        }
        this.closed = closed;
        markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        closed = nbt.getBoolean(NBT_CLOSED);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setBoolean(NBT_CLOSED, closed);
        return nbt;
    }

    @Override
    public long exchange(long amount) {
        rejectedThisTick = amount;
        // Positive: the energy is gone, radiated into space, which is one of the three ways heat is
        // allowed to leave a ship — there is nothing to hand it to. Negative: it came from the star or
        // the world this cell is pointed at, and it arrives in the loop. Neither direction is clamped,
        // because a surface that could only lose heat would make a ship in a star cooler than the star.
        return rejectedThisTick;
    }

    /** What this cell shed on the last solve — negative if it took heat IN instead. */
    public long getRejectedThisTick() {
        return rejectedThisTick;
    }
}
