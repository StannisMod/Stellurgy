package dev.stannismod.stellurgy.integration.dataloaders;

import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.stations.ISpaceObject;
import dev.stannismod.stellurgy.entity.EntityRocket;
import dev.stannismod.stellurgy.stations.SpaceObjectManager;
import dev.stannismod.stellurgy.tile.TileGuidanceComputer;
import dev.stannismod.stellurgy.util.StationLandingLocation;
import zmaster587.libVulpes.util.Vector3F;

public class RocketDataLoaderServer extends RocketDataLoader {
    EntityRocket rocket;

    public RocketDataLoaderServer(EntityRocket rocket) {
        this.rocket = rocket;
    }

    @Override
    protected EntityRocket getRocket() {
        return rocket;
    }

    @Override
    public ItemStack getGuidanceComputer() {
        TileGuidanceComputer gc = rocket.storage.getGuidanceComputer();
        return gc == null ? null : gc.getStackInSlot(0);
    }

    @Override
    public StationLandingLocation getLandingLocation() {
        TileGuidanceComputer gc = rocket.storage.getGuidanceComputer();
        if (gc != null) {
            int currentDim = rocket.world.provider.getDimension();
            int destDim = rocket.storage.getDestinationDimId(currentDim, (int) rocket.posX, (int) rocket.posZ);

            Vector3F<Float> loc = rocket.storage.getDestinationCoordinates(destDim, false);

            if (destDim == StellurgyConfiguration.getCurrentConfig().spaceDimId) {
                if (loc != null) {
                    ISpaceObject station = SpaceObjectManager.getSpaceManager()
                            .getSpaceStationFromBlockCoords(new BlockPos(loc.x, loc.y, loc.z));

                    if (station != null) {
                        return gc.getLandingLocation(station.getId());
                    }
                }
            }
        }

        return null;
    }

    @Override
    public String getDestinationName() {
        TileGuidanceComputer gc = rocket.storage.getGuidanceComputer();
        if (gc == null) {
            return null;
        }
        int currentDim = rocket.world.provider.getDimension();
        int destDim = rocket.storage.getDestinationDimId(currentDim, (int) rocket.posX, (int) rocket.posZ);
        return gc.getDestinationName(destDim);
    }
}
