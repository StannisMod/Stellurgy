package zmaster587.advancedRocketry.block;

import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.tile.multiblock.TileBeacon;
import zmaster587.libVulpes.block.multiblock.BlockMultiblockMachine;
import zmaster587.libVulpes.tile.multiblock.TileMultiBlock;
import zmaster587.libVulpes.util.HashedBlockPosition;

import java.util.Random;

public class BlockBeacon extends BlockMultiblockMachine {

    public BlockBeacon(Class<? extends TileMultiBlock> tileClass, int guiId) {
        super(tileClass, guiId);
    }

    /**
     * Unregisters this beacon's POSITION, which is the whole of what the registry holds about it.
     *
     * <p>The removal used to be gated on {@code world.getTileEntity(pos) instanceof TileBeacon} — a
     * value the removal itself never reads. When that gate was false the block went away and its
     * registry entry stayed, and it stayed FOREVER: the only other unregister is
     * {@code TileBeacon.setMachineEnabled(false)}, which needs the tile that no longer exists.
     * Nothing said so. Removing a position the registry does not hold is a no-op, so asking about
     * the tile could only ever cost cleanups, never save one.</p>
     *
     * <p><b>BOTH old conditions are now observable, and that is the point of the logging.</b> The
     * tile one is removed outright; the dimension one has to stay, because without a created
     * dimension there is no registry to remove from — but it is the other candidate for the silent
     * skip, and a fix that quietened one while leaving the other unwatched would be indistinguishable
     * from a fix that worked. Nobody has ever seen either condition, so each says so once when it
     * happens rather than being inferred later from a stale entry.</p>
     */
    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        int dim = world.provider.getDimension();
        if (DimensionManager.getInstance().isDimensionCreated(dim)) {
            TileEntity tile = world.getTileEntity(pos);
            if (!(tile instanceof TileBeacon)) {
                AdvancedRocketry.logger.warn("[BEACON] a beacon block at {} in dim {} was broken with "
                        + "no beacon tile present (found {}); unregistering it anyway", pos, dim,
                        tile == null ? "nothing" : tile.getClass().getName());
            }
            DimensionManager.getInstance().getDimensionProperties(dim)
                    .removeBeaconLocation(world, new HashedBlockPosition(pos));
        } else {
            // Not a degradation that can be repaired here — there is no registry for an uncreated
            // dimension — but it IS the other way this break can leave a beacon registered, so it is
            // never silent.
            AdvancedRocketry.logger.warn("[BEACON] a beacon block at {} was broken in dim {}, which "
                    + "reports itself as not created, so nothing was unregistered", pos, dim);
        }
        super.breakBlock(world, pos, state);
    }

    @SideOnly(Side.CLIENT)
    public void randomDisplayTick(IBlockState stateIn, World worldIn, BlockPos pos, Random rand) {
        if (worldIn.getTileEntity(pos) instanceof TileBeacon && ((TileBeacon) worldIn.getTileEntity(pos)).getMachineEnabled()) {
            EnumFacing enumfacing = stateIn.getValue(FACING);
            for (int i = 0; i < 10; i++)
                AdvancedRocketry.proxy.spawnParticle("reddust", worldIn, pos.getX() - enumfacing.getFrontOffsetX() + worldIn.rand.nextDouble(), pos.getY() + 5 - worldIn.rand.nextDouble(), pos.getZ() - enumfacing.getFrontOffsetZ() + worldIn.rand.nextDouble(), 0, 0, 0);
        }
    }
}
