package dev.stannismod.stellurgy.integration.jei;

import mezz.jei.api.*;
import mezz.jei.api.gui.IAdvancedGuiHandler;
import mezz.jei.api.ingredients.IIngredientBlacklist;
import mezz.jei.api.recipe.IRecipeCategoryRegistration;
import net.minecraft.item.ItemStack;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.api.StellurgyItems;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.block.BlockSmallPlatePress;
import dev.stannismod.stellurgy.integration.jei.arcFurnace.ArcFurnaceCategory;
import dev.stannismod.stellurgy.integration.jei.arcFurnace.ArcFurnaceRecipeHandler;
import dev.stannismod.stellurgy.integration.jei.arcFurnace.ArcFurnaceRecipeMaker;
import dev.stannismod.stellurgy.integration.jei.asteroids.AsteroidCategory;
import dev.stannismod.stellurgy.integration.jei.asteroids.AsteroidRecipeHandler;
import dev.stannismod.stellurgy.integration.jei.asteroids.AsteroidRecipeMaker;
import dev.stannismod.stellurgy.integration.jei.centrifuge.CentrifugeCategory;
import dev.stannismod.stellurgy.integration.jei.centrifuge.CentrifugeRecipeHandler;
import dev.stannismod.stellurgy.integration.jei.centrifuge.CentrifugeRecipeMaker;
import dev.stannismod.stellurgy.integration.jei.chemicalReactor.ChemicalReactorCategory;
import dev.stannismod.stellurgy.integration.jei.chemicalReactor.ChemicalReactorRecipeHandler;
import dev.stannismod.stellurgy.integration.jei.chemicalReactor.ChemicalReactorRecipeMaker;
import dev.stannismod.stellurgy.integration.jei.co2scrubber.Co2ScrubberCategory;
import dev.stannismod.stellurgy.integration.jei.co2scrubber.Co2ScrubberRecipeHandler;
import dev.stannismod.stellurgy.integration.jei.co2scrubber.Co2ScrubberRecipeMaker;
import dev.stannismod.stellurgy.integration.jei.crystallizer.CrystallizerCategory;
import dev.stannismod.stellurgy.integration.jei.crystallizer.CrystallizerRecipeHandler;
import dev.stannismod.stellurgy.integration.jei.crystallizer.CrystallizerRecipeMaker;
import dev.stannismod.stellurgy.integration.jei.electrolyser.ElectrolyzerCategory;
import dev.stannismod.stellurgy.integration.jei.electrolyser.ElectrolyzerRecipeHandler;
import dev.stannismod.stellurgy.integration.jei.electrolyser.ElectrolyzerRecipeMaker;
import dev.stannismod.stellurgy.integration.jei.fuelingStation.FuelingStationCategory;
import dev.stannismod.stellurgy.integration.jei.fuelingStation.FuelingStationRecipeHandler;
import dev.stannismod.stellurgy.integration.jei.fuelingStation.FuelingStationRecipeMaker;
import dev.stannismod.stellurgy.integration.jei.gasgiants.GasGiantCategory;
import dev.stannismod.stellurgy.integration.jei.gasgiants.GasGiantRecipeHandler;
import dev.stannismod.stellurgy.integration.jei.gasgiants.GasGiantRecipeMaker;
import dev.stannismod.stellurgy.integration.jei.lathe.LatheCategory;
import dev.stannismod.stellurgy.integration.jei.lathe.LatheRecipeHandler;
import dev.stannismod.stellurgy.integration.jei.lathe.LatheRecipeMaker;
import dev.stannismod.stellurgy.integration.jei.orbitalLaserDrill.OrbitalLaserDrillCategory;
import dev.stannismod.stellurgy.integration.jei.orbitalLaserDrill.OrbitalLaserDrillRecipeHandler;
import dev.stannismod.stellurgy.integration.jei.orbitalLaserDrill.OrbitalLaserDrillRecipeMaker;
import dev.stannismod.stellurgy.integration.jei.platePresser.PlatePressCategory;
import dev.stannismod.stellurgy.integration.jei.platePresser.PlatePressRecipeHandler;
import dev.stannismod.stellurgy.integration.jei.platePresser.PlatePressRecipeMaker;
import dev.stannismod.stellurgy.integration.jei.precisionAssembler.PrecisionAssemblerCategory;
import dev.stannismod.stellurgy.integration.jei.precisionAssembler.PrecisionAssemblerRecipeHandler;
import dev.stannismod.stellurgy.integration.jei.precisionAssembler.PrecisionAssemblerRecipeMaker;
import dev.stannismod.stellurgy.integration.jei.precisionLaserEtcher.PrecisionLaserEtcherCategory;
import dev.stannismod.stellurgy.integration.jei.precisionLaserEtcher.PrecisionLaserEtcherRecipeHandler;
import dev.stannismod.stellurgy.integration.jei.precisionLaserEtcher.PrecisionLaserEtcherRecipeMaker;
import dev.stannismod.stellurgy.integration.jei.rollingMachine.RollingMachineCategory;
import dev.stannismod.stellurgy.integration.jei.rollingMachine.RollingMachineRecipeHandler;
import dev.stannismod.stellurgy.integration.jei.rollingMachine.RollingMachineRecipeMaker;
import dev.stannismod.stellurgy.integration.jei.sawmill.SawMillCategory;
import dev.stannismod.stellurgy.integration.jei.sawmill.SawMillRecipeHandler;
import dev.stannismod.stellurgy.integration.jei.sawmill.SawMillRecipeMaker;
import dev.stannismod.stellurgy.integration.jei.satelliteBuilder.SatelliteBuilderCategory;
import dev.stannismod.stellurgy.integration.jei.satelliteBuilder.SatelliteBuilderRecipeHandler;
import dev.stannismod.stellurgy.integration.jei.satelliteBuilder.SatelliteBuilderRecipeMaker;
import dev.stannismod.stellurgy.integration.jei.stationAssembler.StationAssemblerCategory;
import dev.stannismod.stellurgy.integration.jei.stationAssembler.StationAssemblerRecipeHandler;
import dev.stannismod.stellurgy.integration.jei.stationAssembler.StationAssemblerRecipeMaker;
import dev.stannismod.stellurgy.tile.infrastructure.TileFuelingStation;
import dev.stannismod.stellurgy.tile.multiblock.machine.*;
import dev.stannismod.stellurgy.tile.satellite.TileSatelliteBuilder;
import dev.stannismod.stellurgy.tile.TileStationAssembler;
import dev.stannismod.stellurgy.libvulpes.inventory.GuiModular;

