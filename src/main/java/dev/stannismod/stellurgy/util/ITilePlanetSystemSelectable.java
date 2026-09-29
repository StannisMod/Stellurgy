package dev.stannismod.stellurgy.util;

import net.minecraft.item.ItemStack;

import javax.annotation.Nonnull;
import java.util.List;

public interface ITilePlanetSystemSelectable {
    @Nonnull
    ItemStack getChipWithId(int id);

    void setSelectedPlanetId(int id);

    List<Integer> getVisiblePlanets();
}
