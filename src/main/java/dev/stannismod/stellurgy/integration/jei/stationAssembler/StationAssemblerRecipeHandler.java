package dev.stannismod.stellurgy.integration.jei.stationAssembler;

import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeWrapper;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;

public class StationAssemblerRecipeHandler implements IRecipeHandler<StationAssemblerWrapper> {
    @Override public Class<StationAssemblerWrapper> getRecipeClass() { return StationAssemblerWrapper.class; }
    @Override public String getRecipeCategoryUid(StationAssemblerWrapper r) { return StellurgyJeiPlugin.stationAssemblerUUID; }
    @Override public IRecipeWrapper getRecipeWrapper(StationAssemblerWrapper r) { return r; }
    @Override public boolean isRecipeValid(StationAssemblerWrapper r) { return r != null; }
}
