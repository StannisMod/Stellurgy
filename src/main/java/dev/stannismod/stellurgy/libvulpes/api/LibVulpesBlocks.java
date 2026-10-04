package dev.stannismod.stellurgy.libvulpes.api;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.registries.GameData;

import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.Set;

public class LibVulpesBlocks {
	/** Effectively final, process lifetime: filled only by LibVulpesBlocks.registerBlock. */
	public static final Set<Block> blocks = new HashSet<>();
	/** Effectively final, process lifetime: filled only by LibVulpesBlocks.registerBlock. */
	public static final Set<Block> itemBlocks = new HashSet<>();

	/** Effectively final, process lifetime: written only by LibVulpes.createContent. */
	public static Block blockHatch;
	/** Effectively final, process lifetime: written only by LibVulpes.createContent. */
	public static Block blockPhantom;
	/** Effectively final, process lifetime: written only by LibVulpes.createContent. */
	public static Block blockPlaceHolder;
	/** Effectively final, process lifetime: written only by LibVulpes.registerBlocks (null without IC2). */
	public static Block blockIC2Plug;
	/** Effectively final, process lifetime: written only by LibVulpes.createContent. */
	public static Block blockStructureBlock;
	/** Effectively final, process lifetime: written only by LibVulpes.createContent. */
	public static Block blockAdvStructureBlock;
	/** Effectively final, process lifetime: written only by LibVulpes.createContent. */
	public static Block blockCreativeInputPlug;
	/** Effectively final, process lifetime: written only by LibVulpes.createContent. */
	public static Block blockForgeInputPlug;
	/** Effectively final, process lifetime: written only by LibVulpes.createContent. */
	public static Block blockForgeOutputPlug;
	/** Effectively final, process lifetime: written only by LibVulpes.createContent. */
	public static Block blockMotor;
	/** Effectively final, process lifetime: written only by LibVulpes.createContent. */
	public static Block blockAdvancedMotor;
	/** Effectively final, process lifetime: written only by LibVulpes.createContent. */
	public static Block blockEnhancedMotor;
	/** Effectively final, process lifetime: written only by LibVulpes.createContent. */
	public static Block blockEliteMotor;
	//public static List<Block> blockOre = new ArrayList<Block>();
	//public static List<Block> blockMetal = new ArrayList<Block>();
	//public static List<Block> blockCoil = new ArrayList<Block>();
	/** Effectively final, process lifetime: written only by LibVulpes.registerBlocks. */
	public static Block[] motors;
	/** Effectively final, process lifetime: written only by LibVulpes.createContent. */
	public static Block blockCoalGenerator;
	/** Effectively final, process lifetime: written only by LibVulpes.registerBlocks (null without GregTech). */
	public static Block blockGTPlug;

	

	

	

	



	/**
	 * Register a Block with the default ItemBlock class.
	 *
	 * @param block The Block instance
	 * @param <BLOCK>   The Block type
	 * @return The Block instance
	 */
	public static <BLOCK extends Block> BLOCK registerBlock(BLOCK block) {
		return registerBlock(block, ItemBlock.class, true);
	}

	public static <ITEM extends Item> ITEM registerItem(ITEM item) {

		return (ITEM) GameData.register_impl(item);
	}

	/**
	 * Register a Block with a custom ItemBlock class.
	 *
	 * @param <BLOCK>     The Block type
	 * @param block       The Block instance
	 * @param itemFactory A function that creates the ItemBlock instance, or null if no ItemBlock should be created
	 * @return The Block instance
	 */
	public static <BLOCK extends Block> BLOCK registerBlock(BLOCK block, Class<? extends ItemBlock> clazz, boolean registerItemStates) {
		GameData.register_impl(block);

		if (clazz != null) {
			ItemBlock itemBlock;
			try {

				itemBlock = clazz.getDeclaredConstructor(Block.class).newInstance(block);

				if(registerItemStates) {
					itemBlocks.add(block);
				}

				GameData.register_impl(itemBlock.setRegistryName(block.getRegistryName()));
			} catch (InstantiationException | IllegalAccessException e) {
				e.printStackTrace();
			} catch (IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}

		blocks.add(block);
		return block;
	}
}
