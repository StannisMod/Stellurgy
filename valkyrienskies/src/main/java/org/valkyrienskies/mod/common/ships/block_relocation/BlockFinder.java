package org.valkyrienskies.mod.common.ships.block_relocation;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.StructureBoundingBox;

public class BlockFinder {

    public static SpatialDetector getBlockFinderFor(BlockFinderType id, BlockPos start, World worldIn, int maximum,
                                                    boolean checkCorners, StructureBoundingBox footprint) {
        switch (id) {
            case FIND_ALLOWED_BLOCKS:
                return new ShipSpawnDetector(start, worldIn, maximum, checkCorners, footprint);
            case FIND_ALL_BLOCKS:
                return new ShipBlockPosFinder(start, worldIn, maximum, checkCorners, footprint);
            case FIND_SINGLE_BLOCK:
                return new SingleBlockPosDetector(start, worldIn, maximum, checkCorners, footprint);
            default:
                throw new IllegalArgumentException("Unrecognized detector");
        }
    }

    public enum BlockFinderType {
        FIND_ALLOWED_BLOCKS, FIND_ALL_BLOCKS, FIND_SINGLE_BLOCK
    }

}
