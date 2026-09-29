package dev.stannismod.stellurgy.integration.jei.crystallizer;

import mezz.jei.api.IJeiHelpers;
import dev.stannismod.stellurgy.libvulpes.interfaces.IRecipe;
import dev.stannismod.stellurgy.libvulpes.recipe.RecipesMachine;

import java.util.LinkedList;
import java.util.List;

public class CrystallizerRecipeMaker {

    public static List<CrystallizerWrapper> getMachineRecipes(IJeiHelpers helpers, Class clazz) {

        List<CrystallizerWrapper> list = new LinkedList<>();
        for (IRecipe rec : RecipesMachine.getInstance().getRecipes(clazz)) {
            list.add(new CrystallizerWrapper(rec));
        }
        return list;
    }

}
