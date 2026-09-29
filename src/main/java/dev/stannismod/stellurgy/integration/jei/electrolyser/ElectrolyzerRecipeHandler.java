package dev.stannismod.stellurgy.integration.jei.electrolyser;

import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeWrapper;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;

public class ElectrolyzerRecipeHandler implements IRecipeHandler<ElectrolyzerWrapper> {

    @Override
    public Class<ElectrolyzerWrapper> getRecipeClass() {
        return ElectrolyzerWrapper.class;
    }

    @Override
    public String getRecipeCategoryUid(ElectrolyzerWrapper recipe) {
        return StellurgyJeiPlugin.electrolyzerUUID;
    }

    @Override
    public IRecipeWrapper getRecipeWrapper(ElectrolyzerWrapper recipe) {
        return recipe;
    }

    @Override
    public boolean isRecipeValid(ElectrolyzerWrapper recipe) {
        return true;
    }

}
