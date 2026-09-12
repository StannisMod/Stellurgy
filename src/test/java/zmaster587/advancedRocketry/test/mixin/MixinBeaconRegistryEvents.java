package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.block.BlockBeacon;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The break that is supposed to unregister a beacon, as an event — with both of the conditions the
 * production path consults evaluated right there.
 *
 * <h2>What this replaces, and why the thing it replaces could not work</h2>
 *
 * <p>The question is why a beacon is still registered after its controller block was broken. It was
 * first instrumented as a production LOG LINE naming which of the two guards had refused. That
 * instrument reported nothing on a run where the red DID occur — and its silence said nothing
 * either, because the mod logger writes into the dedicated-server CHILD's log and no current harness
 * path captures it: the only such files on disk are three weeks stale. A log is the right home for a
 * degradation a PLAYER should be able to read about afterwards. It is the wrong home for a fact a
 * TEST has to see, and reaching for it twice is what this class exists to stop.</p>
 *
 * <p>Recorded against the WORLD rather than an entity: this is a block event with no player anywhere
 * near it — a beacon in a headless test world is broken by a probe.</p>
 *
 * <p>The two conditions are read HERE rather than inferred from the outcome, because a break that
 * ran and removed nothing and a break that never happened produce the same registry contents, and no
 * assertion on those contents can separate them. The registry's own two writers are recorded by
 * {@code MixinBeaconRegistryWrites}.</p>
 */
@Mixin(BlockBeacon.class)
public abstract class MixinBeaconRegistryEvents {

    @Inject(method = "breakBlock", at = @At("HEAD"))
    private void arTest$beaconBreak(World world, BlockPos pos, IBlockState state, CallbackInfo ci) {
        if (world == null || pos == null) {
            return;
        }
        int dim = world.provider.getDimension();
        TileEntity tile = world.getTileEntity(pos);
        TestTrace.instrument(world, "beacon_registry_events");
        TestTrace.record(world, "beacon_break",
                "\"dim\":" + dim
                        + ",\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ()
                        + ",\"tile\":\"" + (tile == null ? "none" : tile.getClass().getSimpleName())
                        + "\""
                        + ",\"dimensionCreated\":"
                        + DimensionManager.getInstance().isDimensionCreated(dim)
                        + ",\"remote\":" + world.isRemote);
    }
}
