package org.valkyrienskies.mod.common.ships.block_relocation;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import org.valkyrienskies.mod.common.physics.BlockPhysicsDetails;

public class ShipBlockPosFinder extends SpatialDetector {

    private final MutableBlockPos mutablePos = new MutableBlockPos();

    public ShipBlockPosFinder(BlockPos start, World worldIn, int maximum, boolean checkCorners,
                              StructureBoundingBox footprint) {
        super(start, worldIn, maximum, checkCorners, footprint);
        startDetection();
    }

    @Override
    public boolean isValidExpansion(int x, int y, int z) {
        mutablePos.setPos(x, y, z);
        return !BlockPhysicsDetails.isNotPhysicsInfused(cache.getBlockState(mutablePos).getBlock());
    }

}
