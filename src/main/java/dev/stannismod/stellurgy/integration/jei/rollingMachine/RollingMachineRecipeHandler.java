package dev.stannismod.stellurgy.integration.jei.rollingMachine;

import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeWrapper;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;

public class RollingMachineRecipeHandler implements IRecipeHandler<RollingMachineWrapper> {

    @Override
    public Class<RollingMachineWrapper> getRecipeClass() {
        return RollingMachineWrapper.class;
    }


    @Override
    public String getRecipeCategoryUid(RollingMachineWrapper recipe) {
        return StellurgyJeiPlugin.rollingMachineUUID;
    }

    @Override
    public IRecipeWrapper getRecipeWrapper(RollingMachineWrapper recipe) {
        return recipe;
    }

    @Override
    public boolean isRecipeValid(RollingMachineWrapper recipe) {
        return true;
    }

}
