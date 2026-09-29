package dev.stannismod.stellurgy.api;

import net.minecraft.util.math.BlockPos;
import dev.stannismod.stellurgy.api.stations.ISpaceObject;

public interface ISpaceObjectManager {
    ISpaceObject getSpaceStationFromBlockCoords(BlockPos pos);
}
