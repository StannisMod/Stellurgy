package dev.stannismod.stellurgy.integration.jei.precisionAssembler;

import dev.stannismod.stellurgy.libvulpes.interfaces.IRecipe;
import dev.stannismod.stellurgy.libvulpes.recipe.RecipesMachine;
import mezz.jei.api.IJeiHelpers;

import java.util.LinkedList;
import java.util.List;

public class PrecisionAssemblerRecipeMaker {

    public static List<PrecisionAssemblerWrapper> getMachineRecipes(IJeiHelpers helpers, Class clazz) {

        List<PrecisionAssemblerWrapper> list = new LinkedList<>();
        for (IRecipe rec : RecipesMachine.getInstance().getRecipes(clazz)) {
            list.add(new PrecisionAssemblerWrapper(rec));
        }
        return list;
    }

}
