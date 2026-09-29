package dev.stannismod.stellurgy.integration.jei.co2scrubber;

import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeWrapper;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;

public class Co2ScrubberRecipeHandler implements IRecipeHandler<Co2ScrubberWrapper> {

    @Override
    public Class<Co2ScrubberWrapper> getRecipeClass() {
        return Co2ScrubberWrapper.class;
    }

    @Override
    public String getRecipeCategoryUid(Co2ScrubberWrapper recipe) {
        return StellurgyJeiPlugin.co2ScrubberUUID;
    }

    @Override
    public IRecipeWrapper getRecipeWrapper(Co2ScrubberWrapper recipe) {
        return recipe;
    }

    @Override
    public boolean isRecipeValid(Co2ScrubberWrapper recipe) {
        return recipe != null && !recipe.getCartridgeStack().isEmpty();
    }
}
