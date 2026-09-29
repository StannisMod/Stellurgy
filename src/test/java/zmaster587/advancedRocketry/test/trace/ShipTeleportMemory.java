package zmaster587.advancedRocketry.test.trace;

import net.minecraft.world.World;

/**
 * The world-frame Y a ship stood at when the current {@code VSBridge.teleportShip} call entered —
 * written at HEAD, consumed at RETURN, {@code NaN} when there was no ship or no pose to read. One per
 * side, in that side's {@link SideTrace}: the call runs on the side that owns the world.
 *
 * <p>Test source set: absent from a released jar.</p>
 */
public final class ShipTeleportMemory {

    public double fromY = Double.NaN;

    /** The memory of the side {@code world} belongs to, or of the calling thread's without one. */
    public static ShipTeleportMemory of(World world) {
        SideTrace side = world == null ? SideTrace.here() : SideTrace.of(world);
        return side.memory(ShipTeleportMemory.class, ShipTeleportMemory::new);
    }
}
