package dev.stannismod.stellurgy.affs.world;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;

/**
 * Resolves the {@link FieldFrame} for a shield block from its position (§4.3): the frame is a
 * property of the network, but because a network is entirely on one ship or entirely standalone, any
 * member block resolves the same frame. A block a ship's registry record claims gets that ship's
 * {@link ShipFieldFrame}; everything else is the identity {@link WorldFieldFrame}. No mixed case.
 *
 * <p>Which ship a block belongs to is asked of the record, not of the ship's physics object. A hull
 * nobody is near has no physics object while its chunks stay loaded and its tiles tick; resolving
 * through the physics object handed such a block the WORLD frame, so its shell stood at the block's
 * shipyard address as though that were a place in the world. Through the record it gets its ship's
 * frame, which reports not-ready until the ship is simulated, and a not-ready emitter projects
 * nothing.</p>
 */
public final class FieldFrames {

    private FieldFrames() {
    }

    public static FieldFrame forBlock(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return WorldFieldFrame.INSTANCE;
        }
        String shipId = VSIntegration.registeredShipIdManagingBlock(world, pos);
        return shipId == null ? WorldFieldFrame.INSTANCE : new ShipFieldFrame(world, shipId);
    }
}
