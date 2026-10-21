package dev.stannismod.stellurgy.satellite;

import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.SatelliteRegistry;
import dev.stannismod.stellurgy.api.satellite.SatelliteProperties;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

/**
 * What a satellite's parts add up to. The one sum, read by the builder when it assembles a satellite and
 * by a repair in orbit when a part is swapped, so the two can never disagree about what a part is worth.
 */
public final class SatelliteAssembly {

    /** The slots of a chassis' part inventory that hold the parts the sum is taken over: 1..6. Slot 0 is the primary function. */
    public static final int FIRST_PART_SLOT = 1;
    public static final int LAST_PART_SLOT = 6;
    /** Power buffer every chassis carries before any battery part, so a satellite has something to run on. */
    public static final int BASE_POWER_STORAGE = 720;

    private SatelliteAssembly() {
    }

    /**
     * The properties of a satellite of type {@code satType} built from the parts in {@code parts}, with no
     * id: the caller either mints one (assembly) or keeps the satellite's own (repair).
     */
    public static SatelliteProperties propertiesOf(IInventory parts, String satType) {
        int powerStorage = 0;
        int powerGeneration = 0;
        int maxData = 0;
        float weight = 0;
        for (int slot = FIRST_PART_SLOT; slot <= LAST_PART_SLOT; slot++) {
            ItemStack stack = parts.getStackInSlot(slot);
            SatelliteProperties part = SatelliteRegistry.getSatelliteProperty(stack);
            if (part != null) {
                int flag = part.getPropertyFlag();
                if (flag == SatelliteProperties.Property.BATTERY.getFlag())
                    powerStorage += part.getPowerStorage();
                if (flag == SatelliteProperties.Property.POWER_GEN.getFlag())
                    powerGeneration += part.getPowerGeneration();
                if (flag == SatelliteProperties.Property.DATA.getFlag())
                    maxData += part.getMaxDataStorage();
            }
            weight += Stellurgy.weights().getWeight(stack);
        }
        return new SatelliteProperties(powerGeneration, powerStorage + BASE_POWER_STORAGE, satType, maxData, weight);
    }
}
