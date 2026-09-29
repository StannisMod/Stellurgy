package dev.stannismod.stellurgy.integration.jei.satelliteBuilder;

import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeWrapper;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;

public class SatelliteBuilderRecipeHandler implements IRecipeHandler<SatelliteBuilderWrapper> {

    @Override
    public Class<SatelliteBuilderWrapper> getRecipeClass() {
        return SatelliteBuilderWrapper.class;
    }

    @Override
    public String getRecipeCategoryUid(SatelliteBuilderWrapper recipe) {
        return StellurgyJeiPlugin.satelliteBuilderUUID;
    }

    @Override
    public IRecipeWrapper getRecipeWrapper(SatelliteBuilderWrapper recipe) {
        return recipe;
    }

    @Override
    public boolean isRecipeValid(SatelliteBuilderWrapper recipe) {
        return recipe != null;
    }
}
