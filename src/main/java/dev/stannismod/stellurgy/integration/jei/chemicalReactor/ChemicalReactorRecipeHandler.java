package dev.stannismod.stellurgy.integration.jei.chemicalReactor;

import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;
import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeWrapper;

public class ChemicalReactorRecipeHandler implements IRecipeHandler<ChemicalReactorlWrapper> {

    @Override
    public Class<ChemicalReactorlWrapper> getRecipeClass() {
        return ChemicalReactorlWrapper.class;
    }

    @Override
    public String getRecipeCategoryUid(ChemicalReactorlWrapper recipe) {
        return StellurgyJeiPlugin.chemicalReactorUUID;
    }

    @Override
    public IRecipeWrapper getRecipeWrapper(ChemicalReactorlWrapper recipe) {
        return recipe;
    }

    @Override
    public boolean isRecipeValid(ChemicalReactorlWrapper recipe) {
        return true;
    }

}
