package dev.stannismod.stellurgy.client;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemMeshDefinition;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ModelBakery;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.block.statemap.StateMapperBase;
import net.minecraft.client.renderer.color.IItemColor;
import net.minecraft.entity.Entity;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.client.event.ModelBakeEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.client.model.obj.OBJLoader;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.client.registry.IRenderFactory;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.api.StellurgyItems;
import dev.stannismod.stellurgy.api.stations.ISpaceObject;
import dev.stannismod.stellurgy.backwardCompat.ModelFormatException;
import dev.stannismod.stellurgy.backwardCompat.WavefrontObject;
import dev.stannismod.stellurgy.block.BlockCrystal;
import dev.stannismod.stellurgy.block.CrystalColorizer;
import dev.stannismod.stellurgy.client.model.ModelRocket;
import dev.stannismod.stellurgy.common.CommonProxy;
import dev.stannismod.stellurgy.event.PlanetEventHandler;
import dev.stannismod.stellurgy.event.RocketEventHandler;
import dev.stannismod.stellurgy.inventory.modules.ModuleContainerPanYOnlyWithScrollCache;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;
import dev.stannismod.stellurgy.stations.SpaceObjectManager;
import dev.stannismod.stellurgy.tile.TileBrokenPart;
import dev.stannismod.stellurgy.tile.TileFluidTank;
import dev.stannismod.stellurgy.tile.TileRocketAssemblingMachine;
import dev.stannismod.stellurgy.tile.multiblock.energy.TileBlackHoleGenerator;
import dev.stannismod.stellurgy.tile.multiblock.energy.TileMicrowaveReciever;
import dev.stannismod.stellurgy.tile.multiblock.energy.TileSolarArray;
import dev.stannismod.stellurgy.tile.multiblock.orbitallaserdrill.TileOrbitalLaserDrill;
import dev.stannismod.stellurgy.libvulpes.entity.fx.FxErrorBlock;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleContainerPan;
import dev.stannismod.stellurgy.libvulpes.tile.TileSchematic;

import net.minecraft.util.text.TextComponentTranslation;
import dev.stannismod.stellurgy.api.IAtmosphere;
import dev.stannismod.stellurgy.client.gui.ModuleSelectableAtmosphereButton;
import dev.stannismod.stellurgy.tile.atmosphere.TileAtmosphereDetector;


import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Loader;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedList;
import java.util.List;
import dev.stannismod.stellurgy.client.render.*;
import dev.stannismod.stellurgy.client.render.entity.*;
import dev.stannismod.stellurgy.client.render.multiblocks.*;
import dev.stannismod.stellurgy.entity.*;
import dev.stannismod.stellurgy.entity.fx.*;
import dev.stannismod.stellurgy.tile.multiblock.*;
import dev.stannismod.stellurgy.tile.multiblock.machine.*;

@Mod.EventBusSubscriber(value = Side.CLIENT)
public class ClientProxy extends CommonProxy {

    private static final Map<ResourceLocation, WavefrontObject> models = new HashMap<>();

    public static WavefrontObject getModel(ResourceLocation location) {
        WavefrontObject model = models.getOrDefault(location, null);
        if (model == null) {
            try {
                model = new WavefrontObject(location);
                models.put(location, model);
            } catch (ModelFormatException e) {
                e.printStackTrace();
            }
        }
        return model;
    }

