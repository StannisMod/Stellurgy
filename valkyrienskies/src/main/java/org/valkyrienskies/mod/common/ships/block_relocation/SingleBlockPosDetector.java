package org.valkyrienskies.mod.common.ships.block_relocation;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.StructureBoundingBox;

/**
 * A detector that will only pick up one block; used for most orbitals
 *
 * @author thebest108
 */
public class SingleBlockPosDetector extends SpatialDetector {

    public SingleBlockPosDetector(BlockPos start, World worldIn, int maximum, boolean checkCorners,
                                  StructureBoundingBox footprint) {
        super(start, worldIn, maximum, false, footprint);
        startDetection();
    }

    @Override
    public boolean isValidExpansion(int x, int y, int z) {
        return x == firstBlock.getX() && y == firstBlock.getY() && z == firstBlock.getZ();
    }

}
