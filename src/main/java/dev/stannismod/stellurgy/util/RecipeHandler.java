package dev.stannismod.stellurgy.util;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;
import net.minecraftforge.registries.GameData;
import dev.stannismod.stellurgy.block.BlockSmallPlatePress;
import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.libvulpes.api.material.AllowedProducts;
import dev.stannismod.stellurgy.libvulpes.api.material.MaterialRegistry;
import dev.stannismod.stellurgy.libvulpes.recipe.RecipesMachine;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiblockMachine;

import java.util.*;
import java.util.Map.Entry;
import dev.stannismod.stellurgy.tile.multiblock.machine.*;

public class RecipeHandler {

    private List<Class<? extends TileMultiblockMachine>> machineList = new ArrayList<>();

    public void registerMachine(Class<? extends TileMultiblockMachine> clazz) {
        if (!machineList.contains(clazz)) {
            machineList.add(clazz);
            RecipesMachine.getInstance().recipeList.put(clazz, new LinkedList<>());
        }

    }

    public void clearAllMachineRecipes() {
        for (Class<? extends TileMultiblockMachine> clazz : machineList) {
            RecipesMachine.getInstance().getRecipes(clazz).clear();
        }
    }

    public void registerXMLRecipes() {
        //Load XML recipes
        Stellurgy.instance.libVulpes.loadXMLRecipe(TileCuttingMachine.class);
        Stellurgy.instance.libVulpes.loadXMLRecipe(TilePrecisionAssembler.class);
        Stellurgy.instance.libVulpes.loadXMLRecipe(TileChemicalReactor.class);
        Stellurgy.instance.libVulpes.loadXMLRecipe(TileCrystallizer.class);
        Stellurgy.instance.libVulpes.loadXMLRecipe(TileElectrolyser.class);
        Stellurgy.instance.libVulpes.loadXMLRecipe(TileElectricArcFurnace.class);
        Stellurgy.instance.libVulpes.loadXMLRecipe(TileLathe.class);
        Stellurgy.instance.libVulpes.loadXMLRecipe(TileRollingMachine.class);
        Stellurgy.instance.libVulpes.loadXMLRecipe(BlockSmallPlatePress.class);
        Stellurgy.instance.libVulpes.loadXMLRecipe(TileCentrifuge.class);
        Stellurgy.instance.libVulpes.loadXMLRecipe(TilePrecisionLaserEtcher.class);
    }

    public void registerAllMachineRecipes() {

        for (Class<? extends TileMultiblockMachine> clazz : machineList)
            try {
                clazz.newInstance().registerRecipes();
            } catch (InstantiationException | IllegalAccessException e) {
                e.printStackTrace();
            }
    }

