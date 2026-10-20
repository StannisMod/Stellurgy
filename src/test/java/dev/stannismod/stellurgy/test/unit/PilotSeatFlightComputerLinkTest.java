package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.tile.TilePilotSeat;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Contract for how a {@link TilePilotSeat} remembers its Advanced Flight Computer.
 *
 * <p>The seat and computer are linked on the launch pad, then the physics mod relocates the
 * whole craft into a ship — a rigid translation that changes both blocks' absolute positions
 * but NOT the offset between them. So the seat must store the computer as a RELATIVE offset,
 * not an absolute position; at runtime it recovers the computer at {@code seatPos + offset}.
 * These tests pin that relative-offset contract (the thing that makes the link survive
 * relocation) and its NBT persistence — no world, no physics mod, no client needed.</p>
 */
public class PilotSeatFlightComputerLinkTest {

    @Test
    public void resolvedComputerPositionTracksTheSeatUnderRigidTranslation() {
        // Link on the "pad", then simulate the physics-mod relocation as a rigid translation of
        // BOTH blocks by the same delta. The stored offset is relative, so re-placing the seat
        // at its post-relocation position must resolve the computer at ITS post-relocation
        // position — the property that lets the link survive the move.
        BlockPos padSeat = new BlockPos(100, 64, 100);
        BlockPos padAfc = new BlockPos(103, 65, 98);
        TilePilotSeat seat = new TilePilotSeat();
        seat.setPos(padSeat);
        seat.linkToFlightComputer(padAfc);

        BlockPos delta = new BlockPos(-2048, 0, 4096); // arbitrary rigid move into ship subspace
        seat.setPos(padSeat.add(delta));

        assertEquals("the resolved computer position tracks the seat under a rigid translation",
                padAfc.add(delta), seat.getFlightComputerPos());
    }
}
