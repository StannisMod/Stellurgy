package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.test.trace.TestTrace;
import zmaster587.libVulpes.util.HashedBlockPosition;

/**
 * Both WRITERS of a dimension's beacon registry, as events.
 *
 * <p>A red that says "still registered" has three shapes behind it and they want different fixes:
 * the unregister never ran; it ran and something registered the position again afterwards; or the
 * break that should have called it never happened. The first two are separated HERE — an
 * {@code beacon_unregistered} followed by a later {@code beacon_registered} for the same position is
 * a different defect from no {@code beacon_unregistered} at all — and the third by the break event
 * in {@code MixinBeaconRegistryEvents}.</p>
 *
 * <p>Each record carries {@code by}, the caller trail, because the registry has more than one
 * legitimate writer: a machine being enabled or disabled goes through the tile
 * ({@code TileBeacon.setMachineEnabled}) while a broken block goes through the block. Which of them
 * moved the registry is the whole question when a position is registered twice.</p>
 */
@Mixin(DimensionProperties.class)
public abstract class MixinBeaconRegistryWrites {

    @Inject(method = "addBeaconLocation", at = @At("HEAD"))
    private void arTest$beaconAdded(World world, HashedBlockPosition pos, CallbackInfo ci) {
        arTest$mutation(world, pos, "beacon_registered");
    }

    @Inject(method = "removeBeaconLocation", at = @At("HEAD"))
    private void arTest$beaconRemoved(World world, HashedBlockPosition pos, CallbackInfo ci) {
        arTest$mutation(world, pos, "beacon_unregistered");
    }

    private static void arTest$mutation(World world, HashedBlockPosition pos, String type) {
        if (world == null || pos == null) {
            return;
        }
        TestTrace.instrument(world, "beacon_registry_events");
        TestTrace.record(world, type,
                "\"dim\":" + world.provider.getDimension()
                        + ",\"x\":" + pos.x + ",\"y\":" + pos.y + ",\"z\":" + pos.z
                        + ",\"remote\":" + world.isRemote
                        + ",\"by\":\"" + TestTrace.callerTrail() + "\"");
    }
}
