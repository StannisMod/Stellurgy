package dev.stannismod.stellurgy.integration.jei.precisionAssembler;

import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeWrapper;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;

public class PrecisionAssemblerRecipeHandler implements IRecipeHandler<PrecisionAssemblerWrapper> {

    @Override
    public Class<PrecisionAssemblerWrapper> getRecipeClass() {
        return PrecisionAssemblerWrapper.class;
    }

    @Override
    public String getRecipeCategoryUid(PrecisionAssemblerWrapper recipe) {
        return StellurgyJeiPlugin.precisionAssemblerUUID;
    }

    @Override
    public IRecipeWrapper getRecipeWrapper(PrecisionAssemblerWrapper recipe) {
        return recipe;
    }

    @Override
    public boolean isRecipeValid(PrecisionAssemblerWrapper recipe) {
        return true;
    }

}
