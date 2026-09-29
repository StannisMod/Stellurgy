package dev.stannismod.stellurgy.integration.jei.orbitalLaserDrill;

import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeWrapper;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;

public class OrbitalLaserDrillRecipeHandler implements IRecipeHandler<OrbitalLaserDrillWrapper> {
    @Override public Class<OrbitalLaserDrillWrapper> getRecipeClass() { return OrbitalLaserDrillWrapper.class; }
    @Override public String getRecipeCategoryUid(OrbitalLaserDrillWrapper r) { return StellurgyJeiPlugin.orbitalLaserDrillUUID; }
    @Override public IRecipeWrapper getRecipeWrapper(OrbitalLaserDrillWrapper r) { return r; }
    @Override public boolean isRecipeValid(OrbitalLaserDrillWrapper r) { return r != null; }
}
