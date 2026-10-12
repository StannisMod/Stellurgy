package dev.stannismod.stellurgy.integration.jei.asteroids;

import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;
import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeWrapper;

public class AsteroidRecipeHandler implements IRecipeHandler<AsteroidWrapper> {
    @Override public Class<AsteroidWrapper> getRecipeClass() { return AsteroidWrapper.class; }
    @Override public String getRecipeCategoryUid(AsteroidWrapper r) { return StellurgyJeiPlugin.asteroidsUUID; }
    @Override public IRecipeWrapper getRecipeWrapper(AsteroidWrapper r) { return r; }
    @Override public boolean isRecipeValid(AsteroidWrapper r) { return r != null && r.isValid(); }
}
