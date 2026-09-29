package dev.stannismod.stellurgy.integration.jei.precisionLaserEtcher;

import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeWrapper;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;

public class PrecisionLaserEtcherRecipeHandler implements IRecipeHandler<PrecisionLaserEtcherWrapper> {

    @Override
    public Class<PrecisionLaserEtcherWrapper> getRecipeClass() {
        return PrecisionLaserEtcherWrapper.class;
    }

    @Override
    public String getRecipeCategoryUid(PrecisionLaserEtcherWrapper recipe) {
        return StellurgyJeiPlugin.precisionLaserEngraverUUID;
    }

    @Override
    public IRecipeWrapper getRecipeWrapper(PrecisionLaserEtcherWrapper recipe) {
        return recipe;
    }

    @Override
    public boolean isRecipeValid(PrecisionLaserEtcherWrapper recipe) {
        return true;
    }

}
