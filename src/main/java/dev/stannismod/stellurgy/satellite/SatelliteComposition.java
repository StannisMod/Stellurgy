package dev.stannismod.stellurgy.satellite;

import dev.stannismod.stellurgy.api.DataStorage;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;

public class SatelliteComposition extends SatelliteData {

    public SatelliteComposition() {
        super();
        data = new DataStorage(DataStorage.DataType.COMPOSITION);
        data.lockDataType(DataStorage.DataType.COMPOSITION);
    }

    @Override
    public String getName() {
        return LibVulpes.proxy.getLocalizedString("item.satellite.composition");
    }

    @Override
    public double failureChance() {
        return 0;
    }
}
