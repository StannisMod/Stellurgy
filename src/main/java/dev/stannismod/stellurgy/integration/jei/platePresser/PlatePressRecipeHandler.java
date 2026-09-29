package dev.stannismod.stellurgy.integration.jei.platePresser;

import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeWrapper;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;

public class PlatePressRecipeHandler implements IRecipeHandler<PlatePressWrapper> {

    @Override
    public Class<PlatePressWrapper> getRecipeClass() {
        return PlatePressWrapper.class;
    }

    @Override
    public String getRecipeCategoryUid(PlatePressWrapper recipe) {
        return StellurgyJeiPlugin.platePresser;
    }

    @Override
    public IRecipeWrapper getRecipeWrapper(PlatePressWrapper recipe) {
        return recipe;
    }

    @Override
    public boolean isRecipeValid(PlatePressWrapper recipe) {
        return true;
    }

}
