package dev.stannismod.stellurgy.integration.jei.stationAssembler;

import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;
import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeWrapper;

public class StationAssemblerRecipeHandler implements IRecipeHandler<StationAssemblerWrapper> {
    @Override public Class<StationAssemblerWrapper> getRecipeClass() { return StationAssemblerWrapper.class; }
    @Override public String getRecipeCategoryUid(StationAssemblerWrapper r) { return StellurgyJeiPlugin.stationAssemblerUUID; }
    @Override public IRecipeWrapper getRecipeWrapper(StationAssemblerWrapper r) { return r; }
    @Override public boolean isRecipeValid(StationAssemblerWrapper r) { return r != null; }
}
