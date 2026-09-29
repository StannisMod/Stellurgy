package dev.stannismod.stellurgy.inventory;

import dev.stannismod.stellurgy.api.dimension.IDimensionProperties;
import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;

public interface IPlanetDefiner {

    boolean isPlanetKnown(IDimensionProperties properties);

    boolean isStarKnown(StellarBody body);
}
