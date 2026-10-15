package dev.stannismod.stellurgy.api;

import dev.stannismod.stellurgy.api.satellite.SatelliteProperties;
import net.minecraft.item.ItemStack;

import javax.annotation.Nonnull;

public interface ISatelliteIdItem {
    void setSatellite(@Nonnull ItemStack stack, SatelliteProperties properties);
}