import mezz.jei.api.IRecipeRegistry;
import net.minecraft.client.Minecraft;
import dev.stannismod.stellurgy.integration.jei.gasgiants.GasGiantWrapper;

import java.util.ArrayList;

import javax.annotation.Nonnull;
import java.awt.*;
import java.util.List;

@JEIPlugin
public class StellurgyJeiPlugin implements IModPlugin {
    public static final String rollingMachineUUID = "stellurgy.rollingMachine";
    public static final String latheUUID = "stellurgy.lathe";
    public static final String precisionAssemblerUUID = "stellurgy.precisionAssembler";
    public static final String sawMillUUID = "stellurgy.sawMill";
    public static final String chemicalReactorUUID = "stellurgy.chemicalReactor";
    public static final String crystallizerUUID = "stellurgy.crystallizer";
    public static final String electrolyzerUUID = "stellurgy.electrolyzer";
    public static final String arcFurnaceUUID = "stellurgy.arcFurnace";
    public static final String platePresser = "stellurgy.platePresser";
    public static final String centrifugeUUID = "stellurgy.centrifuge";
    public static final String precisionLaserEngraverUUID = "stellurgy.precisionlaseretcher";
    public static final String satelliteBuilderUUID = "stellurgy.satelliteBuilder";
    public static final String fuelingStationUUID = "stellurgy.fuelingStation";
    public static final String co2ScrubberUUID = "stellurgy.co2scrubber";
    public static final String stationAssemblerUUID = "stellurgy.stationAssembler";
    public static final String orbitalLaserDrillUUID = "stellurgy.orbitalLaserDrill";
    public static final String asteroidsUUID = "stellurgy.asteroids";
    public static final String gasGiantsUUID = GasGiantCategory.UID;
    /**
     * JEI's own helper facade, as handed to {@link #register}. OWNER: the CLIENT — JEI loads its
     * plugins once per client and this object is JEI's, for as long as JEI is there; nothing here
     * releases it because nothing here may.
     *
     * <p>Static rather than an instance field because the recipe-refresh entry points on this class
     * are static: they are called from outside a JEI callback, where the plugin instance JEI built
     * is not in reach.</p>
     */
    private static IJeiHelpers jeiHelpers;

