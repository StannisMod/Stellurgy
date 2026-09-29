package dev.stannismod.stellurgy.integration.jei.crystallizer;

import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeWrapper;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;

public class CrystallizerRecipeHandler implements IRecipeHandler<CrystallizerWrapper> {

    @Override
    public Class<CrystallizerWrapper> getRecipeClass() {
        return CrystallizerWrapper.class;
    }

    @Override
    public String getRecipeCategoryUid(CrystallizerWrapper recipe) {
        return StellurgyJeiPlugin.crystallizerUUID;
    }

    @Override
    public IRecipeWrapper getRecipeWrapper(CrystallizerWrapper recipe) {
        return recipe;
    }

    @Override
    public boolean isRecipeValid(CrystallizerWrapper recipe) {
        return true;
    }

}
