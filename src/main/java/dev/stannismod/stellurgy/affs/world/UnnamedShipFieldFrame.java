package dev.stannismod.stellurgy.affs.world;

import net.minecraft.util.math.Vec3d;

/**
 * The frame of a block that stands in the shipyard while no ship claims it: the window in which a
 * ship's chunks are loaded and its ship object does not exist yet. Its coordinates are a shipyard
 * address, not a place in the world, so nothing maps out of it and it is never ready — an emitter here
 * projects no shell, and a block here is covered by none.
 *
 * <p>Distinct from {@link WorldFieldFrame} on purpose: answering "standalone" for such a block was a
 * well-formed stand-in that put a live shell millions of blocks from the ship.</p>
 */
public final class UnnamedShipFieldFrame implements FieldFrame {

    @Override
    public Vec3d fieldToWorld(double x, double y, double z) {
        return null;
    }

    @Override
    public Vec3d surfaceVelocityAt(double worldX, double worldY, double worldZ) {
        return new Vec3d(0.0D, 0.0D, 0.0D);
    }

    @Override
    public boolean isReady() {
        return false;
    }
}
