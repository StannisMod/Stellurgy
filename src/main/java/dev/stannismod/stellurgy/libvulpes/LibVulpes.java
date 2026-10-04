package dev.stannismod.stellurgy.libvulpes;


import com.google.common.collect.Lists;
import ic2.api.item.IC2Items;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.event.RegistryEvent.MissingMappings.Mapping;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapelessOreRecipe;
import net.minecraftforge.registries.GameData;
import org.apache.logging.log4j.LogManager;
import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.libvulpes.api.LibVulpesBlocks;
import dev.stannismod.stellurgy.libvulpes.api.LibVulpesItems;
import dev.stannismod.stellurgy.libvulpes.api.material.AllowedProducts;
import dev.stannismod.stellurgy.libvulpes.api.material.MaterialRegistry;
import dev.stannismod.stellurgy.libvulpes.block.*;
import dev.stannismod.stellurgy.libvulpes.block.multiblock.BlockHatch;
import dev.stannismod.stellurgy.libvulpes.block.multiblock.BlockMultiMachineBattery;
import dev.stannismod.stellurgy.libvulpes.block.multiblock.BlockMultiblockPlaceHolder;
import dev.stannismod.stellurgy.libvulpes.cap.TeslaHandler;
import dev.stannismod.stellurgy.libvulpes.common.CommonProxy;
import dev.stannismod.stellurgy.libvulpes.event.BucketHandler;
import dev.stannismod.stellurgy.libvulpes.interfaces.IRecipe;
import dev.stannismod.stellurgy.libvulpes.inventory.GuiHandler;
import dev.stannismod.stellurgy.libvulpes.items.ItemBlockMeta;
import dev.stannismod.stellurgy.libvulpes.items.ItemIngredient;
import dev.stannismod.stellurgy.libvulpes.items.ItemLinker;
import dev.stannismod.stellurgy.libvulpes.items.ItemProjector;
import dev.stannismod.stellurgy.libvulpes.recipe.RecipesMachine;
import dev.stannismod.stellurgy.libvulpes.tile.TileInventoriedPointer;
import dev.stannismod.stellurgy.libvulpes.tile.TilePointer;
import dev.stannismod.stellurgy.libvulpes.tile.TileSchematic;
import dev.stannismod.stellurgy.libvulpes.tile.energy.*;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiBlock;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TilePlaceholder;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.hatch.TileFluidHatch;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.hatch.TileInputHatch;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.hatch.TileOutputHatch;
import dev.stannismod.stellurgy.libvulpes.util.ModCompatDictionary;
import dev.stannismod.stellurgy.libvulpes.util.TeslaCapabilityProvider;
import dev.stannismod.stellurgy.libvulpes.util.XMLRecipeLoader;

import javax.annotation.Nonnull;
import java.io.*;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

// Merged into Stellurgy from what was once a separate mod, and folded into its single mod
// container: no longer its own @Mod.
// Stellurgy drives the lifecycle below (preInit/init/postInit) from its own @Mod.EventHandlers,
// and the load-order constraints this class used to declare on its @Mod are now the host's.
public class LibVulpes {
	/**
	 * The registry domain of everything libVulpes registers, and of its assets. It is NOT the
	 * owning modid - the owner is the host container - so every registration below names it
	 * explicitly: a bare {@code setRegistryName("x")} resolves against whichever container is
	 * active, which here is the host's, and would move libVulpes' blocks into the host domain
	 * while their blockstates stay under assets/libvulpes/.
	 */
	public static final String REGISTRY_DOMAIN = "libvulpes";

	/** Effectively final, process lifetime: built once at class initialisation. */
	public static org.apache.logging.log4j.Logger logger = LogManager.getLogger("libVulpes");
	/** Effectively final, process lifetime: filled only by {@link #registerRecipeHandler} during pre-init. */
	private final HashMap<Class, String> userModifiableRecipes = new HashMap<>();

	/** Effectively final, process lifetime: built with this object, dropped by {@link #preInit}. */
	private Object teslaHandler = new TeslaHandler();

