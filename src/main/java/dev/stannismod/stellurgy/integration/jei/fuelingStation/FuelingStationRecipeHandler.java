package dev.stannismod.stellurgy.integration.jei.fuelingStation;

import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;
import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeWrapper;

public class FuelingStationRecipeHandler implements IRecipeHandler<FuelingStationWrapper> {
    @Override public Class<FuelingStationWrapper> getRecipeClass() { return FuelingStationWrapper.class; }
    @Override public String getRecipeCategoryUid(FuelingStationWrapper r) { return StellurgyJeiPlugin.fuelingStationUUID; }
    @Override public IRecipeWrapper getRecipeWrapper(FuelingStationWrapper r) { return r; }
    @Override public boolean isRecipeValid(FuelingStationWrapper r) { return r != null; }
}
