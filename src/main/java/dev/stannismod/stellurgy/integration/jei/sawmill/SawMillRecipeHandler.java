package dev.stannismod.stellurgy.integration.jei.sawmill;

import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;
import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeWrapper;

public class SawMillRecipeHandler implements IRecipeHandler<SawMillWrapper> {

    @Override
    public Class<SawMillWrapper> getRecipeClass() {
        return SawMillWrapper.class;
    }

    @Override
    public String getRecipeCategoryUid(SawMillWrapper recipe) {
        return StellurgyJeiPlugin.sawMillUUID;
    }

    @Override
    public IRecipeWrapper getRecipeWrapper(SawMillWrapper recipe) {
        return recipe;
    }

    @Override
    public boolean isRecipeValid(SawMillWrapper recipe) {
        return true;
    }

}
