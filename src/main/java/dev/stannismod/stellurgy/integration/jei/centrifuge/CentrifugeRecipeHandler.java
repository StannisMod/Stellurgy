package dev.stannismod.stellurgy.integration.jei.centrifuge;

import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeWrapper;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;

public class CentrifugeRecipeHandler implements IRecipeHandler<CentrifugeWrapper> {

    @Override
    public Class<CentrifugeWrapper> getRecipeClass() {
        return CentrifugeWrapper.class;
    }

    @Override
    public String getRecipeCategoryUid(CentrifugeWrapper recipe) {
        return StellurgyJeiPlugin.centrifugeUUID;
    }

    @Override
    public IRecipeWrapper getRecipeWrapper(CentrifugeWrapper recipe) {
        return recipe;
    }

    @Override
    public boolean isRecipeValid(CentrifugeWrapper recipe) {
        return true;
    }

}