    @Override
    public void registerRenderers() {
        ClientRegistry.bindTileEntitySpecialRenderer(TileRocketAssemblingMachine.class, new RendererRocketAssemblingMachine());
        ClientRegistry.bindTileEntitySpecialRenderer(TilePrecisionAssembler.class, new RendererPrecisionAssembler());
        ClientRegistry.bindTileEntitySpecialRenderer(TileCuttingMachine.class, new RendererCuttingMachine());
        ClientRegistry.bindTileEntitySpecialRenderer(TileCrystallizer.class, new RendererCrystallizer());
        ClientRegistry.bindTileEntitySpecialRenderer(TileObservatory.class, new RendererObservatory());
        ClientRegistry.bindTileEntitySpecialRenderer(TileAstrobodyDataProcessor.class, new RenderAstrobodyDataProcessor());
        ClientRegistry.bindTileEntitySpecialRenderer(TileLathe.class, new RendererLathe());
        ClientRegistry.bindTileEntitySpecialRenderer(TileRollingMachine.class, new RendererRollingMachine());
        ClientRegistry.bindTileEntitySpecialRenderer(TileElectrolyser.class, new RendererElectrolyser());
        ClientRegistry.bindTileEntitySpecialRenderer(TileChemicalReactor.class, new RendererChemicalReactor("stellurgy:models/chemicalreactor.obj", "stellurgy:textures/models/chemicalreactor.png"));
        ClientRegistry.bindTileEntitySpecialRenderer(TileSchematic.class, new RendererPhantomBlock());
        //ClientRegistry.bindTileEntitySpecialRenderer(TileDrill.class, new RendererDrill());
        ClientRegistry.bindTileEntitySpecialRenderer(TileMicrowaveReciever.class, new RendererMicrowaveReciever());
        //ClientRegistry.bindTileEntitySpecialRenderer(TileOrbitalLaserDrill.class, new RenderOrbitalLaserDrillTile());
        ClientRegistry.bindTileEntitySpecialRenderer(TileBiomeScanner.class, new RenderBiomeScanner());
        ClientRegistry.bindTileEntitySpecialRenderer(TileBlackHoleGenerator.class, new RenderBlackHoleGenerator());
        ClientRegistry.bindTileEntitySpecialRenderer(TileAtmosphereTerraformer.class, new RenderTerraformerAtm());
        ClientRegistry.bindTileEntitySpecialRenderer(TileFluidTank.class, new RenderTank());
        ClientRegistry.bindTileEntitySpecialRenderer(TileOrbitalLaserDrill.class, new RenderOrbitalLaserDrill());
        ClientRegistry.bindTileEntitySpecialRenderer(dev.stannismod.stellurgy.tile.multiblock.TileRailgun.class, new dev.stannismod.stellurgy.client.render.multiblocks.RendererRailgun());
        ClientRegistry.bindTileEntitySpecialRenderer(TileAreaGravityController.class, new RenderAreaGravityController());
        ClientRegistry.bindTileEntitySpecialRenderer(dev.stannismod.stellurgy.tile.multiblock.TileSpaceElevator.class, new dev.stannismod.stellurgy.client.render.multiblocks.RendererSpaceElevator());
        ClientRegistry.bindTileEntitySpecialRenderer(dev.stannismod.stellurgy.tile.multiblock.TileBeacon.class, new dev.stannismod.stellurgy.client.render.multiblocks.RenderBeacon());
        ClientRegistry.bindTileEntitySpecialRenderer(dev.stannismod.stellurgy.tile.multiblock.machine.TileCentrifuge.class, new dev.stannismod.stellurgy.client.render.multiblocks.RenderCentrifuge());
        ClientRegistry.bindTileEntitySpecialRenderer(TilePrecisionLaserEtcher.class, new RendererPrecisionLaserEtcher());
        ClientRegistry.bindTileEntitySpecialRenderer(TileSolarArray.class, new RendererSolarArray());
        ClientRegistry.bindTileEntitySpecialRenderer(TileBrokenPart.class, new RendererBrokenPart());

        //ClientRegistry.bindTileEntitySpecialRenderer(TileModelRenderRotatable.class, modelBlock);

        //RendererModelBlock blockRenderer = new RendererModelBlock();

        RenderingRegistry.registerEntityRenderingHandler(EntityRocket.class, (IRenderFactory<EntityRocket>) new RendererRocket(null));
        RenderingRegistry.registerEntityRenderingHandler(EntityLaserNode.class, (IRenderFactory<EntityLaserNode>) new RenderLaser(2.0, new float[]{1F, 0.25F, 0.25F, 0.2F}, new float[]{0.9F, 0.2F, 0.3F, 0.5F}));
        RenderingRegistry.registerEntityRenderingHandler(EntityItemAbducted.class, (IRenderFactory<EntityItemAbducted>) new RendererItem(Minecraft.getMinecraft().getRenderManager(), Minecraft.getMinecraft().getRenderItem()));
        RenderingRegistry.registerEntityRenderingHandler(EntityUIPlanet.class, (IRenderFactory<EntityUIPlanet>) new RenderPlanetUIEntity(null));
        RenderingRegistry.registerEntityRenderingHandler(EntityUIButton.class, (IRenderFactory<EntityUIButton>) new RenderButtonUIEntity(null));
        RenderingRegistry.registerEntityRenderingHandler(EntityUIStar.class, (IRenderFactory<EntityUIStar>) new RenderStarUIEntity(null));
        RenderingRegistry.registerEntityRenderingHandler(EntityElevatorCapsule.class, (IRenderFactory<EntityElevatorCapsule>) new RenderElevatorCapsule(null));
        RenderingRegistry.registerEntityRenderingHandler(EntityHoverCraft.class, (IRenderFactory<EntityHoverCraft>) new RenderHoverCraft(null));
    }

