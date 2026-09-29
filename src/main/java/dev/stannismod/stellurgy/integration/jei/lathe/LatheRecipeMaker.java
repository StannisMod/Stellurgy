package dev.stannismod.stellurgy.integration.jei.lathe;

import mezz.jei.api.IJeiHelpers;
import dev.stannismod.stellurgy.libvulpes.interfaces.IRecipe;
import dev.stannismod.stellurgy.libvulpes.recipe.RecipesMachine;

import java.util.LinkedList;
import java.util.List;

public class LatheRecipeMaker {

    public static List<LatheWrapper> getMachineRecipes(IJeiHelpers helpers, Class clazz) {

        List<LatheWrapper> list = new LinkedList<>();
        for (IRecipe rec : RecipesMachine.getInstance().getRecipes(clazz)) {
            list.add(new LatheWrapper(rec));
        }
        return list;
    }

}
