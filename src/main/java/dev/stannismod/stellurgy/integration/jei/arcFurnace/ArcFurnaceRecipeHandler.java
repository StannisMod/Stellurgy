package dev.stannismod.stellurgy.integration.jei.arcFurnace;

import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeWrapper;
import dev.stannismod.stellurgy.integration.jei.StellurgyJeiPlugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ArcFurnaceRecipeHandler implements IRecipeHandler<ArcFurnaceWrapper> {

    @Override
    @Nonnull
    public Class<ArcFurnaceWrapper> getRecipeClass() {
        return ArcFurnaceWrapper.class;
    }

    @Override
    @Nonnull
    public String getRecipeCategoryUid(@Nullable ArcFurnaceWrapper recipe) {
        return StellurgyJeiPlugin.arcFurnaceUUID;
    }

    @Override
    @Nonnull
    public IRecipeWrapper getRecipeWrapper(@Nonnull ArcFurnaceWrapper recipe) {
        return recipe;
    }

    @Override
    public boolean isRecipeValid(@Nullable ArcFurnaceWrapper recipe) {
        return true;
    }

}