	// modId names the HOST: this class is not a @Mod class, so FML cannot infer the owner from
	// the class name and would silently skip the injection.
	@SidedProxy(modId = Constants.modId, clientSide="dev.stannismod.stellurgy.libvulpes.client.ClientProxy", serverSide="dev.stannismod.stellurgy.libvulpes.common.CommonProxy")
	public static CommonProxy proxy;

	/** Effectively final, process lifetime: built with this object. */
	private final CreativeTabs tabMultiblock = new CreativeTabs("multiBlock") {
		@Override
		@Nonnull
		public ItemStack getTabIconItem() {
			return new ItemStack(LibVulpesItems.itemLinker);// AdvancedRocketryItems.itemSatelliteIdChip;
		}
	};

	// Labelled by the host's lang files (itemGroup.stellurgyOres): the host's ores share this tab.
	/** Effectively final, process lifetime: built with this object. */
	public final CreativeTabs tabLibVulpesOres = new CreativeTabs("stellurgyOres") {

		@Override
		@Nonnull
		public ItemStack getTabIconItem() {
			return MaterialRegistry.getMaterialFromName("Copper").getProduct(AllowedProducts.getProductByName("ORE"));
		}
	};

	/** Effectively final, process lifetime: built with this object; filled at registration. */
	public final MaterialRegistry materialRegistry = new MaterialRegistry(REGISTRY_DOMAIN);

	public void registerRecipeHandler(Class clazz, String fileName) {
		userModifiableRecipes.put(clazz, fileName);
	}

	/**
	 * Built by the host mod object, which owns it ({@code Stellurgy.instance.libVulpes}) - libVulpes is
	 * not a mod of its own any more, so FML has no container and no {@code @Instance} for it, and it can
	 * never be passed to {@code openGui} or {@code registerGuiHandler}; those take the host. Its blocks
	 * and items are created in {@link #preInit}, not here.
	 */
	public LibVulpes() {
	}

