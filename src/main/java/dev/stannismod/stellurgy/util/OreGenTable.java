package dev.stannismod.stellurgy.util;

import dev.stannismod.stellurgy.dimension.DimensionProperties.AtmosphereTypes;
import dev.stannismod.stellurgy.dimension.DimensionProperties.Temps;

/**
 * The ore palette of each climate cell, [pressure][temperature]. One per server
 * ({@code ServerState}), filled from {@code oreConfig.xml} when the server starts, so an entry removed
 * from that file between two worlds is gone in the second.
 */
public final class OreGenTable {

    private final OreGenProperties[][] cells =
            new OreGenProperties[AtmosphereTypes.values().length][Temps.values().length];

    /** Sets any planet with temperature {@code temp} to use these properties regardless of pressure. */
    public void setOresForTemperature(Temps temp, OreGenProperties properties) {
        for (int i = 0; i < AtmosphereTypes.values().length; i++)
            cells[i][temp.ordinal()] = properties;
    }

    public void setOresForPressure(AtmosphereTypes atmType, OreGenProperties properties) {
        for (int i = 0; i < Temps.values().length; i++)
            cells[atmType.ordinal()][i] = properties;
    }

    public void setOresForPressureAndTemp(AtmosphereTypes atmType, Temps temp, OreGenProperties properties) {
        cells[atmType.ordinal()][temp.ordinal()] = properties;
    }

    /** @return the cell's palette, or {@code null} when the cell uses the default world gen */
    public OreGenProperties getOresForPressure(AtmosphereTypes atmType, Temps temp) {
        return cells[atmType.ordinal()][temp.ordinal()];
    }
}
