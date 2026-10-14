package dev.stannismod.stellurgy.affs.world;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;

/**
 * Resolves the {@link FieldFrame} for a shield block from its position (§4.3): the frame is a
 * property of the network, but because a network is entirely on one ship or entirely standalone, any
 * member block resolves the same frame. A block managed by a VS ship's subspace claim gets that ship's
 * {@link ShipFieldFrame}; a block in the shipyard that no ship claims yet gets the never-ready
 * {@link UnnamedShipFieldFrame}; everything else is the identity {@link WorldFieldFrame}. No mixed case.
 */
public final class FieldFrames {

    private FieldFrames() {
    }

    public static FieldFrame forBlock(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return WorldFieldFrame.INSTANCE;
        }
        String shipId = VSIntegration.shipIdManagingBlock(world, pos);
        if (shipId != null) {
            return new ShipFieldFrame(world, shipId);
        }
        return VSIntegration.isOnUnnamedShip(world, pos) ? new UnnamedShipFieldFrame() : WorldFieldFrame.INSTANCE;
    }
}