	/** What the constructor did while FML constructed libVulpes as a mod of its own. */
	private void createContent()
    {
        //Initialize Blocks
        LibVulpesBlocks.blockPhantom = new BlockPhantom(Material.CIRCUITS).setUnlocalizedName("blockPhantom");
        LibVulpesBlocks.blockHatch = new BlockHatch(Material.IRON).setUnlocalizedName("hatch").setCreativeTab(tabMultiblock).setHardness(3f);
        LibVulpesBlocks.blockPlaceHolder = new BlockMultiblockPlaceHolder().setUnlocalizedName("placeHolder").setHardness(1f);
        LibVulpesBlocks.blockAdvStructureBlock = new BlockAlphaTexture(Material.IRON).setUnlocalizedName("advStructureMachine").setCreativeTab(tabMultiblock).setHardness(3f);
        LibVulpesBlocks.blockStructureBlock = new BlockAlphaTexture(Material.IRON).setUnlocalizedName("structureMachine").setCreativeTab(tabMultiblock).setHardness(3f);
        LibVulpesBlocks.blockCreativeInputPlug = new BlockMultiMachineBattery(Material.IRON, TileCreativePowerInput.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("creativePowerBattery").setCreativeTab(tabMultiblock).setHardness(3f);
        LibVulpesBlocks.blockForgeInputPlug = new BlockMultiMachineBattery(Material.IRON, TileForgePowerInput.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("forgePowerInput").setCreativeTab(tabMultiblock).setHardness(3f);
        LibVulpesBlocks.blockForgeOutputPlug = new BlockMultiMachineBattery(Material.IRON, TileForgePowerOutput.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("forgePowerOutput").setCreativeTab(tabMultiblock).setHardness(3f);
        LibVulpesBlocks.blockCoalGenerator = new BlockTileComparatorOverride(TileCoalGenerator.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("coalGenerator").setCreativeTab(tabMultiblock).setHardness(3f);
        //LibVulpesBlocks.blockRFBattery = new BlockMultiMachineBattery(Material.ROCK, TilePlugInputRF.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("rfBattery").setCreativeTab(tabMultiblock).setHardness(3f);
        //LibVulpesBlocks.blockRFOutput = new BlockMultiMachineBattery(Material.ROCK, TilePlugOutputRF.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("rfOutput").setCreativeTab(tabMultiblock).setHardness(3f);

        LibVulpesBlocks.blockMotor = new BlockMotor(Material.IRON, 1f).setCreativeTab(tabMultiblock).setUnlocalizedName("motor").setHardness(2f);
        LibVulpesBlocks.blockAdvancedMotor = new BlockMotor(Material.IRON, 1/1.5f).setCreativeTab(tabMultiblock).setUnlocalizedName("advancedMotor").setHardness(2f);
        LibVulpesBlocks.blockEnhancedMotor = new BlockMotor(Material.IRON, 1/2f).setCreativeTab(tabMultiblock).setUnlocalizedName("enhancedMotor").setHardness(2f);
        LibVulpesBlocks.blockEliteMotor = new BlockMotor(Material.IRON, 1/4f).setCreativeTab(tabMultiblock).setUnlocalizedName("eliteMotor").setHardness(2f);

        //Initialize Items
        LibVulpesItems.itemLinker = new ItemLinker().setUnlocalizedName("Linker").setCreativeTab(tabMultiblock).setRegistryName(REGISTRY_DOMAIN, "linker");
        LibVulpesItems.itemBattery = new ItemIngredient(2).setUnlocalizedName("libvulpes:battery").setCreativeTab(tabMultiblock).setRegistryName(REGISTRY_DOMAIN, "battery");
        LibVulpesItems.itemHoloProjector = new ItemProjector().setUnlocalizedName("holoProjector").setCreativeTab(tabMultiblock).setRegistryName(REGISTRY_DOMAIN, "holoProjector");
        
        
    }

    @SubscribeEvent(priority=EventPriority.HIGH)
    public void registerItems(RegistryEvent.Register<Item> evt)
    {
        //Register Items
        LibVulpesBlocks.registerItem(LibVulpesItems.itemLinker);
        LibVulpesBlocks.registerItem(LibVulpesItems.itemBattery);
        LibVulpesBlocks.registerItem(LibVulpesItems.itemHoloProjector);
        
        OreDictionary.registerOre("itemBattery", new ItemStack(LibVulpesItems.itemBattery,1,0));
    }
    
	@SideOnly(Side.CLIENT)
	@SubscribeEvent
	public void registerModels(ModelRegistryEvent event) {
		proxy.preInitItems();
		proxy.preInitBlocks();
	}
    

    private void registerRecipes()
    {
        List<net.minecraft.item.crafting.IRecipe> toRegister = Lists.newArrayList();
   
//
      
//      
//      //Plugs
        if(Loader.isModLoaded("ic2")) {
          toRegister.add(new ShapelessOreRecipe(null, new ItemStack(LibVulpesBlocks.blockIC2Plug), LibVulpesBlocks.blockStructureBlock, 
                  IC2Items.getItem("te","mv_transformer"), LibVulpesItems.itemBattery).setRegistryName(new ResourceLocation("libvulpes", "blockIC2Plug")));
        }
        if(Loader.isModLoaded("gregtech")) {
          toRegister.add(new ShapelessOreRecipe(null, new ItemStack(LibVulpesBlocks.blockGTPlug), LibVulpesBlocks.blockStructureBlock, 
                  "plateBatteryAlloy","plateBatteryAlloy", LibVulpesItems.itemBattery).setRegistryName(new ResourceLocation("libvulpes", "blockGTPlug")));
        }
        
        for(net.minecraft.item.crafting.IRecipe recipe: toRegister)
        {
            GameData.register_impl(recipe);
        }
        
//      //GameRegistry.addShapelessRecipe(new ItemStack(LibVulpesBlocks.blockRFBattery), new ItemStack(LibVulpesBlocks.blockRFOutput));
//      //GameRegistry.addShapelessRecipe(new ItemStack(LibVulpesBlocks.blockRFOutput), new ItemStack(LibVulpesBlocks.blockRFBattery));
    }
	
	@SubscribeEvent(priority=EventPriority.HIGH)
    public void registerBlocks(RegistryEvent.Register<Block> evt)
	{
        //Register Blocks
        LibVulpesBlocks.registerBlock(LibVulpesBlocks.blockPhantom.setRegistryName(REGISTRY_DOMAIN, LibVulpesBlocks.blockPhantom.getUnlocalizedName().substring(5)));
        LibVulpesBlocks.registerBlock(LibVulpesBlocks.blockHatch.setRegistryName(REGISTRY_DOMAIN, LibVulpesBlocks.blockHatch.getUnlocalizedName().substring(5)), ItemBlockMeta.class, false);
        LibVulpesBlocks.registerBlock(LibVulpesBlocks.blockPlaceHolder.setRegistryName(REGISTRY_DOMAIN, LibVulpesBlocks.blockPlaceHolder.getUnlocalizedName().substring(5)));
        LibVulpesBlocks.registerBlock(LibVulpesBlocks.blockStructureBlock.setRegistryName(REGISTRY_DOMAIN, LibVulpesBlocks.blockStructureBlock.getUnlocalizedName().substring(5)));
		LibVulpesBlocks.registerBlock(LibVulpesBlocks.blockAdvStructureBlock.setRegistryName(REGISTRY_DOMAIN, LibVulpesBlocks.blockAdvStructureBlock.getUnlocalizedName().substring(5)));
        LibVulpesBlocks.registerBlock(LibVulpesBlocks.blockCreativeInputPlug.setRegistryName(REGISTRY_DOMAIN, LibVulpesBlocks.blockCreativeInputPlug.getUnlocalizedName().substring(5)));
        LibVulpesBlocks.registerBlock(LibVulpesBlocks.blockForgeInputPlug.setRegistryName(REGISTRY_DOMAIN, LibVulpesBlocks.blockForgeInputPlug.getUnlocalizedName().substring(5)));
        LibVulpesBlocks.registerBlock(LibVulpesBlocks.blockForgeOutputPlug.setRegistryName(REGISTRY_DOMAIN, LibVulpesBlocks.blockForgeOutputPlug.getUnlocalizedName().substring(5)));
        LibVulpesBlocks.registerBlock(LibVulpesBlocks.blockCoalGenerator.setRegistryName(REGISTRY_DOMAIN, LibVulpesBlocks.blockCoalGenerator.getUnlocalizedName().substring(5)));

        LibVulpesBlocks.registerBlock(LibVulpesBlocks.blockMotor.setRegistryName(REGISTRY_DOMAIN, "motor"));
        LibVulpesBlocks.registerBlock(LibVulpesBlocks.blockAdvancedMotor.setRegistryName(REGISTRY_DOMAIN, "advancedMotor"));
        LibVulpesBlocks.registerBlock(LibVulpesBlocks.blockEnhancedMotor.setRegistryName(REGISTRY_DOMAIN, "enhancedMotor"));
        LibVulpesBlocks.registerBlock(LibVulpesBlocks.blockEliteMotor.setRegistryName(REGISTRY_DOMAIN, "eliteMotor"));
        //LibVulpesBlocks.registerBlock(LibVulpesBlocks.blockRFBattery.setRegistryName(LibVulpesBlocks.blockRFBattery.getUnlocalizedName()));
        //LibVulpesBlocks.registerBlock(LibVulpesBlocks.blockRFOutput.setRegistryName(LibVulpesBlocks.blockRFOutput.getUnlocalizedName()));

        //populate lists
		LibVulpesBlocks.motors = new Block[]{ LibVulpesBlocks.blockMotor, LibVulpesBlocks.blockAdvancedMotor, LibVulpesBlocks.blockEnhancedMotor, LibVulpesBlocks.blockEliteMotor };

        //Register Tile
        GameRegistry.registerTileEntity(TileOutputHatch.class, "vulpesoutputHatch");
        GameRegistry.registerTileEntity(TileInputHatch.class, "vulpesinputHatch");
        GameRegistry.registerTileEntity(TilePlaceholder.class, "vulpesplaceHolder");
        GameRegistry.registerTileEntity(TileFluidHatch.class, "vulpesFluidHatch");
        GameRegistry.registerTileEntity(TileSchematic.class, "vulpesTileSchematic");
        GameRegistry.registerTileEntity(TileCreativePowerInput.class, "vulpesCreativeBattery");
        GameRegistry.registerTileEntity(TileForgePowerInput.class, "vulpesForgePowerInput");
        GameRegistry.registerTileEntity(TileForgePowerOutput.class, "vulpesForgePowerOutput");
        GameRegistry.registerTileEntity(TileCoalGenerator.class, "vulpesCoalGenerator");
        //GameRegistry.registerTileEntity(TilePlugInputRF.class, "ARrfBattery");
        //GameRegistry.registerTileEntity(TilePlugOutputRF.class, "ARrfOutputRF");
        GameRegistry.registerTileEntity(TilePointer.class, "vulpesTilePointer");
        GameRegistry.registerTileEntity(TileInventoriedPointer.class, "vulpesTileInvPointer");


        //MOD-SPECIFIC ENTRIES --------------------------------------------------------------------------------------------------------------------------
        //Items dependant on IC2
        if(Loader.isModLoaded("ic2")) {
            LibVulpesBlocks.blockIC2Plug = new BlockMultiMachineBattery(Material.ROCK ,TilePlugInputIC2.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("IC2Plug").setCreativeTab(tabMultiblock).setHardness(3f);
            LibVulpesBlocks.registerBlock(LibVulpesBlocks.blockIC2Plug.setRegistryName(REGISTRY_DOMAIN, LibVulpesBlocks.blockIC2Plug.getUnlocalizedName().substring(5)));
            GameRegistry.registerTileEntity(TilePlugInputIC2.class, "ARIC2Plug");
        }
        
        if(Loader.isModLoaded("gregtech")) {
            LibVulpesBlocks.blockGTPlug = new BlockMultiMachineBattery(Material.ROCK ,TilePlugInputGregTech.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("GTPlug").setCreativeTab(tabMultiblock).setHardness(3f);
            LibVulpesBlocks.registerBlock(LibVulpesBlocks.blockGTPlug.setRegistryName(REGISTRY_DOMAIN, LibVulpesBlocks.blockGTPlug.getUnlocalizedName().substring(5)));
            GameRegistry.registerTileEntity(TilePlugInputGregTech.class, "ARGTPlug");
        }


        if(FMLCommonHandler.instance().getSide().isClient()) {
            //Register Block models
            Item blockItem = Item.getItemFromBlock(LibVulpesBlocks.blockHatch);
            ModelLoader.setCustomModelResourceLocation(blockItem, 0, new ModelResourceLocation("libvulpes:inputHatch", "inventory"));
            ModelLoader.setCustomModelResourceLocation(blockItem, 1, new ModelResourceLocation("libvulpes:outputHatch", "inventory"));
            ModelLoader.setCustomModelResourceLocation(blockItem, 2, new ModelResourceLocation("libvulpes:fluidInputHatch", "inventory"));
            ModelLoader.setCustomModelResourceLocation(blockItem, 3, new ModelResourceLocation("libvulpes:fluidOutputHatch", "inventory"));
        }
        
        materialRegistry.registerOres(tabLibVulpesOres);
        
        //Ore dict stuff
		//Motors
        OreDictionary.registerOre("blockMotor", LibVulpesBlocks.blockMotor);
        OreDictionary.registerOre("blockMotor", LibVulpesBlocks.blockAdvancedMotor);
        OreDictionary.registerOre("blockMotor", LibVulpesBlocks.blockEnhancedMotor);
        OreDictionary.registerOre("blockMotor", LibVulpesBlocks.blockEliteMotor);
	}
	
	@SubscribeEvent(priority=EventPriority.HIGH)
	public void missingMappings(RegistryEvent.MissingMappings<Item> evt)
	{
		for(Mapping<Item> mapping : evt.getAllMappings())
		{
			if (mapping.key.compareTo(new ResourceLocation("libvulpes:productcrystal")) == 0)
				mapping.remap(MaterialRegistry.getItemStackFromMaterialAndType("Dilithium", AllowedProducts.getProductByName("GEM")).getItem());
			
		}
	}

	/**
	 * Called by the host at the very start of its own pre-init, before the host's content exists:
	 * the host builds on the products, materials and packet discriminators registered here, as it
	 * did when FML ran this mod first because of the host's {@code required-after}.
	 */
	public void preInit(FMLPreInitializationEvent event)
	{
		createContent();
		// Here, not at class-load, so the order against the host's own registry listeners is
		// decided by the host's call order rather than by whenever this class happened to load.
		// Both register at HIGH priority, and within one priority the bus fires in registration
		// order: libVulpes' blocks must exist before the host's multiblocks name them.
		MinecraftForge.EVENT_BUS.register(this);
		proxy.preInit();
		teslaHandler = null;
		//Configuration - its own file: the event's suggested one is named after the HOST container
		// and would be the host's own config.
		Configuration config = new Configuration(new File(event.getModConfigurationDirectory(), REGISTRY_DOMAIN + ".cfg"));
		config.load();

		dev.stannismod.stellurgy.libvulpes.Configuration.EUMult = (float)config.get(Configuration.CATEGORY_GENERAL, "EUPowerMultiplier", 4, "How many FE one EU makes").getDouble();
		dev.stannismod.stellurgy.libvulpes.Configuration.powerMult =(float)config.get(Configuration.CATEGORY_GENERAL, "PowerMultiplier", 1, "Power multiplier on machines").getDouble();

		config.save();

		TeslaCapabilityProvider.registerCap();


        /*DUST,
        INGOT,
        GEM,
        BOULE,
        NUGGET,
        COIL(true, AdvancedRocketryBlocks.blockCoil),
        PLATE,
        STICK,
        BLOCK(true, LibVulpesBlocks.blockMetal),
        ORE(true, LibVulpesBlocks.blockOre),
        FAN,
        SHEET,
        GEAR;*/

        //Register allowedProducts
        AllowedProducts.registerProduct("DUST");
        AllowedProducts.registerProduct("INGOT");
        AllowedProducts.registerProduct("GEM");
        AllowedProducts.registerProduct("BOULE");
        AllowedProducts.registerProduct("NUGGET");
        AllowedProducts.registerProduct("COIL", true);
        AllowedProducts.registerProduct("PLATE");
        AllowedProducts.registerProduct("STICK");
        AllowedProducts.registerProduct("BLOCK", true);
        AllowedProducts.registerProduct("ORE", true);
        AllowedProducts.registerProduct("FAN");
        AllowedProducts.registerProduct("SHEET");
        AllowedProducts.registerProduct("GEAR");

        //Register Ores

        materialRegistry.registerMaterial(new dev.stannismod.stellurgy.libvulpes.api.material.Material("Dilithium", "pickaxe", 3, 0xddcecb, AllowedProducts.getProductByName("DUST").getFlagValue() | AllowedProducts.getProductByName("GEM").getFlagValue()));
        materialRegistry.registerMaterial(new dev.stannismod.stellurgy.libvulpes.api.material.Material("Iron", "pickaxe", 1, 0xafafaf, AllowedProducts.getProductByName("SHEET").getFlagValue() | AllowedProducts.getProductByName("STICK").getFlagValue() | AllowedProducts.getProductByName("DUST").getFlagValue() | AllowedProducts.getProductByName("PLATE").getFlagValue(), false));
        materialRegistry.registerMaterial(new dev.stannismod.stellurgy.libvulpes.api.material.Material("Gold", "pickaxe", 1, 0xffff5d, AllowedProducts.getProductByName("DUST").getFlagValue() | AllowedProducts.getProductByName("COIL").getFlagValue() | AllowedProducts.getProductByName("PLATE").getFlagValue(), false));
        materialRegistry.registerMaterial(new dev.stannismod.stellurgy.libvulpes.api.material.Material("Silicon", "pickaxe", 1, 0x2c2c2b, AllowedProducts.getProductByName("INGOT").getFlagValue() | AllowedProducts.getProductByName("DUST").getFlagValue() | AllowedProducts.getProductByName("BOULE").getFlagValue() | AllowedProducts.getProductByName("NUGGET").getFlagValue() | AllowedProducts.getProductByName("PLATE").getFlagValue(), false));
        materialRegistry.registerMaterial(new dev.stannismod.stellurgy.libvulpes.api.material.Material("Copper", "pickaxe", 1, 0xd55e28, AllowedProducts.getProductByName("COIL").getFlagValue() | AllowedProducts.getProductByName("BLOCK").getFlagValue() | AllowedProducts.getProductByName("STICK").getFlagValue() | AllowedProducts.getProductByName("INGOT").getFlagValue() | AllowedProducts.getProductByName("NUGGET").getFlagValue() | AllowedProducts.getProductByName("DUST").getFlagValue() | AllowedProducts.getProductByName("PLATE").getFlagValue() | AllowedProducts.getProductByName("SHEET").getFlagValue()));
        materialRegistry.registerMaterial(new dev.stannismod.stellurgy.libvulpes.api.material.Material("Tin", "pickaxe", 1, 0xcdd5d8, AllowedProducts.getProductByName("BLOCK").getFlagValue() | AllowedProducts.getProductByName("PLATE").getFlagValue() | AllowedProducts.getProductByName("INGOT").getFlagValue() | AllowedProducts.getProductByName("NUGGET").getFlagValue() | AllowedProducts.getProductByName("DUST").getFlagValue()));
        materialRegistry.registerMaterial(new dev.stannismod.stellurgy.libvulpes.api.material.Material("Steel", "pickaxe", 1, 0x55555d, AllowedProducts.getProductByName("BLOCK").getFlagValue() | AllowedProducts.getProductByName("FAN").getFlagValue() | AllowedProducts.getProductByName("PLATE").getFlagValue() | AllowedProducts.getProductByName("INGOT").getFlagValue() | AllowedProducts.getProductByName("NUGGET").getFlagValue() | AllowedProducts.getProductByName("DUST").getFlagValue() | AllowedProducts.getProductByName("STICK").getFlagValue() | AllowedProducts.getProductByName("GEAR").getFlagValue() | AllowedProducts.getProductByName("SHEET").getFlagValue(), false));
        materialRegistry.registerMaterial(new dev.stannismod.stellurgy.libvulpes.api.material.Material("Titanium", "pickaxe", 1, 0xccc8fa, AllowedProducts.getProductByName("PLATE").getFlagValue() | AllowedProducts.getProductByName("COIL").getFlagValue() | AllowedProducts.getProductByName("INGOT").getFlagValue() | AllowedProducts.getProductByName("NUGGET").getFlagValue() | AllowedProducts.getProductByName("DUST").getFlagValue() | AllowedProducts.getProductByName("STICK").getFlagValue() | AllowedProducts.getProductByName("BLOCK").getFlagValue() | AllowedProducts.getProductByName("GEAR").getFlagValue() | AllowedProducts.getProductByName("SHEET").getFlagValue(), false));
        materialRegistry.registerMaterial(new dev.stannismod.stellurgy.libvulpes.api.material.Material("Rutile", "pickaxe", 1, 0xbf936a, AllowedProducts.getProductByName("ORE").getFlagValue(), new String[] {"Rutile", "Titanium"}));
        materialRegistry.registerMaterial(new dev.stannismod.stellurgy.libvulpes.api.material.Material("Aluminum", "pickaxe", 1, 0xb3e4dc, AllowedProducts.getProductByName("COIL").getFlagValue() | AllowedProducts.getProductByName("BLOCK").getFlagValue() | AllowedProducts.getProductByName("INGOT").getFlagValue() | AllowedProducts.getProductByName("PLATE").getFlagValue() | AllowedProducts.getProductByName("SHEET").getFlagValue() | AllowedProducts.getProductByName("DUST").getFlagValue() | AllowedProducts.getProductByName("NUGGET").getFlagValue() | AllowedProducts.getProductByName("SHEET").getFlagValue()));
        materialRegistry.registerMaterial(new dev.stannismod.stellurgy.libvulpes.api.material.Material("Iridium", "pickaxe", 2, 0xdedcce, AllowedProducts.getProductByName("COIL").getFlagValue() | AllowedProducts.getProductByName("BLOCK").getFlagValue() | AllowedProducts.getProductByName("DUST").getFlagValue() | AllowedProducts.getProductByName("INGOT").getFlagValue() | AllowedProducts.getProductByName("NUGGET").getFlagValue() | AllowedProducts.getProductByName("PLATE").getFlagValue() | AllowedProducts.getProductByName("STICK").getFlagValue()));
	}

	/**
	 * Called by the host at the start of its own init. libVulpes' GUI handler is NOT registered
	 * here: FML keeps one handler per container, this is not a container, and the host's handler
	 * serves libVulpes' modular GUI ids.
	 */
	public void init(FMLInitializationEvent event) {
		registerRecipes();
		proxy.init();
		proxy.registerEventHandlers();


		if(Loader.isModLoaded("immersiveengineering")) {
			ModCompatDictionary.registerIECoils();
		}

	}

	/** Called by the host at the start of its own post-init. */
	public void postInit(FMLPostInitializationEvent event) {
		MinecraftForge.EVENT_BUS.register(new BucketHandler());
		
		//Init TileMultiblock
		//Item output
		List<BlockMeta> list = new LinkedList<>();
		list.add(new BlockMeta(LibVulpesBlocks.blockHatch, 1));
		list.add(new BlockMeta(LibVulpesBlocks.blockHatch, 9));
		TileMultiBlock.addMapping('O', list);

		//Item Inputs
		list = new LinkedList<>();
		list.add(new BlockMeta(LibVulpesBlocks.blockHatch, 0));
		list.add(new BlockMeta(LibVulpesBlocks.blockHatch, 8));
		TileMultiBlock.addMapping('I', list);

		//Power input
		list = new LinkedList<>();
		list.add(new BlockMeta(LibVulpesBlocks.blockCreativeInputPlug, BlockMeta.WILDCARD));
		list.add(new BlockMeta(LibVulpesBlocks.blockForgeInputPlug, BlockMeta.WILDCARD));
		if(LibVulpesBlocks.blockIC2Plug != null)
			list.add(new BlockMeta(LibVulpesBlocks.blockIC2Plug, BlockMeta.WILDCARD));
		TileMultiBlock.addMapping('P', list);

		//Power output
		list = new LinkedList<>();
		list.add(new BlockMeta(LibVulpesBlocks.blockForgeOutputPlug, BlockMeta.WILDCARD));
		TileMultiBlock.addMapping('p', list);

		//Liquid input
		list = new LinkedList<>();
		list.add(new BlockMeta(LibVulpesBlocks.blockHatch, 2));
		list.add(new BlockMeta(LibVulpesBlocks.blockHatch, 10));
		TileMultiBlock.addMapping('L', list);

		//Liquid output
		list = new LinkedList<>();
		list.add(new BlockMeta(LibVulpesBlocks.blockHatch, 3));
		list.add(new BlockMeta(LibVulpesBlocks.blockHatch, 11));
		TileMultiBlock.addMapping('l', list);

		//User Recipes


	}

	public void loadXMLRecipe(Class clazz) {
		File file = new File(userModifiableRecipes.get(clazz));
		if(!file.exists()) {
			try {
				file.createNewFile();
				BufferedReader inputStream = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/assets/libvulpes/defaultrecipe.xml")));

				BufferedWriter stream2 = new BufferedWriter(new FileWriter(file));


				while(inputStream.ready()) {
					stream2.write(inputStream.readLine() + "\n");
				}


				//Write recipes

				stream2.write("<Recipes useDefault=\"true\">\n");
				for(IRecipe recipe : RecipesMachine.getInstance().getRecipes(clazz)) {
					boolean writeable = true;
					for (ItemStack stack : recipe.getOutput()) {
						if(stack.hasTagCompound()) {
							writeable = false;
							break;
						}
					}

					if(((RecipesMachine.Recipe)recipe).outputToOnlyEmptySlots())
						writeable = false;

					if(writeable)
						stream2.write(XMLRecipeLoader.writeRecipe(recipe) + "\n");
				}
				stream2.write("</Recipes>");
				stream2.close();

				inputStream.close();


			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			XMLRecipeLoader loader = new XMLRecipeLoader();
			try {
				loader.loadFile(file);
				loader.registerRecipes(clazz);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

	}
}

