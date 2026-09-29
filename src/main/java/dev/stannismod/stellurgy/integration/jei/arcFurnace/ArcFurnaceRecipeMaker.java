package dev.stannismod.stellurgy.integration.jei.arcFurnace;

import mezz.jei.api.IJeiHelpers;
import dev.stannismod.stellurgy.libvulpes.interfaces.IRecipe;
import dev.stannismod.stellurgy.libvulpes.recipe.RecipesMachine;

import java.util.LinkedList;
import java.util.List;

public class ArcFurnaceRecipeMaker {

    public static List<ArcFurnaceWrapper> getMachineRecipes(IJeiHelpers helpers, Class clazz) {

        List<ArcFurnaceWrapper> list = new LinkedList<>();
        for (IRecipe rec : RecipesMachine.getInstance().getRecipes(clazz)) {
            list.add(new ArcFurnaceWrapper(rec));
        }
        return list;
    }

}
