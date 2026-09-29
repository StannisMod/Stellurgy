package dev.stannismod.stellurgy.satellite;

import dev.stannismod.stellurgy.api.DataStorage;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;

public class SatelliteMassScanner extends SatelliteData {

    public SatelliteMassScanner() {
        super();
        data = new DataStorage(DataStorage.DataType.MASS);
        data.lockDataType(DataStorage.DataType.MASS);
    }

    @Override
    public String getName() {
        return LibVulpes.proxy.getLocalizedString("item.satellite.massscanner");
    }

    @Override
    public double failureChance() {
        return 0;
    }

}