    @Override
    public void init() {

        //Colorizers
        CrystalColorizer colorizer = new CrystalColorizer();
        Minecraft.getMinecraft().getBlockColors().registerBlockColorHandler(colorizer, StellurgyBlocks.blockCrystal);
        Minecraft.getMinecraft().getItemColors().registerItemColorHandler(colorizer, Item.getItemFromBlock(StellurgyBlocks.blockCrystal));

        Minecraft.getMinecraft().getItemColors().registerItemColorHandler(new IItemColor() {
            public int colorMultiplier(@Nonnull ItemStack stack, int tintIndex) {
                return tintIndex > 0 ? -1 : ((ItemArmor) stack.getItem()).getColor(stack);
            }
        }, StellurgyItems.itemSpaceSuit_Boots, StellurgyItems.itemSpaceSuit_Chest, StellurgyItems.itemSpaceSuit_Helmet, StellurgyItems.itemSpaceSuit_Leggings);

        Stellurgy.materialRegistry.init();
    }

    @Override
    public void preInitBlocks() {
        //Register Block models
        Item blockItem = Item.getItemFromBlock(StellurgyBlocks.blockLoader);
        ModelLoader.setCustomModelResourceLocation(blockItem, 0, new ModelResourceLocation("stellurgy:databus", "inventory"));
        ModelLoader.setCustomModelResourceLocation(blockItem, 1, new ModelResourceLocation("stellurgy:satelliteHatch", "inventory"));
        ModelLoader.setCustomModelResourceLocation(blockItem, 2, new ModelResourceLocation("libvulpes:outputHatch", "inventory"));
        ModelLoader.setCustomModelResourceLocation(blockItem, 3, new ModelResourceLocation("libvulpes:inputHatch", "inventory"));
        ModelLoader.setCustomModelResourceLocation(blockItem, 4, new ModelResourceLocation("libvulpes:fluidOutputHatch", "inventory"));
        ModelLoader.setCustomModelResourceLocation(blockItem, 5, new ModelResourceLocation("libvulpes:fluidInputHatch", "inventory"));
        ModelLoader.setCustomModelResourceLocation(blockItem, 6, new ModelResourceLocation("stellurgy:guidancecomputeraccesshatch", "inventory"));

        blockItem = Item.getItemFromBlock(StellurgyBlocks.blockCrystal);
        for (int i = 0; i < BlockCrystal.numMetas; i++)
            ModelLoader.setCustomModelResourceLocation(blockItem, i, new ModelResourceLocation("stellurgy:crystal", "inventory"));

        registerFluidModel((IFluidBlock) StellurgyBlocks.blockOxygenFluid);
        registerFluidModel((IFluidBlock) StellurgyBlocks.blockNitrogenFluid);
        registerFluidModel((IFluidBlock) StellurgyBlocks.blockHydrogenFluid);
        registerFluidModel((IFluidBlock) StellurgyBlocks.blockFuelFluid);
        registerFluidModel((IFluidBlock) StellurgyBlocks.blockEnrichedLavaFluid);
    }

