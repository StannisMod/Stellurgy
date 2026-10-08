package dev.stannismod.stellurgy.hyperdrive;

import java.util.OptionalLong;
import java.util.UUID;

import net.minecraft.world.World;

import dev.stannismod.stellurgy.integration.vs.FlightComputerMassSource;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrame;

/**
 * How heavy a ship is, for the drive — the one number the speed formula needs from the hull.
 *
 * <p>Read through the same mass port every other consumer of ship mass reads, so the drive weighs the
 * hull the flight model weighs: its blocks, what they hold and who is aboard, in kilograms.</p>
 */
public final class ShipMassProvider {

    private ShipMassProvider() {
    }

    /**
     * The mass of the ship {@code shipId} names, in kilograms, or ABSENT when this world cannot weigh
     * it — no such ship here, no flight computer aboard, or a hull that weighs nothing. Absent is not
     * zero and is not a stand-in: a caller that gets it has nothing to compute a speed from, and says
     * so.
     */
    public static OptionalLong massOf(World world, UUID shipId) {
        ShipMassFrame frame = new FlightComputerMassSource(world).massFrame(shipId);
        if (frame == null || !(frame.getTotalMass() > 0.0D)) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Math.max(1L, Math.round(frame.getTotalMass())));
    }
}
