package dev.stannismod.stellurgy.libvulpes.interfaces;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.List;

public interface IRecipe {
	List<ItemStack> getOutput();
	
	List<FluidStack> getFluidOutputs();
	
	List<List<ItemStack>> getIngredients();
	
	List<FluidStack> getFluidIngredients();
	
	int getTime();
	
	int getPower();
	
	String getOreDictString(int slot);
}