    private static IJeiRuntime jeiRuntime;

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        jeiRuntime = runtime;
    }

    /**
     * Replace the gas-giant recipes JEI shows with the ones the connected server's galaxy holds.
     * What is removed is asked of JEI's own registry, which is the only list of what JEI shows.
     *
     * @return whether the refresh ran; false while JEI's runtime or the client world is not up yet,
     *         and the caller tries again later
     */
    @SuppressWarnings("unchecked")
    public static boolean refreshGasGiantRecipes() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.world == null || jeiRuntime == null) return false;

        IRecipeRegistry recipeRegistry = jeiRuntime.getRecipeRegistry();
        if (recipeRegistry == null) return false;
        mezz.jei.api.recipe.IRecipeCategory<GasGiantWrapper> category =
                recipeRegistry.getRecipeCategory(gasGiantsUUID);
        if (category != null) {
            for (GasGiantWrapper shown : new ArrayList<>(recipeRegistry.getRecipeWrappers(category))) {
                recipeRegistry.removeRecipe(shown, gasGiantsUUID);
            }
        }
        for (GasGiantWrapper recipe : GasGiantRecipeMaker.getRecipes(jeiHelpers)) {
            recipeRegistry.addRecipe(recipe, gasGiantsUUID);
        }
        return true;
    }

    /* newer JEI doesnt have this
    //Stellurgy machines can reload recipes. We still need this for JEI to be up-to-date
    @SuppressWarnings("deprecation")
    public static void reload() {
        jeiHelpers.reload();
    }
    */
    private static boolean isVoidDrillJeiEnabled() {
        StellurgyConfiguration cfg = StellurgyConfiguration.getCurrentConfig();
        return cfg.enableLaserDrill && !cfg.laserDrillPlanet;
    }


    @Override
    public void registerCategories(IRecipeCategoryRegistration registry) {
        jeiHelpers = registry.getJeiHelpers();
        IGuiHelper guiHelper = jeiHelpers.getGuiHelper();
        //debug
        //dev.stannismod.stellurgy.Stellurgy.logger.info("[JEI][GasGiants] registerCategories called");
        registry.addRecipeCategories(
            new RollingMachineCategory(guiHelper),
            new LatheCategory(guiHelper),
            new PrecisionAssemblerCategory(guiHelper),
            new SawMillCategory(guiHelper),
            new ChemicalReactorCategory(guiHelper),
            new CrystallizerCategory(guiHelper),
            new ElectrolyzerCategory(guiHelper),
            new ArcFurnaceCategory(guiHelper),
            new PlatePressCategory(guiHelper),
            new CentrifugeCategory(guiHelper),
            new PrecisionLaserEtcherCategory(guiHelper),
            new SatelliteBuilderCategory(guiHelper),
            new FuelingStationCategory(guiHelper),
            new Co2ScrubberCategory(guiHelper),
            new StationAssemblerCategory(guiHelper),
            new AsteroidCategory(guiHelper),
            new GasGiantCategory(guiHelper)
        );
        // ---- Orbital Laser Drill (VoidDrill mode only) ----
        final boolean voidDrillJei = isVoidDrillJeiEnabled();
        if (voidDrillJei) {
            registry.addRecipeCategories(new OrbitalLaserDrillCategory(guiHelper));
        }
    }



    @Override
    public void register(IModRegistry registry) {
        //debug
        //dev.stannismod.stellurgy.Stellurgy.logger.info("[JEI][GasGiants] register called");
        registry.addAdvancedGuiHandlers(new IAdvancedGuiHandler<GuiModular>() {
            @Override
            @Nonnull
            public Class<GuiModular> getGuiContainerClass() {
                return GuiModular.class;
            }

            @Override
            public List<Rectangle> getGuiExtraAreas(GuiModular guiContainer) {
                return guiContainer.getExtraAreasCovered();
            }

            @Override
            public Object getIngredientUnderMouse(GuiModular guiContainer,
                                                  int mouseX, int mouseY) {
                return null;
            }
        });

        IIngredientBlacklist blacklist = registry.getJeiHelpers().getIngredientBlacklist();
        //Hide problematic blocks
        blacklist.addIngredientToBlacklist(new ItemStack(StellurgyBlocks.blockLightSource));
        blacklist.addIngredientToBlacklist(new ItemStack(StellurgyBlocks.blockAirLock));
        //Hide problematic items
        blacklist.addIngredientToBlacklist(new ItemStack(StellurgyItems.itemSpaceStation));


        registry.addRecipeHandlers(new RollingMachineRecipeHandler(),
                new LatheRecipeHandler(),
                new PrecisionAssemblerRecipeHandler(),
                new SawMillRecipeHandler(),
                new ChemicalReactorRecipeHandler(),
                new CrystallizerRecipeHandler(),
                new ElectrolyzerRecipeHandler(),
                new ArcFurnaceRecipeHandler(),
                new PlatePressRecipeHandler(),
                new CentrifugeRecipeHandler(),
                new PrecisionLaserEtcherRecipeHandler(),
                new SatelliteBuilderRecipeHandler(),
                new FuelingStationRecipeHandler(),
                new Co2ScrubberRecipeHandler(),
                new StationAssemblerRecipeHandler(),
                new AsteroidRecipeHandler(),
                new GasGiantRecipeHandler()
            );

        registry.addRecipes(RollingMachineRecipeMaker.getMachineRecipes(jeiHelpers, TileRollingMachine.class), rollingMachineUUID);
        registry.addRecipes(LatheRecipeMaker.getMachineRecipes(jeiHelpers, TileLathe.class), latheUUID);
        registry.addRecipes(PrecisionAssemblerRecipeMaker.getMachineRecipes(jeiHelpers, TilePrecisionAssembler.class), precisionAssemblerUUID);
        registry.addRecipes(SawMillRecipeMaker.getMachineRecipes(jeiHelpers, TileCuttingMachine.class), sawMillUUID);
        registry.addRecipes(CrystallizerRecipeMaker.getMachineRecipes(jeiHelpers, TileCrystallizer.class), crystallizerUUID);
        registry.addRecipes(ArcFurnaceRecipeMaker.getMachineRecipes(jeiHelpers, TileElectricArcFurnace.class), arcFurnaceUUID);
        registry.addRecipes(PlatePressRecipeMaker.getMachineRecipes(jeiHelpers, BlockSmallPlatePress.class), platePresser);
        registry.addRecipes(ElectrolyzerRecipeMaker.getMachineRecipes(jeiHelpers, TileElectrolyser.class), electrolyzerUUID);
        registry.addRecipes(ChemicalReactorRecipeMaker.getMachineRecipes(jeiHelpers, TileChemicalReactor.class), chemicalReactorUUID);
        registry.addRecipes(CentrifugeRecipeMaker.getMachineRecipes(jeiHelpers, TileCentrifuge.class), centrifugeUUID);
        registry.addRecipes(PrecisionLaserEtcherRecipeMaker.getMachineRecipes(jeiHelpers, TilePrecisionLaserEtcher.class), precisionLaserEngraverUUID);
        registry.addRecipes(SatelliteBuilderRecipeMaker.getMachineRecipes(jeiHelpers, TileSatelliteBuilder.class), satelliteBuilderUUID);
        registry.addRecipes(FuelingStationRecipeMaker.getMachineRecipes(jeiHelpers, TileFuelingStation.class), fuelingStationUUID);
        registry.addRecipes(Co2ScrubberRecipeMaker.getRecipes(jeiHelpers), co2ScrubberUUID);
        registry.addRecipes(StationAssemblerRecipeMaker.getMachineRecipes(jeiHelpers, TileStationAssembler.class),stationAssemblerUUID);
        registry.addRecipes(AsteroidRecipeMaker.getRecipes(jeiHelpers), asteroidsUUID);
        /*//remove this?
        registry.addRecipes(
                GasGiantRecipeMaker.getMachineRecipes(jeiHelpers, TileUnmannedVehicleAssembler.class),
                gasGiantsUUID
        );
*/

        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockRollingMachine), rollingMachineUUID);
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockLathe), latheUUID);
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockPrecisionAssembler), precisionAssemblerUUID);
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockCuttingMachine), sawMillUUID);
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockCrystallizer), crystallizerUUID);
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockElectrolyser), electrolyzerUUID);
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockChemicalReactor), chemicalReactorUUID);
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockArcFurnace), arcFurnaceUUID);
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockPlatePress), platePresser);
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockCentrifuge), centrifugeUUID);
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockPrecisionLaserEngraver), precisionLaserEngraverUUID);
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockSatelliteBuilder), satelliteBuilderUUID);
        // Station Assembler catalyst
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockStationBuilder), stationAssemblerUUID);
        registry.addRecipeCatalyst(new ItemStack(StellurgyItems.itemSpaceStationChip), stationAssemblerUUID);
        // Co2 Scrubber catalysts
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockCO2Scrubber),  co2ScrubberUUID);
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockOxygenVent),   co2ScrubberUUID);

        // One tab: Fueling Station + Tank-type catalysts (mono / biprop fuel / oxidizer / working fluid)
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockFuelingStation), fuelingStationUUID);
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockFuelTank),             fuelingStationUUID); // mono
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockBipropellantFuelTank), fuelingStationUUID); // biprop fuel
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockOxidizerFuelTank),     fuelingStationUUID); // oxidizer
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockNuclearFuelTank),      fuelingStationUUID); // working fluid

        // Asteroids: observatory and asteroid chip are what players associate with this system
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockObservatory), asteroidsUUID);
        registry.addRecipeCatalyst(new ItemStack(StellurgyItems.itemAsteroidChip), asteroidsUUID);

        // Gas missions use the Unmanned Vehicle Assembler / Deployable Rocket Builder
        registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockDeployableRocketBuilder), gasGiantsUUID);

        // ---- Orbital Laser Drill (VoidDrill mode only) ----
        // Voiddrill means laserdrillPlanet is false
        final boolean voidDrillJei = isVoidDrillJeiEnabled();
        if (voidDrillJei) {
            registry.addRecipeHandlers(new OrbitalLaserDrillRecipeHandler());
            registry.addRecipes(OrbitalLaserDrillRecipeMaker.getRecipes(jeiHelpers), orbitalLaserDrillUUID);
            registry.addRecipeCatalyst(new ItemStack(StellurgyBlocks.blockSpaceLaser), orbitalLaserDrillUUID);
        }
    }
}
