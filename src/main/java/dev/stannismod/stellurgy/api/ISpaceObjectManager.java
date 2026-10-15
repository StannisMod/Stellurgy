package dev.stannismod.stellurgy.api;

import dev.stannismod.stellurgy.api.stations.ISpaceObject;
import net.minecraft.util.math.BlockPos;

public interface ISpaceObjectManager {
    ISpaceObject getSpaceStationFromBlockCoords(BlockPos pos);
}