    @Override
    public void preInitItems() {
        //Register Item models
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSatellitePrimaryFunction, 0, new ModelResourceLocation("stellurgy:opticalSensor", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSatellitePrimaryFunction, 1, new ModelResourceLocation("stellurgy:compositionSensor", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSatellitePrimaryFunction, 2, new ModelResourceLocation("stellurgy:massDetector", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSatellitePrimaryFunction, 3, new ModelResourceLocation("stellurgy:microwaveTransmitter", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSatellitePrimaryFunction, 4, new ModelResourceLocation("stellurgy:oreMapper", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSatellitePrimaryFunction, 5, new ModelResourceLocation("stellurgy:biomeChangerSat", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSatellitePrimaryFunction, 6, new ModelResourceLocation("stellurgy:weatherControllerSat", "inventory"));

        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemIC, 0, new ModelResourceLocation("stellurgy:basicCircuit", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemIC, 1, new ModelResourceLocation("stellurgy:trackingCircuit", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemIC, 2, new ModelResourceLocation("stellurgy:advancedCircuit", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemIC, 3, new ModelResourceLocation("stellurgy:controlIOCircuit", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemIC, 4, new ModelResourceLocation("stellurgy:itemIOCircuit", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemIC, 5, new ModelResourceLocation("stellurgy:liquidIOCircuit", "inventory"));

        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemMisc, 0, new ModelResourceLocation("stellurgy:userInterface", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemMisc, 1, new ModelResourceLocation("stellurgy:miscpart1", "inventory"));

        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemUpgrade, 0, new ModelResourceLocation("stellurgy:hoverUpgrade", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemUpgrade, 1, new ModelResourceLocation("stellurgy:flightSpeedUpgrade", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemUpgrade, 2, new ModelResourceLocation("stellurgy:bionicLegs", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemUpgrade, 3, new ModelResourceLocation("stellurgy:landingBoots", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemUpgrade, 4, new ModelResourceLocation("stellurgy:antiFogVisor", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemUpgrade, 5, new ModelResourceLocation("stellurgy:earthbrightvisor", "inventory"));

        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSatellitePowerSource, 0, new ModelResourceLocation("stellurgy:basicSolarPanel", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSatellitePowerSource, 1, new ModelResourceLocation("stellurgy:advancedSolarPanel", "inventory"));

        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemLens, 0, new ModelResourceLocation("stellurgy:basicLens", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemBeaconFinder, 0, new ModelResourceLocation("stellurgy:beaconFinder", "inventory"));

        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemWafer, 0, new ModelResourceLocation("stellurgy:siliconWafer", "inventory"));

        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSpaceStation, 0, new ModelResourceLocation("stellurgy:spaceStation", "inventory"));


        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSpaceSuit_Chest, 0, new ModelResourceLocation("stellurgy:spaceChestplate", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSpaceSuit_Helmet, 0, new ModelResourceLocation("stellurgy:spaceHelmet", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSpaceSuit_Boots, 0, new ModelResourceLocation("stellurgy:spaceBoots", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSpaceSuit_Leggings, 0, new ModelResourceLocation("stellurgy:spaceLeggings", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemQuartzCrucible, 0, new ModelResourceLocation("stellurgy:iquartzCrucible", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemDataUnit, 0, new ModelResourceLocation("stellurgy:dataStorageUnit", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSatelliteIdChip, 0, new ModelResourceLocation("stellurgy:satelliteIdChip", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemPlanetIdChip, 0, new ModelResourceLocation("stellurgy:planetIdChip", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSpaceStationChip, 0, new ModelResourceLocation("stellurgy:stationidchip", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSpaceElevatorChip, 0, new ModelResourceLocation("stellurgy:elevatorChip", "inventory"));

        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSawBlade, 0, new ModelResourceLocation("stellurgy:sawBladeIron", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemOreScanner, 0, new ModelResourceLocation("stellurgy:oreScanner", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSatellite, 0, new ModelResourceLocation("stellurgy:satellite", "inventory"));

        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemCarbonScrubberCartridge, 0, new ModelResourceLocation("stellurgy:carbonCartridge", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSealDetector, 0, new ModelResourceLocation("stellurgy:sealDetector", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemJackhammer, 0, new ModelResourceLocation("stellurgy:jackHammer", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemAsteroidChip, 0, new ModelResourceLocation("stellurgy:asteroidChip", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemJetpack, 0, new ModelResourceLocation("stellurgy:jetPack", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemAtmAnalyser, 0, new ModelResourceLocation("stellurgy:atmAnalyser", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemBiomeChanger, 0, new ModelResourceLocation("stellurgy:biomeChanger", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemWeatherController, 0, new ModelResourceLocation("stellurgy:weatherController", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemSmallAirlockDoor, 0, new ModelResourceLocation("stellurgy:smallAirlockDoor", "inventory"));

        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemCircuitPlate, 0, new ModelResourceLocation("stellurgy:basicCircuitPlate", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemCircuitPlate, 1, new ModelResourceLocation("stellurgy:advancedCircuitPlate", "inventory"));

        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemPressureTank, 0, new ModelResourceLocation("stellurgy:pressureTank0", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemPressureTank, 1, new ModelResourceLocation("stellurgy:pressureTank1", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemPressureTank, 2, new ModelResourceLocation("stellurgy:pressureTank2", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemPressureTank, 3, new ModelResourceLocation("stellurgy:pressureTank3", "inventory"));

        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemBasicLaserGun, 0, new ModelResourceLocation("stellurgy:basicLaserGun", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemThermite, 0, new ModelResourceLocation("stellurgy:thermite", "inventory"));
        ModelLoader.setCustomModelResourceLocation(StellurgyItems.itemHovercraft, 0, new ModelResourceLocation("stellurgy:hoverCraft", "inventory"));
    }

    @Override
    public void preinit() {
        OBJLoader.INSTANCE.addDomain("stellurgy");
        registerRenderers();
        // Hand the aboard-movement resolution this side's look/input answers. Here, and not from a
        // static initialiser of the class that answers them: the port must exist before the first
        // aboard tick, and a port that appears when something happens to class-load its implementor
        // is absent exactly when nothing has needed it yet.
        dev.stannismod.stellurgy.integration.vs.ShipFrameTravel.installClientLookSource(
                new dev.stannismod.stellurgy.client.DeckLook.Port());
        bootstrapTestClientBridge();
    }

    /**
     * Test-only hook: when the client JVM is launched with
     * {@code -Dforge.test.client=true} (the reusable test framework's marker for
     * {@code RealClientHarness}-spawned clients), reflectively load and start
     * the framework's bridge so {@code ClientBot} can drive this client.
     *
     * <p>Inert in normal gameplay (flag absent &rarr; returns immediately). The
     * bridge class lives in the test-only framework jar and is NOT on the
     * production runtime classpath; the {@link ClassNotFoundException} branch
     * handles that gracefully.</p>
     */
    private static void bootstrapTestClientBridge() {
        if (!Boolean.getBoolean("forge.test.client")) {
            return;
        }
        try {
            Class<?> bridge = Class.forName("com.github.stannismod.forge.testing.client.bridge.ForgeTestClientBootstrap");
            bridge.getMethod("bootstrap").invoke(null);
        } catch (ClassNotFoundException ignored) {
            // Test framework jar absent at runtime — no-op (production launch).
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to bootstrap forge test client bridge", e);
        }
    }

    /** Latches once the test client has been muted (or once we've confirmed this is not a test
     *  client), so the per-tick check stops doing any work after the first successful pass. */
    private static boolean testClientSoundHandled = false;


    /**
     * The dimension id of the world the CLIENT is currently in, or {@link Integer#MIN_VALUE} when no
     * world is loaded. Published for client e2e tests (the harness's static-invoke surface passes int
     * args, hence the unused parameter) to observe that the client GENUINELY entered a dimension —
     * e.g. a space-subsystem slot dim synced by
     * {@link dev.stannismod.stellurgy.network.PacketSlotDimSync} — rather than that a server-side
     * transfer merely ran.
     */
    public static int currentClientDimension(int ignored) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        return mc.world == null ? Integer.MIN_VALUE : mc.world.provider.getDimension();
    }

    /**
     * Advance this client's copy of the space clock by one tick.
     *
     * <p>The space clock is the server's counter, and the server sends a baseline rather than a
     * value per tick; between baselines the client carries it forward itself. Deliberately NOT read
     * off any world's own total time: every dimension but the overworld has a clock that advances
     * only while it ticks, so a client that took its time from the world it happens to be standing
     * in would answer with a different quantity every time it changed dimension.</p>
     */
    @SubscribeEvent
    public static void advanceSpaceClock(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            dev.stannismod.stellurgy.space.SpaceClockSync.onClientTick();
        }
    }

    /**
     * Forget what this client learned from the server it is leaving — the space-clock baseline and
     * which dimension hyperspace was. The next server's counter and the next server's dim ids have
     * nothing to do with this one, and either value kept across the gap would let the client answer
     * confidently about a world it has left.
     */
    @SubscribeEvent
    public static void forgetServerState(
            net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        dev.stannismod.stellurgy.space.SpaceClockSync.reset();
        dev.stannismod.stellurgy.space.HyperspaceWorld.forgetServerId();
        ClientAtmosphere.reset();
    }

    /**
     * Silence a harness-spawned test client. Automated client e2e ({@code RealClientHarness})
     * boots a REAL client with REAL audio on the dev box, marked by {@code -Dforge.test.client=true}
     * (the same flag {@link #bootstrapTestClientBridge()} keys on); this mutes the master sound
     * level so those runs are quiet. Runs on the first client tick where the sound handler is up —
     * done on a tick rather than in {@code preinit} because {@code GameSettings.setSoundLevel}
     * pushes to the sound handler, which is not yet constructed that early. Inert in normal
     * gameplay and in a manual {@code runClient} playtest (flag absent &rarr; latches without muting),
     * so a human playtester still hears sound.
     */
    @SubscribeEvent
    public static void muteTestClientSound(TickEvent.ClientTickEvent event) {
        if (testClientSoundHandled || event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!Boolean.getBoolean("forge.test.client")) {
            testClientSoundHandled = true; // not a harness client — never mute, stop checking
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getSoundHandler() == null) {
            return; // sound not initialised yet — try again next tick
        }
        mc.gameSettings.setSoundLevel(SoundCategory.MASTER, 0.0F);
        testClientSoundHandled = true;
    }

    private void registerFluidModel(IFluidBlock fluidBlock) {
        Item item = Item.getItemFromBlock((Block) fluidBlock);

        final ModelResourceLocation modelResourceLocation = new ModelResourceLocation("stellurgy:fluid", fluidBlock.getFluid().getName());

        if (item != Items.AIR) {
            ModelLoader.registerItemVariants(item);
            ModelLoader.setCustomMeshDefinition(item, new FluidItemMeshDefinition(modelResourceLocation));
        }
        FluidStateMapper ignoreState = new FluidStateMapper(modelResourceLocation);
        ModelLoader.setCustomStateMapper((Block) fluidBlock, ignoreState);
        ModelBakery.registerItemVariants(item, modelResourceLocation);

    }

    @SubscribeEvent
    public void modelBakeEvent(ModelBakeEvent event) {
        IBakedModel bakedModel = event.getModelRegistry().getObject(ModelRocket.resource);
        if (bakedModel != null) {
            ModelRocket customModel = new ModelRocket();
            event.getModelRegistry().putObject(ModelRocket.resource, bakedModel);
        }
    }

    @Override
    public void registerEventHandlers() {
        super.registerEventHandlers();
        MinecraftForge.EVENT_BUS.register(ConnectionGalaxy.Events.class);
        MinecraftForge.EVENT_BUS.register(new RocketEventHandler());
        MinecraftForge.EVENT_BUS.register(new DelayedParticleRenderingEventHandler());
        MinecraftForge.EVENT_BUS.register(ModuleContainerPan.class);
        MinecraftForge.EVENT_BUS.register(new RenderComponents());
        // The client ticks its ships from the client tick event, not the world one, so the
        // re-seat that follows them needs its own handler on this side.
        MinecraftForge.EVENT_BUS.register(new ClientDeckFollowsItsShip());

        if (Loader.isModLoaded("jei")) {
            FMLCommonHandler.instance().bus().register(
                    new dev.stannismod.stellurgy.integration.jei.JeiClientTickHandler()
            );
        }
    }

    @Override
    public void fireFogBurst(ISpaceObject station) {
        try {
            PlanetEventHandler.runBurst(Minecraft.getMinecraft().world.getTotalWorldTime() + 20, 20);
        } catch (NullPointerException e) {
        }
    }

    @Override
    public void registerKeyBindings() {
        KeyBindings.init();
        MinecraftForge.EVENT_BUS.register(new KeyBindings());

    }

    @Override
    public Profiler getProfiler() {
        return Minecraft.getMinecraft().mcProfiler;
    }

    @Override
    public void changeClientPlayerWorld(World world) {
        Minecraft.getMinecraft().player.world = world;
    }

    @Override
    public void spawnDynamicRocketSmoke(World world, double x, double y,
                                        double z, double motionX, double motionY, double motionZ, int engineNum) {
        TrailFx fx = new TrailFx(world, x, y, z, motionX, motionY, motionZ);
        fx.register_additional_engines(engineNum);
        Minecraft.getMinecraft().effectRenderer.addEffect(fx);

    }
    @Override
    public void spawnDynamicRocketFlame(World world, double x, double y,
                                        double z, double motionX, double motionY, double motionZ, int engineNum) {

        RocketFx fx = new RocketFx(world, x, y, z, motionX, motionY, motionZ);
        fx.register_additional_engines(engineNum);
        Minecraft.getMinecraft().effectRenderer.addEffect(fx);

    }

    @Override
    public void spawnParticle(String particle, World world, double x, double y, double z, double motionX, double motionY, double motionZ) {
        switch (particle) {
            case "rocketFlame": {
                RocketFx fx = new RocketFx(world, x, y, z, motionX, motionY, motionZ);
                Minecraft.getMinecraft().effectRenderer.addEffect(fx);
                break;
            }
            case "smallRocketFlame": {
                RocketFx fx = new RocketFx(world, x, y, z, motionX, motionY, motionZ, 0.25f);
                Minecraft.getMinecraft().effectRenderer.addEffect(fx);
                break;
            }
            case "rocketSmoke": {
                TrailFx fx = new TrailFx(world, x, y, z, motionX, motionY, motionZ);
                Minecraft.getMinecraft().effectRenderer.addEffect(fx);
                break;
            }
            case "rocketSmokeInverse": {
                InverseTrailFx fx = new InverseTrailFx(world, x, y, z, motionX, motionY, motionZ);
                Minecraft.getMinecraft().effectRenderer.addEffect(fx);
                break;
            }
            case "arc": {
                FxElectricArc fx = new FxElectricArc(world, x, y, z, motionX);
                Minecraft.getMinecraft().effectRenderer.addEffect(fx);
                break;
            }
            case "smallLazer": {
                FxSkyLaser fx = new FxSkyLaser(world, x, y, z);
                Minecraft.getMinecraft().effectRenderer.addEffect(fx);
                break;
            }
            case "errorBox": {
                FxErrorBlock fx = new FxErrorBlock(world, x, y, z);
                Minecraft.getMinecraft().effectRenderer.addEffect(fx);
                break;
            }
            case "gravityEffect": {
                FxGravityEffect fx = new FxGravityEffect(world, x, y, z, motionX, motionY, motionZ);
                Minecraft.getMinecraft().effectRenderer.addEffect(fx);
                break;
            }
            default:
                world.spawnParticle(Objects.requireNonNull(EnumParticleTypes.getByName(particle)), x, y, z, motionX, motionY, motionZ);
                break;
        }
    }

    @Override
    public void spawnLaser(Entity entity, Vec3d toPos) {
        FxLaser fx = new FxLaser(entity.world, toPos.x, toPos.y, toPos.z, entity);
        Minecraft.getMinecraft().effectRenderer.addEffect(fx);

        FxLaserHeat fx2 = new FxLaserHeat(entity.world, toPos.x, toPos.y, toPos.z, 0.02f);
        Minecraft.getMinecraft().effectRenderer.addEffect(fx2);

        for (int i = 0; i < 4; i++) {
            FxLaserSpark fx3 = new FxLaserSpark(entity.world, toPos.x, toPos.y, toPos.z,
                    .125 - entity.world.rand.nextFloat() / 4f, .125 - entity.world.rand.nextFloat() / 4f, .125 - entity.world.rand.nextFloat() / 4f, .5f);
            Minecraft.getMinecraft().effectRenderer.addEffect(fx3);
        }
    }

    @Override
    public float calculateCelestialAngleSpaceStation() {
        Entity player = Minecraft.getMinecraft().player;
        try {
            return (float) SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(player.getPosition()).getRotation(EnumFacing.EAST);
        } catch (NullPointerException e) {

            /*While waiting for network packets various variables required to continue with rendering may be null,
             * it would be impractical to check them all
             * This is kinda hacky but I cannot find a better solution for the time being
             */
            return 0;
        }
    }

    @Override
    public long getWorldTimeUniversal(int id) {
        try {
            return Minecraft.getMinecraft().world.getTotalWorldTime();
        } catch (NullPointerException e) {
            return 0;
        }
    }

    @Override
    public void loadUILayout(Configuration config) {
        final String CLIENT = "Client";

        RocketEventHandler.suitPanel.setRawX(config.get(CLIENT, "suitPanelX", 8).getInt());
        RocketEventHandler.suitPanel.setRawY(config.get(CLIENT, "suitPanelY", 8).getInt());
        RocketEventHandler.suitPanel.setSizeModeX(config.get(CLIENT, "suitPanelModeX", -1).getInt());
        RocketEventHandler.suitPanel.setSizeModeY(config.get(CLIENT, "suitPanelModeY", -1).getInt());

        RocketEventHandler.oxygenBar.setRawX(config.get(CLIENT, "oxygenBarX", -8).getInt());
        RocketEventHandler.oxygenBar.setRawY(config.get(CLIENT, "oxygenBarY", 57).getInt());
        RocketEventHandler.oxygenBar.setSizeModeX(config.get(CLIENT, "oxygenBarModeX", 0).getInt());
        RocketEventHandler.oxygenBar.setSizeModeY(config.get(CLIENT, "oxygenBarModeY", 1).getInt());

        RocketEventHandler.hydrogenBar.setRawX(config.get(CLIENT, "hydrogenBarX", -8).getInt());
        RocketEventHandler.hydrogenBar.setRawY(config.get(CLIENT, "hydrogenBarY", 74).getInt());
        RocketEventHandler.hydrogenBar.setSizeModeX(config.get(CLIENT, "hydrogenBarModeX", 0).getInt());
        RocketEventHandler.hydrogenBar.setSizeModeY(config.get(CLIENT, "hydrogenBarModeY", 1).getInt());

        RocketEventHandler.atmBar.setRawX(config.get(CLIENT, "atmBarX", 8).getInt());
        RocketEventHandler.atmBar.setRawY(config.get(CLIENT, "atmBarY", 27).getInt());
        RocketEventHandler.atmBar.setSizeModeX(config.get(CLIENT, "atmBarModeX", -1).getInt());
        RocketEventHandler.atmBar.setSizeModeY(config.get(CLIENT, "atmBarModeY", 1).getInt());
    }

    @Override
    public void displayMessage(String msg, int time) {
        RocketEventHandler.setOverlay(Minecraft.getMinecraft().world.getTotalWorldTime() + time, msg);
    }

    public String getNameFromBiome(Biome biome) {
        return biome.getBiomeName();
    }

    /**
     * In single player the integrated server runs in this process, so the side is the CALLER's: its
     * threads are in Forge's server thread group, everything else here is the client.
     */
    @Override
    public dev.stannismod.stellurgy.dimension.DimensionManager getDimensionManager() {
        return isServerThread() ? super.getDimensionManager() : ConnectionGalaxy.current().dimensions;
    }

    @Override
    public dev.stannismod.stellurgy.stations.SpaceObjectManager getSpaceObjectManager() {
        return isServerThread() ? super.getSpaceObjectManager() : ConnectionGalaxy.current().spaceObjects;
    }

    @Override
    public dev.stannismod.stellurgy.dimension.DimensionManager connectionDimensions(net.minecraft.network.NetworkManager manager) {
        ConnectionGalaxy galaxy = ConnectionGalaxy.keptOn(manager);
        return galaxy == null ? null : galaxy.dimensions;
    }

    private static boolean isServerThread() {
        return FMLCommonHandler.instance().getEffectiveSide() == Side.SERVER;
    }

    private static class FluidStateMapper extends StateMapperBase {
        private final ModelResourceLocation location;

        public FluidStateMapper(ModelResourceLocation fluidLocation) {
            this.location = fluidLocation;
        }

        @Override
        protected ModelResourceLocation getModelResourceLocation(@Nullable IBlockState iBlockState) {
            return location;
        }
    }


    @Override
    public ModuleBase createScrollListPan(
            int baseX, int baseY,
            List<ModuleBase> list,
            int sizeX, int sizeY
    ) {
        return new ModuleContainerPanYOnlyWithScrollCache(
                baseX, baseY,
                list, new LinkedList<>(),
                null,
                sizeX - 2, sizeY,
                0, -48,
                0, 72
        );
    }

    @Override
    public void clearScrollCache() {
        ModuleContainerPanYOnlyWithScrollCache.clearScrollCache();
    }

    @Override
    public ModuleBase createObservatoryAsteroidListPan(int baseX, int baseY, List<ModuleBase> list2, int sizeX, int sizeY) {
        return createScrollListPan(baseX, baseY, list2, sizeX, sizeY);
    }

    @Override
    public void clearObservatoryScrollCache() {
        clearScrollCache();
    }

    private static class FluidItemMeshDefinition implements ItemMeshDefinition {
        private final ModelResourceLocation location;

        public FluidItemMeshDefinition(ModelResourceLocation fluidLocation) {
            this.location = fluidLocation;
        }

        @Override
        public ModelResourceLocation getModelLocation(@Nonnull ItemStack stack) {
            return location;
        }
    }

    // atmosphere detector

    @Override
    public ModuleBase createAtmosphereDetectorButton(int offsetX, int offsetY, int buttonId, IAtmosphere atmosphere, String text, TileAtmosphereDetector detector, ResourceLocation[] buttonImages) {
        return new ModuleSelectableAtmosphereButton(offsetX, offsetY, buttonId, atmosphere, text, detector, buttonImages);
    }

    @Override
    public void sendClientStatusMessage(String translationKey, Object... args) {
        if (Minecraft.getMinecraft().player != null) {
            Minecraft.getMinecraft().player.sendStatusMessage(
                    new TextComponentTranslation(translationKey, args),
                    true
            );
        }
    }
}
