package dev.stannismod.stellurgy.integration.jei.electrolyser;

import mezz.jei.api.IJeiHelpers;
import dev.stannismod.stellurgy.libvulpes.interfaces.IRecipe;
import dev.stannismod.stellurgy.libvulpes.recipe.RecipesMachine;

import java.util.LinkedList;
import java.util.List;

public class ElectrolyzerRecipeMaker {

    public static List<ElectrolyzerWrapper> getMachineRecipes(IJeiHelpers helpers, Class clazz) {

        List<ElectrolyzerWrapper> list = new LinkedList<>();
        for (IRecipe rec : RecipesMachine.getInstance().getRecipes(clazz)) {
            list.add(new ElectrolyzerWrapper(rec));
        }

        return list;
    }

}
