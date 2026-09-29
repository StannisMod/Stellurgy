package dev.stannismod.stellurgy.api;

import net.minecraft.item.ItemStack;
import dev.stannismod.stellurgy.api.satellite.SatelliteProperties;

import javax.annotation.Nonnull;

public interface ISatelliteIdItem {
    void setSatellite(@Nonnull ItemStack stack, SatelliteProperties properties);
}
