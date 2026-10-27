package dev.stannismod.stellurgy.probe;

import dev.stannismod.stellurgy.api.StellurgyBlocks;
import net.minecraft.block.BlockDirt;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * What the probe leaves where it lands: a bowl dug into the ground, its floor and rim scorched, and the
 * wreck at the bottom holding the record. Laid in one go, once the ground under it is loaded.
 *
 * <p>The size is staged, not derived: a probe this light reaches the ground of an airy world at a few tens
 * of metres a second and would barely dent it, and the one scripted find has to be found.</p>
 */
public final class ProbeImpact {

    /** The bowl's radius, blocks. Staged. */
    static final int RADIUS = 3;
    /** How deep the bowl is at its centre, blocks. Staged. */
    static final int DEPTH = 2;
    /** How far above the ground the bowl clears grass and the like, blocks. */
    private static final int CLEARANCE = 2;

    private ProbeImpact() {
    }

    /** Where the wreck lies for a landing on top of {@code landing}: the bowl's bottom. */
    public static BlockPos wreckAt(BlockPos landing) {
        return landing.down(DEPTH - 1);
    }

    /**
     * Dig the bowl around {@code landing} — the top block of the ground it landed on — scorch it and lay the
     * wreck holding {@code record}. A block with a tile entity, and an unbreakable one, is left as it is.
     */
    public static void lay(World world, BlockPos landing, NBTTagCompound record) {
        IBlockState scorched = Blocks.DIRT.getDefaultState().withProperty(BlockDirt.VARIANT, BlockDirt.DirtType.COARSE_DIRT);
        double r2 = (double) RADIUS * RADIUS;
        for (int dx = -RADIUS - 1; dx <= RADIUS + 1; dx++) {
            for (int dz = -RADIUS - 1; dz <= RADIUS + 1; dz++) {
                double rho2 = dx * dx + dz * dz;
                if (rho2 > (RADIUS + 1d) * (RADIUS + 1d)) {
                    continue;
                }
                BlockPos column = landing.add(dx, 0, dz);
                if (rho2 > r2) {
                    // The rim: the ground's top, scorched where it is ground.
                    replaceGround(world, column, scorched);
                    continue;
                }
                int depth = (int) Math.round(DEPTH * (1d - rho2 / r2));
                for (int y = CLEARANCE; y > -depth; y--) {
                    clear(world, column.up(y));
                }
                replaceGround(world, column.down(depth), scorched);
            }
        }
        BlockPos wreck = wreckAt(landing);
        world.setBlockState(wreck, StellurgyBlocks.blockProbeWreck.getDefaultState());
        TileEntity tile = world.getTileEntity(wreck);
        if (tile instanceof TileProbeWreck) {
            ((TileProbeWreck) tile).hold(record);
        }
    }

    private static boolean movable(World world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        return world.getTileEntity(pos) == null && state.getBlockHardness(world, pos) >= 0f;
    }

    private static void clear(World world, BlockPos pos) {
        if (!world.isAirBlock(pos) && movable(world, pos)) {
            world.setBlockToAir(pos);
        }
    }

    private static void replaceGround(World world, BlockPos pos, IBlockState scorched) {
        IBlockState state = world.getBlockState(pos);
        if (!world.isAirBlock(pos) && state.getMaterial().isSolid() && movable(world, pos)) {
            world.setBlockState(pos, scorched);
        }
    }
}