    public void createAutoGennedRecipes(HashMap<AllowedProducts, HashSet<String>> modProducts) {

        for (dev.stannismod.stellurgy.libvulpes.api.material.Material ore : MaterialRegistry.getAllMaterials()) {
            if (AllowedProducts.getProductByName("ORE").isOfType(ore.getAllowedProducts()) && AllowedProducts.getProductByName("INGOT").isOfType(ore.getAllowedProducts()))
                GameRegistry.addSmelting(ore.getProduct(AllowedProducts.getProductByName("ORE")), ore.getProduct(AllowedProducts.getProductByName("INGOT")), 0);

            if (AllowedProducts.getProductByName("NUGGET").isOfType(ore.getAllowedProducts())) {
                ItemStack nugget = ore.getProduct(AllowedProducts.getProductByName("NUGGET"));
                nugget.setCount(9);
                for (String str : ore.getOreDictNames()) {
                    GameData.register_impl(new ShapelessOreRecipe(null, nugget, AllowedProducts.getProductByName("INGOT").name().toLowerCase(Locale.ENGLISH) + str)
                            .setRegistryName("stellurgy", "unpacknugget" + str));
                    GameData.register_impl(new ShapedOreRecipe(null, ore.getProduct(AllowedProducts.getProductByName("INGOT")), "ooo", "ooo", "ooo", 'o', AllowedProducts.getProductByName("NUGGET").name().toLowerCase(Locale.ENGLISH) + str)
                            .setRegistryName("stellurgy", "unpackingot" + str));
                }
            }

            if (AllowedProducts.getProductByName("GEM").isOfType(ore.getAllowedProducts())) {
                for (String str : ore.getOreDictNames())
                    RecipesMachine.getInstance().addRecipe(TileCrystallizer.class, ore.getProduct(AllowedProducts.getProductByName("GEM")), 300, 20, AllowedProducts.getProductByName("DUST").name().toLowerCase(Locale.ENGLISH) + str);
            }

            if (AllowedProducts.getProductByName("BOULE").isOfType(ore.getAllowedProducts())) {
                for (String str : ore.getOreDictNames())
                    RecipesMachine.getInstance().addRecipe(TileCrystallizer.class, ore.getProduct(AllowedProducts.getProductByName("BOULE")), 300, 20, AllowedProducts.getProductByName("INGOT").name().toLowerCase(Locale.ENGLISH) + str, AllowedProducts.getProductByName("NUGGET").name().toLowerCase(Locale.ENGLISH) + str);
            }

            if (AllowedProducts.getProductByName("STICK").isOfType(ore.getAllowedProducts()) && AllowedProducts.getProductByName("INGOT").isOfType(ore.getAllowedProducts())) {
                for (String name : ore.getOreDictNames())
                    if (OreDictionary.doesOreNameExist(AllowedProducts.getProductByName("INGOT").name().toLowerCase(Locale.ENGLISH) + name)) {

                        GameData.register_impl(new ShapedOreRecipe(null, ore.getProduct(AllowedProducts.getProductByName("STICK"), 4), "x  ", " x ", "  x", 'x', AllowedProducts.getProductByName("INGOT").name().toLowerCase(Locale.ENGLISH) + name)
                                .setRegistryName("stellurgy", "stick" + name));

                        RecipesMachine.getInstance().addRecipe(TileLathe.class, ore.getProduct(AllowedProducts.getProductByName("STICK"), 2), 300, 20, AllowedProducts.getProductByName("INGOT").name().toLowerCase(Locale.ENGLISH) + name); //ore.getProduct(AllowedProducts.getProductByName("INGOT")));
                    }
            }

            if (AllowedProducts.getProductByName("PLATE").isOfType(ore.getAllowedProducts())) {
                for (String oreDictNames : ore.getOreDictNames()) {
                    if (OreDictionary.doesOreNameExist(AllowedProducts.getProductByName("INGOT").name().toLowerCase(Locale.ENGLISH) + oreDictNames)) {
                        RecipesMachine.getInstance().addRecipe(TileRollingMachine.class, ore.getProduct(AllowedProducts.getProductByName("PLATE")), 300, 20, AllowedProducts.getProductByName("INGOT").name().toLowerCase(Locale.ENGLISH) + oreDictNames, new FluidStack(FluidRegistry.WATER, 100));
                        if (AllowedProducts.getProductByName("BLOCK").isOfType(ore.getAllowedProducts()) || ore.isVanilla())
                            RecipesMachine.getInstance().addRecipe(BlockSmallPlatePress.class, ore.getProduct(AllowedProducts.getProductByName("PLATE"), 4), 0, 0, AllowedProducts.getProductByName("BLOCK").name().toLowerCase(Locale.ENGLISH) + oreDictNames);
                    }
                }
            }

            if (AllowedProducts.getProductByName("SHEET").isOfType(ore.getAllowedProducts())) {
                for (String oreDictNames : ore.getOreDictNames()) {
                    RecipesMachine.getInstance().addRecipe(TileRollingMachine.class, ore.getProduct(AllowedProducts.getProductByName("SHEET")), 300, 200, AllowedProducts.getProductByName("PLATE").name().toLowerCase(Locale.ENGLISH) + oreDictNames, new FluidStack(FluidRegistry.WATER, 100));
                }
            }

            if (AllowedProducts.getProductByName("COIL").isOfType(ore.getAllowedProducts())) {
                for (String str : ore.getOreDictNames())
                    GameData.register_impl(new ShapedOreRecipe(null, ore.getProduct(AllowedProducts.getProductByName("COIL")), "ooo", "o o", "ooo", 'o',
                            AllowedProducts.getProductByName("INGOT").name().toLowerCase(Locale.ENGLISH) + str).setRegistryName("stellurgy", "coil" + str));
            }

            if (AllowedProducts.getProductByName("FAN").isOfType(ore.getAllowedProducts())) {
                for (String str : ore.getOreDictNames()) {
                    GameData.register_impl(new ShapedOreRecipe(null, ore.getProduct(AllowedProducts.getProductByName("FAN")), "p p", " r ", "p p", 'p',
                            AllowedProducts.getProductByName("PLATE").name().toLowerCase(Locale.ENGLISH) + str, 'r',
                            AllowedProducts.getProductByName("STICK").name().toLowerCase(Locale.ENGLISH) + str).setRegistryName("stellurgy", "fan" + str));
                }
            }
            if (AllowedProducts.getProductByName("GEAR").isOfType(ore.getAllowedProducts())) {
                for (String str : ore.getOreDictNames()) {
                    GameData.register_impl(new ShapedOreRecipe(null, ore.getProduct(AllowedProducts.getProductByName("GEAR")), "sps", " r ", "sps", 'p',
                            AllowedProducts.getProductByName("PLATE").name().toLowerCase(Locale.ENGLISH) + str, 's',
                            AllowedProducts.getProductByName("STICK").name().toLowerCase(Locale.ENGLISH) + str, 'r',
                            AllowedProducts.getProductByName("INGOT").name().toLowerCase(Locale.ENGLISH) + str).setRegistryName("stellurgy", "gear" + str));
                }
            }
            if (AllowedProducts.getProductByName("BLOCK").isOfType(ore.getAllowedProducts())) {
                ItemStack ingot = ore.getProduct(AllowedProducts.getProductByName("INGOT"));
                ingot.setCount(9);
                for (String str : ore.getOreDictNames()) {
                    GameData.register_impl(new ShapelessOreRecipe(null, ingot,
                            AllowedProducts.getProductByName("BLOCK").name().toLowerCase(Locale.ENGLISH) + str).setRegistryName("stellurgy", "unpackblock" + str));
                    GameData.register_impl(new ShapedOreRecipe(null,
                            ore.getProduct(AllowedProducts.getProductByName("BLOCK")), "ooo", "ooo", "ooo", 'o',
                            AllowedProducts.getProductByName("INGOT").name().toLowerCase(Locale.ENGLISH) + str).setRegistryName("stellurgy", "packblock" + str));
                }
            }

            if (AllowedProducts.getProductByName("DUST").isOfType(ore.getAllowedProducts())) {
                for (String str : ore.getOreDictNames()) {
                    if (AllowedProducts.getProductByName("ORE").isOfType(ore.getAllowedProducts()) || ore.isVanilla()) {
                        ItemStack stack = ore.getProduct(AllowedProducts.getProductByName("DUST"));
                        stack.setCount(2);
                        RecipesMachine.getInstance().addRecipe(BlockSmallPlatePress.class, stack, 0, 0,
                                AllowedProducts.getProductByName("ORE").name().toLowerCase(Locale.ENGLISH) + str);
                    }
                    if (AllowedProducts.getProductByName("INGOT").isOfType(ore.getAllowedProducts()) || ore.isVanilla())
                        GameRegistry.addSmelting(ore.getProduct(AllowedProducts.getProductByName("DUST")),
                                ore.getProduct(AllowedProducts.getProductByName("INGOT")), 0);
                }
            }
        }

        // Handle vanilla integration
        if (dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig().allowSawmillVanillaWood) {
            for (int i = 0; i < 4; i++) {
                RecipesMachine.getInstance().addRecipe(TileCuttingMachine.class, new ItemStack(Blocks.PLANKS, 6, i), 80,
                        10, new ItemStack(Blocks.LOG, 1, i));
            }
            RecipesMachine.getInstance().addRecipe(TileCuttingMachine.class, new ItemStack(Blocks.PLANKS, 6, 4), 80, 10,
                    new ItemStack(Blocks.LOG2, 1, 0));
            RecipesMachine.getInstance().addRecipe(TileCuttingMachine.class, new ItemStack(Blocks.PLANKS, 6, 5), 80, 10,
                    new ItemStack(Blocks.LOG2, 1, 1));
        }

        // Handle items from other mods
        if (dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig().allowMakingItemsForOtherMods) {
            for (Entry<AllowedProducts, HashSet<String>> entry : modProducts.entrySet()) {
                if (entry.getKey() == AllowedProducts.getProductByName("PLATE")) {
                    for (String str : entry.getValue()) {
                        dev.stannismod.stellurgy.libvulpes.api.material.Material material = dev.stannismod.stellurgy.libvulpes.api.material.Material
                                .valueOfSafe(str.toUpperCase());

                        if (OreDictionary.doesOreNameExist("ingot" + str)
                                && OreDictionary.getOres("ingot" + str).size() > 0 &&
                                (OreDictionary.doesOreNameExist("plate" + str)
                                        && OreDictionary.getOres("plate" + str).size() > 0)
                                && (material == null || !AllowedProducts.getProductByName("PLATE")
                                .isOfType(material.getAllowedProducts()))) {

                            RecipesMachine.getInstance().addRecipe(TileRollingMachine.class,
                                    OreDictionary.getOres("plate" + str).get(0), 300, 20, "ingot" + str, new FluidStack(FluidRegistry.WATER, 100));
                        }
                    }
                } else if (entry.getKey() == AllowedProducts.getProductByName("STICK")) {
                    for (String str : entry.getValue()) {
                        dev.stannismod.stellurgy.libvulpes.api.material.Material material = dev.stannismod.stellurgy.libvulpes.api.material.Material
                                .valueOfSafe(str.toUpperCase());

                        if (OreDictionary.doesOreNameExist("ingot" + str)
                                && OreDictionary.getOres("ingot" + str).size() > 0
                                && (material == null || !AllowedProducts.getProductByName("STICK")
                                .isOfType(material.getAllowedProducts()))) {

                            // GT registers rods as sticks
                            ItemStack stackToAdd;
                            if (OreDictionary.doesOreNameExist("rod" + str)
                                    && OreDictionary.getOres("rod" + str).size() > 0) {
                                stackToAdd = OreDictionary.getOres("rod" + str).get(0).copy();
                                stackToAdd.setCount(2);
                            } else if (OreDictionary.doesOreNameExist("stick" + str)
                                    && OreDictionary.getOres("stick" + str).size() > 0) {
                                stackToAdd = OreDictionary.getOres("stick" + str).get(0).copy();
                                stackToAdd.setCount(2);
                            } else continue;

                            RecipesMachine.getInstance().addRecipe(TileLathe.class, stackToAdd, 300, 20, "ingot" + str);
                        }
                    }
                }
            }
        }
    }
}
