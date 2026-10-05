package dev.stannismod.stellurgy;

import dev.stannismod.stellurgy.atmosphere.AtmosphereHandler;
import dev.stannismod.stellurgy.satellite.SatelliteOptical;
import dev.stannismod.stellurgy.satellite.SatelliteWeatherController;
import dev.stannismod.stellurgy.api.atmosphere.IAtmosphereSealHandler;
import dev.stannismod.stellurgy.api.ISpaceObjectManager;
import dev.stannismod.stellurgy.api.dimension.solar.IGalaxy;
import dev.stannismod.stellurgy.api.IGravityManager;
import net.minecraft.block.Block;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.block.material.MaterialLiquid;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.Item.ToolMaterial;
import net.minecraft.item.ItemArmor.ArmorMaterial;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemDoor;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.Mod.Instance;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.*;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.OreDictionary.OreRegisterEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import dev.stannismod.stellurgy.stellurgy.Tags;
import dev.stannismod.stellurgy.advancements.StellurgyAdvancements;
import dev.stannismod.stellurgy.api.capability.CapabilitySpaceArmor;
import dev.stannismod.stellurgy.api.satellite.SatelliteProperties;
import dev.stannismod.stellurgy.api.stations.ISpaceObject;
import dev.stannismod.stellurgy.armor.ItemSpaceArmor;
import dev.stannismod.stellurgy.armor.ItemSpaceChest;
import dev.stannismod.stellurgy.block.inventory.BlockInvHatch;
import dev.stannismod.stellurgy.block.multiblock.BlockStellurgyHatch;
import dev.stannismod.stellurgy.block.multiblock.BlockDataBusBig;
import dev.stannismod.stellurgy.block.plant.BlockLightwoodLeaves;
import dev.stannismod.stellurgy.block.plant.BlockLightwoodPlanks;
import dev.stannismod.stellurgy.block.plant.BlockLightwoodSapling;
import dev.stannismod.stellurgy.block.plant.BlockLightwoodWood;
import dev.stannismod.stellurgy.capability.CapabilityProtectiveArmor;
import dev.stannismod.stellurgy.command.StellurgyCommandRoot;
import dev.stannismod.stellurgy.command.test.TestProbeCommandRegistration;
import dev.stannismod.stellurgy.common.CommonProxy;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.dimension.DimensionProperties.AtmosphereTypes;
import dev.stannismod.stellurgy.dimension.DimensionProperties.Temps;
import dev.stannismod.stellurgy.enchant.EnchantmentSpaceBreathing;
import dev.stannismod.stellurgy.integration.CompatibilityMgr;
import dev.stannismod.stellurgy.integration.GalacticCraftHandler;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;
import dev.stannismod.stellurgy.affs.AdvancedForceFieldSystem;
import dev.stannismod.stellurgy.integration.theoneprobe.TopIntegration;
import dev.stannismod.stellurgy.item.components.ItemJetpack;
import dev.stannismod.stellurgy.item.components.ItemPressureTank;
import dev.stannismod.stellurgy.item.components.ItemUpgrade;
import dev.stannismod.stellurgy.item.tools.ItemBasicLaserGun;
import dev.stannismod.stellurgy.mission.MissionGasCollection;
import dev.stannismod.stellurgy.mission.MissionOreMining;
import dev.stannismod.stellurgy.stations.SpaceObjectManager;
import dev.stannismod.stellurgy.stations.SpaceStationObject;
import dev.stannismod.stellurgy.tile.TileWirelessTransceiver;
import dev.stannismod.stellurgy.tile.hatch.TileDataBus;
import dev.stannismod.stellurgy.tile.hatch.TileDataBusBig;
import dev.stannismod.stellurgy.tile.hatch.TileInvHatch;
import dev.stannismod.stellurgy.tile.hatch.TileSatelliteHatch;
import dev.stannismod.stellurgy.tile.multiblock.energy.TileBlackHoleGenerator;
import dev.stannismod.stellurgy.tile.multiblock.energy.TileMicrowaveReciever;
import dev.stannismod.stellurgy.tile.multiblock.energy.TileSolarArray;
import dev.stannismod.stellurgy.tile.multiblock.orbitallaserdrill.TileOrbitalLaserDrill;
import dev.stannismod.stellurgy.tile.satellite.TileSatelliteBuilder;
import dev.stannismod.stellurgy.tile.satellite.TileSatelliteTerminal;
import dev.stannismod.stellurgy.tile.satellite.TileTerraformingTerminal;
import dev.stannismod.stellurgy.world.decoration.MapGenLander;
import dev.stannismod.stellurgy.world.ore.OreGenerator;
import dev.stannismod.stellurgy.world.type.WorldTypePlanetGen;
import dev.stannismod.stellurgy.world.type.WorldTypeSpace;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.api.LibVulpesBlocks;
import dev.stannismod.stellurgy.libvulpes.api.LibVulpesItems;
import dev.stannismod.stellurgy.libvulpes.api.material.AllowedProducts;
import dev.stannismod.stellurgy.libvulpes.api.material.MaterialRegistry;
import dev.stannismod.stellurgy.libvulpes.api.material.MixedMaterial;
import dev.stannismod.stellurgy.libvulpes.block.*;
import dev.stannismod.stellurgy.libvulpes.block.multiblock.BlockMultiBlockComponentVisible;
import dev.stannismod.stellurgy.libvulpes.block.multiblock.BlockMultiBlockComponentVisibleAlphaTexture;
import dev.stannismod.stellurgy.libvulpes.block.multiblock.BlockMultiblockMachine;
import dev.stannismod.stellurgy.libvulpes.inventory.GuiHandler;
import dev.stannismod.stellurgy.libvulpes.items.ItemBlockMeta;
import dev.stannismod.stellurgy.libvulpes.items.ItemIngredient;
import dev.stannismod.stellurgy.libvulpes.items.ItemProjector;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.recipe.RecipesMachine;
import dev.stannismod.stellurgy.libvulpes.tile.TileMaterial;
import dev.stannismod.stellurgy.libvulpes.tile.energy.TilePlugBase;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiBlock;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.hatch.TileFluidHatch;
import dev.stannismod.stellurgy.libvulpes.util.FluidUtils;
import dev.stannismod.stellurgy.libvulpes.util.HashedBlockPosition;
import dev.stannismod.stellurgy.libvulpes.util.InputSyncHandler;
import dev.stannismod.stellurgy.libvulpes.util.SingleEntry;

import javax.annotation.Nonnull;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.Map.Entry;
import dev.stannismod.stellurgy.api.*;
import dev.stannismod.stellurgy.block.*;
import dev.stannismod.stellurgy.entity.*;
import dev.stannismod.stellurgy.event.*;
import dev.stannismod.stellurgy.item.*;
import dev.stannismod.stellurgy.network.*;
import dev.stannismod.stellurgy.satellite.*;
import dev.stannismod.stellurgy.tile.*;
import dev.stannismod.stellurgy.tile.atmosphere.*;
import dev.stannismod.stellurgy.tile.infrastructure.*;
import dev.stannismod.stellurgy.tile.multiblock.*;
import dev.stannismod.stellurgy.tile.multiblock.machine.*;
import dev.stannismod.stellurgy.tile.station.*;
import dev.stannismod.stellurgy.util.*;
import dev.stannismod.stellurgy.world.biome.*;


@Mod(modid = Tags.MOD_ID, name = Tags.MOD_NAME, version = Tags.VERSION, dependencies = Constants.DEPENDENCIES)
public class Stellurgy {

    /**
     * How much absorbed energy a mirror's metal film sheds before it melts. Shared by every tier: a
     * film is a film, and what separates aluminium from gold is how much of a hit it lets into that
     * film rather than how much the film can take.
     */
    private static final int MIRROR_FILM_DISSIPATION = 4000;
    /**
     * How much of an impact one PLATE of reactive armour swallows; a full block takes twice. Set so
     * that ordinary fire is eaten whole and a railgun-class round is not — which is the ordering the
     * mechanic exists to produce, not a number anybody should read as sacred.
     */
    private static final int REACTIVE_PLATE_CAPACITY = 10000;

    private static final String PLANET = "Planet";
    /** Effectively final, process lifetime: built once at class initialisation. */
    public static final Logger logger = LogManager.getLogger(Constants.modId);
    @SidedProxy(clientSide = "dev.stannismod.stellurgy.client.ClientProxy", serverSide = "dev.stannismod.stellurgy.common.CommonProxy")
    public static CommonProxy proxy;
    @Instance(value = Constants.modId)
    public static Stellurgy instance;

    // ---- What the mod holds for the whole process ---------------------------------------------
    //
    // Fields of THIS object, not statics of the class: Forge builds the mod object once and calls
    // every lifecycle hook on it, so the state those hooks build belongs on it, reached through
    // `instance`.

    /** The machine recipe tables. Filled by the mod in preInit / recipe registration / postInit and
     *  read for the life of the side; rewritten only by the operator's /reloadrecipes, a partial
     *  re-initialisation of the mod and the sanctioned exception to being written once.
     *  Effectively final, process lifetime: built with the mod object. */
    public final RecipeHandler machineRecipes = new RecipeHandler();
    /** Effectively final, process lifetime: built with the mod object (a creative tab registers itself
     *  in vanilla's tab array when it is constructed). */
    private final CreativeTabs tabAdvRocketry = new CreativeTabs("stellurgy") {
        @Override
        @Nonnull
        public ItemStack getTabIconItem() {
            return new ItemStack(StellurgyItems.itemSatelliteIdChip);
        }
    };
    /** Effectively final, process lifetime: written only by {@link #preInit}. */
    public String version;

    /** The vendored libVulpes, folded into this mod: built with the mod object, driven from its
     *  lifecycle hooks. Effectively final, process lifetime. */
    public final dev.stannismod.stellurgy.libvulpes.LibVulpes libVulpes =
            new dev.stannismod.stellurgy.libvulpes.LibVulpes();
    /** The vendored force-field system, folded into this mod: built with the mod object, driven from
     *  its lifecycle hooks. Effectively final, process lifetime. */
    public final dev.stannismod.stellurgy.affs.AdvancedForceFieldSystem affs =
            new dev.stannismod.stellurgy.affs.AdvancedForceFieldSystem();
    /** The vendored Valkyrien Skies host object: built with the mod object, driven from its lifecycle
     *  hooks. Effectively final, process lifetime. */
    public final org.valkyrienskies.mod.common.ValkyrienSkiesMod valkyrienSkies =
            new org.valkyrienskies.mod.common.ValkyrienSkiesMod();

    // ---- The four API services, owned here ---------------------------------------------------
    //
    // They used to be public mutable statics on StellurgyAPI, assigned from wherever each
    // service happened to be constructed. That is a mutable static holding a COLLABORATOR, and it
    // had already produced the defect it always produces: `gravityManager` was written from TWO
    // places, one of them a static initialiser on GravityHandler that fires when the class loads —
    // which is what `new GravityHandler()` at the other site does. Two handlers were built, the
    // first published and then immediately replaced, and anything that read the field in between
    // held the orphan.
    //
    // The owner is this mod object: its singleton-ness is guaranteed by Forge's @Instance rather
    // than by convention, which is the property that makes it an owner at all. Each keeps the lifecycle
    // point it already had — moving init order is a separate change with separate risk — but now
    // has ONE writer, and a second install is a loud error instead of a silent overwrite.

    /** Effectively final, process lifetime: written only by Stellurgy.installSealHandler. */
    private IAtmosphereSealHandler apiSealHandler;
    /**
     * Effectively final, server lifetime: written only by Stellurgy.attachServerServices,
     * Stellurgy.detachServerServices at server start, released at server stop.
     */
    private ISpaceObjectManager apiSpaceObjects;
    /**
     * Effectively final, server lifetime: written only by Stellurgy.attachServerServices,
     * Stellurgy.detachServerServices at server start, released at server stop.
     */
    private IGalaxy apiGalaxy;
    /** Effectively final, process lifetime: written only by Stellurgy.installGravityManager. */
    private IGravityManager apiGravity;

    private static <T> T installOnce(T current, T next, String what) {
        if (next == null) {
            throw new IllegalArgumentException(what + " must not be null");
        }
        if (current != null) {
            throw new IllegalStateException(what + " is already installed ("
                    + current.getClass().getName() + "); a second install is a lifecycle bug");
        }
        return next;
    }

    /** @see StellurgyAPI#atmosphereSealHandler() */
    public void installSealHandler(IAtmosphereSealHandler handler) {
        apiSealHandler = installOnce(apiSealHandler, handler, "the atmosphere seal handler");
    }

    /**
    * The two services above belong to the JVM. These two belong to the SERVER, and the difference is
    * in their names because it is a difference in lifetime, not in style.
    *
    * <p>Both are the running server's own objects ({@link #serverDimensions()},
    * {@link #serverSpaceObjects()}), so the reference this mod object publishes is attached when a
    * server starts and RELEASED when it stops, exactly as {@code spaceSubsystem} beside it is: an API
    * caller between servers is told there is no galaxy.</p>
    */
    public void attachServerServices(ISpaceObjectManager manager, IGalaxy galaxy) {
        apiSpaceObjects = installOnce(apiSpaceObjects, manager, "the space object manager");
        apiGalaxy = installOnce(apiGalaxy, galaxy, "the galaxy");
    }

    /** Released by the owner: these belonged to the server that has just stopped. */
    public void detachServerServices() {
        apiSpaceObjects = null;
        apiGalaxy = null;
    }

    /** @see StellurgyAPI#gravityManager() */
    public void installGravityManager(IGravityManager manager) {
        apiGravity = installOnce(apiGravity, manager, "the gravity manager");
    }

    public IAtmosphereSealHandler sealHandler() {
        return apiSealHandler;
    }

    public ISpaceObjectManager spaceObjects() {
        return apiSpaceObjects;
    }

    public IGalaxy galaxy() {
        return apiGalaxy;
    }

    public IGravityManager gravity() {
        return apiGravity;
    }
    /** Effectively final, process lifetime: written only by {@link #load}. */
    public WorldType planetWorldType;
    /** Effectively final, process lifetime: written only by {@link #load}. */
    public WorldType spaceWorldType;
    /** Effectively final, process lifetime: built with the mod object; filled at registration. */
    public final MaterialRegistry materialRegistry = new MaterialRegistry(Constants.modId);
    /** Products other mods may have auto-generated recipes for, accumulated from registry events
     *  during load and consumed once by {@code createAutoGennedRecipes} at init - FML fires those
     *  events once per launch. Effectively final, process lifetime: filled only by {@link #registerOre}. */
    private final HashMap<AllowedProducts, HashSet<String>> modProducts = new HashMap<>();
    /** The mod's config file. Its contents are edited at run time only by the operator's /addtorch and
     *  /addsealant, a partial re-initialisation of the mod and the sanctioned exception to being
     *  written once. Effectively final, process lifetime: written only by {@link #preInit}. */
    private Configuration config;

    /**
     * This server's space subsystem, or {@code null} when it has none (before server start, on a
     * remote client, or when the subsystem stood down — the config flag off, or Valkyrien Skies
     * absent).
     *
     * <p><b>The mod owns it, and that is the whole point of the field being here.</b>
     * {@link dev.stannismod.stellurgy.space.SpaceSubsystem} has a public constructor, so it is not
     * a singleton and cannot hold a meaningful "current" one of itself — it used to, together with an
     * attach/detach pair and six static per-service accessors, and its own start hook did
     * {@code attach(new SpaceSubsystem(...))}: the class built itself and assigned itself to its own
     * static field. Two instances could then be alive at once and which one a caller reached depended
     * on which accessor it happened to use, with no way to ask whose subsystem it had.</p>
     *
     * <p>Written by the four server-lifecycle handlers in this class and by nothing else — there is
     * no setter and no swap seam, so nothing can leave a running server without its subsystem. A test
     * that wants an isolated stack builds its own {@code SpaceSubsystem} and ticks it itself; it
     * cannot pass it off as the server's.</p>
     *
     * Effectively final, server lifetime: written only by Stellurgy.serverStarting, Stellurgy.serverStopped
     * at server start, released at server stop.
     */
    private dev.stannismod.stellurgy.space.SpaceSubsystem spaceSubsystem;

    /**
     * The space subsystem this server is running, or {@code null} when it has none. THE one route to
     * it: callers read the services they need off the returned object ({@code .ledger},
     * {@code .manager}, {@code .transit}, …) in a single read, so a caller needing two of them can
     * never end up holding one from each of two stacks.
     */
    public static dev.stannismod.stellurgy.space.SpaceSubsystem spaceSubsystem() {
        return instance == null ? null : instance.spaceSubsystem;
    }

    /**
     * What the running server owns ({@link ServerState}). Static by transitivity (a field of the mod
     * object); effectively final, SERVER lifetime, approved by the maintainer 2026-10-01: built by
     * {@link #beginServerLifetime()} when a server is about to start and released by
     * {@link #endServerLifetime()} when it has stopped. Nothing in it is cleared for reuse — the next
     * server builds its own.
     */
    private ServerState server;

    /**
     * What the running server owns.
     *
     * @throws IllegalStateException when no server is running
     */
    public static ServerState serverState() {
        ServerState state = instance == null ? null : instance.server;
        if (state == null) {
            throw new IllegalStateException("No server is running: there is no server state");
        }
        return state;
    }

    /**
     * The weight table every rocket, satellite and stored structure is weighed by, over
     * {@code config/advRocketry/weights.json}. Static by transitivity (a field of the mod object);
     * effectively final, lifetime the PROCESS (the client or the dedicated server): written once by
     * {@link #postInit} and only read after — a second write throws. Approved by the maintainer
     * 2026-10-02.
     */
    private WeightEngine weights;

    /**
     * The weight table this process weighs by.
     *
     * @throws IllegalStateException before post-init has built it
     */
    public static WeightEngine weights() {
        WeightEngine table = instance == null ? null : instance.weights;
        if (table == null) {
            throw new IllegalStateException("the weight table is built in post-init and is not built yet");
        }
        return table;
    }

    /** The running server's galaxy. @throws IllegalStateException when no server is running */
    public static DimensionManager serverDimensions() {
        return serverState().dimensions;
    }

    /** The running server's stations. @throws IllegalStateException when no server is running */
    public static SpaceObjectManager serverSpaceObjects() {
        return serverState().spaceObjects;
    }

    /**
     * The running server's subsystem networks, or {@code null} when no server is running in this JVM —
     * null rather than a throw, because a client JVM attached to a remote server asks too and must
     * read "none". @see dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkManager#of
     */
    public static dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkManager subsystemNetworks() {
        ServerState state = instance == null ? null : instance.server;
        return state == null ? null : state.subsystemNetworks;
    }

    /**
     * Builds the state whose lifetime is one server. The server-start hook calls it; so does the
     * headless test bootstrap, which runs no server and arranges the server's state the same way.
     */
    public void beginServerLifetime() {
        if (server != null) {
            throw new IllegalStateException("A server lifetime is already open; a second begin is a lifecycle bug");
        }
        server = new ServerState(dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig().minDimension);
    }

    /** Releases the state {@link #beginServerLifetime()} built, undoing its Forge dimension registrations. */
    public void endServerLifetime() {
        if (server != null) {
            server.release();
        }
        server = null;
    }

    /**
     * Returns a player to the plain world — see {@link dev.stannismod.stellurgy.player.PlayerRelease}.
     *
     * <p><b>Lifetime: the MOD's, and stated because it differs from {@code spaceSubsystem} above.</b>
     * That one is attached when a server starts and released when it stops, because its state is the
     * running server's. This object holds NO per-player state of its own — only references to the
     * binding owners and the order in which to ask them — and those owners are themselves built once
     * at mod init and left on the bus. So its lifetime is theirs; giving it a shorter one would say
     * something untrue about what it holds.</p>
     *
     * <p>That the owners' own state is the SERVER's while their objects are the mod's is a real,
     * pre-existing defect. This class neither worsens nor fixes it.</p>
     *
     * Effectively final, process lifetime: written only by Stellurgy.postInit.
     */
    private dev.stannismod.stellurgy.player.PlayerRelease playerRelease;

    /** The release service, or {@code null} before mod init has built it. */
    public static dev.stannismod.stellurgy.player.PlayerRelease playerRelease() {
        return instance == null ? null : instance.playerRelease;
    }

    static {
        FluidRegistry.enableUniversalBucket(); // Must be called before preInit
    }

    //CONFIG-stuff here to make sure we load early enough
    /** Effectively final, process lifetime: written only by Stellurgy.preInit. */
    private boolean resetFromXml;

    //Biome registry.
    @SubscribeEvent
    public void register(RegistryEvent.Register<Biome> evt) {
        System.out.println("REGISTERING BIOMES");
        //Biome properties
        StellurgyBiomes.moonBiome = new BiomeGenMoon(new Biome.BiomeProperties("Regolith Highlands").setRainDisabled().setBaseHeight(1f).setHeightVariation(0.2f).setRainfall(0).setTemperature(0.3f));
        StellurgyBiomes.alienForest = new BiomeGenAlienForest(new Biome.BiomeProperties("Alien Forest").setWaterColor(0x8888FF));
        StellurgyBiomes.hotDryBiome = new BiomeGenHotDryRock(new Biome.BiomeProperties("Ferric Regolith Wasteland").setRainDisabled().setBaseHeight(1f).setHeightVariation(0.01f).setRainfall(0).setTemperature(0.9f));
        StellurgyBiomes.spaceBiome = new BiomeGenSpace(new Biome.BiomeProperties("Space").setRainDisabled().setBaseHeight(-2f).setHeightVariation(0f).setTemperature(1f));
        StellurgyBiomes.stormLandsBiome = new BiomeGenStormland(new Biome.BiomeProperties("Stormland").setBaseHeight(1f).setHeightVariation(0.1f).setRainfall(0.9f).setTemperature(0.9f));
        StellurgyBiomes.crystalChasms = new BiomeGenCrystal(new Biome.BiomeProperties("Crystal Chasms").setHeightVariation(0.1f).setBaseHeight(1f).setRainfall(0.2f).setTemperature(0.1f));
        StellurgyBiomes.swampDeepBiome = new BiomeGenDeepSwamp(new Biome.BiomeProperties("Deep Swamp").setBaseHeight(-0.1f).setHeightVariation(0.2f).setRainfall(0.9f).setTemperature(0.9f).setWaterColor(14745518));
        StellurgyBiomes.marsh = new BiomeGenMarsh(new Biome.BiomeProperties("Marsh").setBaseHeight(-0.4f).setHeightVariation(0f));
        StellurgyBiomes.oceanSpires = new BiomeGenOceanSpires(new Biome.BiomeProperties("Ocean Spires").setBaseHeight(-0.5f).setHeightVariation(0f));
        StellurgyBiomes.moonBiomeDark = new BiomeGenMoonDark(new Biome.BiomeProperties("Regolith Lowlands").setRainDisabled().setBaseHeight(0.5f).setHeightVariation(0.01f).setRainfall(0).setTemperature(0.3f));
        StellurgyBiomes.volcanic = new BiomeGenVolcanic(new Biome.BiomeProperties("Volcanic").setRainDisabled().setBaseHeight(0f).setHeightVariation(0.9f).setRainfall(0).setTemperature(1.0f));
        StellurgyBiomes.volcanicBarren = new BiomeGenBarrenVolcanic(new Biome.BiomeProperties("Volcanic Lowlands").setRainDisabled().setBaseHeight(0f).setHeightVariation(0.9f).setRainfall(0).setTemperature(1.0f));

        //Biome registry names outside of constructor
        StellurgyBiomes.moonBiome.setRegistryName(Constants.modId, "moon");
        StellurgyBiomes.alienForest.setRegistryName(Constants.modId, "alien_forest");
        StellurgyBiomes.hotDryBiome.setRegistryName(Constants.modId, "hotdryrock");
        StellurgyBiomes.spaceBiome.setRegistryName(Constants.modId, "space");
        StellurgyBiomes.stormLandsBiome.setRegistryName(Constants.modId, "stormland");
        StellurgyBiomes.crystalChasms.setRegistryName(Constants.modId, "crystalchasms");
        StellurgyBiomes.swampDeepBiome.setRegistryName(Constants.modId, "deepswamp");
        StellurgyBiomes.marsh.setRegistryName(Constants.modId, "marsh");
        StellurgyBiomes.oceanSpires.setRegistryName(Constants.modId, "oceanspires");
        StellurgyBiomes.moonBiomeDark.setRegistryName(Constants.modId, "moondark");
        StellurgyBiomes.volcanic.setRegistryName(Constants.modId, "volcanic");
        StellurgyBiomes.volcanicBarren.setRegistryName(Constants.modId, "volcanicbarren");

        //Actual registry
        StellurgyBiomes.instance.registerBiome(StellurgyBiomes.moonBiome, evt.getRegistry());
        StellurgyBiomes.instance.registerBiome(StellurgyBiomes.alienForest, evt.getRegistry());
        StellurgyBiomes.instance.registerBiome(StellurgyBiomes.hotDryBiome, evt.getRegistry());
        StellurgyBiomes.instance.registerBiome(StellurgyBiomes.spaceBiome, evt.getRegistry());
        StellurgyBiomes.instance.registerBiome(StellurgyBiomes.stormLandsBiome, evt.getRegistry());
        StellurgyBiomes.instance.registerBiome(StellurgyBiomes.crystalChasms, evt.getRegistry());
        StellurgyBiomes.instance.registerBiome(StellurgyBiomes.swampDeepBiome, evt.getRegistry());
        StellurgyBiomes.instance.registerBiome(StellurgyBiomes.marsh, evt.getRegistry());
        StellurgyBiomes.instance.registerBiome(StellurgyBiomes.oceanSpires, evt.getRegistry());
        StellurgyBiomes.instance.registerBiome(StellurgyBiomes.moonBiomeDark, evt.getRegistry());
        StellurgyBiomes.instance.registerBiome(StellurgyBiomes.volcanic, evt.getRegistry());
        StellurgyBiomes.instance.registerBiome(StellurgyBiomes.volcanicBarren, evt.getRegistry());

        BiomeDictionary.addTypes(StellurgyBiomes.moonBiome,
                BiomeDictionary.Type.WASTELAND,
                BiomeDictionary.Type.DRY,
                BiomeDictionary.Type.COLD
        );
        BiomeDictionary.addTypes(StellurgyBiomes.moonBiomeDark,
                BiomeDictionary.Type.WASTELAND,
                BiomeDictionary.Type.DRY,
                BiomeDictionary.Type.COLD
        );
        BiomeDictionary.addTypes(StellurgyBiomes.alienForest,
                BiomeDictionary.Type.MAGICAL,
                BiomeDictionary.Type.FOREST
        );
        BiomeDictionary.addTypes(StellurgyBiomes.hotDryBiome,
                BiomeDictionary.Type.WASTELAND,
                BiomeDictionary.Type.DRY,
                BiomeDictionary.Type.HOT
        );
        BiomeDictionary.addTypes(StellurgyBiomes.volcanic,
                BiomeDictionary.Type.WASTELAND,
                BiomeDictionary.Type.DRY,
                BiomeDictionary.Type.HOT,
                BiomeDictionary.Type.MOUNTAIN
        );
        BiomeDictionary.addTypes(StellurgyBiomes.volcanicBarren,
                BiomeDictionary.Type.WASTELAND,
                BiomeDictionary.Type.DRY,
                BiomeDictionary.Type.HOT,
                BiomeDictionary.Type.MOUNTAIN
        );
        BiomeDictionary.addTypes(StellurgyBiomes.spaceBiome, BiomeDictionary.Type.VOID);
        BiomeDictionary.addTypes(StellurgyBiomes.stormLandsBiome,
                BiomeDictionary.Type.WASTELAND,
                BiomeDictionary.Type.WET,
                BiomeDictionary.Type.HOT
        );

        BiomeDictionary.addTypes(StellurgyBiomes.swampDeepBiome,
                BiomeDictionary.Type.WET,
                BiomeDictionary.Type.HOT
        );
        BiomeDictionary.addTypes(StellurgyBiomes.marsh,
                BiomeDictionary.Type.WET,
                BiomeDictionary.Type.HOT
        );
        BiomeDictionary.addTypes(StellurgyBiomes.oceanSpires,
                BiomeDictionary.Type.OCEAN
        );
        BiomeDictionary.addTypes(StellurgyBiomes.crystalChasms,
                BiomeDictionary.Type.SNOWY,
                BiomeDictionary.Type.WASTELAND,
                BiomeDictionary.Type.COLD
        );
    }

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // libVulpes was a separate mod and is folded into this container. It goes FIRST: everything below
        // builds on the products, materials and packet discriminators it registers, and it used to
        // be a separate mod that FML initialised before this one.
        libVulpes.preInit(event);

        version = event.getModMetadata().version;

        dev.stannismod.stellurgy.atmosphere.AtmosphereHandler.createDamageSources();
        dev.stannismod.stellurgy.world.WorldRuntime.register();
        // Forge cannot withdraw a DimensionType, so the two space types are registered once, here, on
        // both sides; the dimension IDS that use them are each server's own.
        dev.stannismod.stellurgy.space.SpaceSlotPool.registerType();
        dev.stannismod.stellurgy.space.HyperspaceWorld.registerType();
        MinecraftForge.EVENT_BUS.register(dev.stannismod.stellurgy.world.WorldRuntime.Attach.class);

        //Init API
        instance.installSealHandler(SealableBlockHandler.INSTANCE);
        SealableBlockHandler.INSTANCE.loadDefaultData();

        // Integrations
        // The One Probe integration
        TopIntegration.register();

        //Configuration  ---------------------------------------------------------------------------------------------

        config = new Configuration(new File(event.getModConfigurationDirectory(), "/" + dev.stannismod.stellurgy.api.StellurgyConfiguration.configFolder + "/stellurgy.cfg"));
        dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig().config = config;
        config.load();

        StellurgyConfiguration.loadPreInit();

        resetFromXml = config.getBoolean(
                "resetPlanetsFromXML",
                PLANET,
                false,
                "Reload planet definitions from config XML on this restart."
        );

        boolean resetOnlyOnce = config.getBoolean(
                "ResetOnlyOnce",
                PLANET,
                true,
                "Setting this to false will prevent resetPlanetsFromXML from being set to false upon world reload. Recommended for pack developers who want all saves to always use planetDefs XML from the config folder."
        );

        if (resetOnlyOnce && resetFromXml) {
            config.get("Planet", "resetPlanetsFromXML", false).set(false);
        }
        config.save();

        //Register cap events
        MinecraftForge.EVENT_BUS.register(new CapabilityProtectiveArmor());
        // Attaches the player-bindings capability, and carries it across a death — Forge copies no
        // capability on respawn, and without this a player who dies aboard his ship loses the only
        // record of which ship it was.
        MinecraftForge.EVENT_BUS.register(
                new dev.stannismod.stellurgy.player.CapabilityPlayerBindings());

        //Register Packets - the discriminator space is declared in PacketRegistry, which owns the
        //wire order; a packet is added by appending it there, never by a call from here.
        PacketRegistry.registerAll();

        //if(dev.stannismod.stellurgy.api.Configuration.allowMakingItemsForOtherMods)
        MinecraftForge.EVENT_BUS.register(this);

        //Satellites ---------------------------------------------------------------------------------------------
        SatelliteRegistry.registerSatellite("optical", SatelliteOptical.class);
        SatelliteRegistry.registerSatellite("density", SatelliteDensity.class);
        SatelliteRegistry.registerSatellite("composition", SatelliteComposition.class);
        SatelliteRegistry.registerSatellite("mass", SatelliteMassScanner.class);
        SatelliteRegistry.registerSatellite("asteroidMiner", MissionOreMining.class);
        SatelliteRegistry.registerSatellite("gasMining", MissionGasCollection.class);
        SatelliteRegistry.registerSatellite("solarEnergy", SatelliteMicrowaveEnergy.class);
        SatelliteRegistry.registerSatellite("oreScanner", SatelliteOreMapping.class);
        SatelliteRegistry.registerSatellite("biomeChanger", SatelliteBiomeChanger.class);
        SatelliteRegistry.registerSatellite("weatherController", SatelliteWeatherController.class);


        //Entity Registration ---------------------------------------------------------------------------------------------
        // The per-mod network id a client resolves an incoming spawn with is scoped to the MOD
        // CONTAINER - which this jar's vendored code bases share with this mod - and nothing in
        // Forge rejects a duplicate: the client builds whichever registered first and then reads
        // another entity's fields out of it. EntityNetworkIds owns that one space and supplies the
        // id, so no registration here names a number; a new entity is declared there first.
        EntityNetworkIds.register(new ResourceLocation(Constants.modId, "mountDummy"), EntityDummy.class, "mountDummy", this, 16, 20, false);
        // updateFrequency=1: Free Flight is a fast, piloted arcade craft whose
        // server motion the FA control loop nudges every tick. At the old 3-tick
        // cadence the client dead-reckoned across stale samples and the position
        // error sawtoothed the first-person camera (visible jitter). Per-tick
        // pos/rotation/velocity sync shrinks the correction to one tick's error —
        // smooth. One piloted rocket's extra tracker traffic is negligible.
        EntityNetworkIds.register(new ResourceLocation(Constants.modId, "rocket"), EntityRocket.class, "rocket", this, 64, 1, true);
        EntityNetworkIds.register(new ResourceLocation(Constants.modId, "laserNode"), EntityLaserNode.class, "laserNode", instance, 256, 20, false);
        EntityNetworkIds.register(new ResourceLocation(Constants.modId, "deployedRocket"), EntityStationDeployedRocket.class, "deployedRocket", this, 256, 600, true);
        EntityNetworkIds.register(new ResourceLocation(Constants.modId, "StellurgyAbductedItem"), EntityItemAbducted.class, "StellurgyAbductedItem", this, 127, 600, false);
        EntityNetworkIds.register(new ResourceLocation(Constants.modId, "StellurgyPlanetUIItem"), EntityUIPlanet.class, "StellurgyPlanetUIItem", this, 64, 1, false);
        EntityNetworkIds.register(new ResourceLocation(Constants.modId, "StellurgyPlanetUIButton"), EntityUIButton.class, "StellurgyPlanetUIButton", this, 64, 20, false);
        EntityNetworkIds.register(new ResourceLocation(Constants.modId, "StellurgyStarUIButton"), EntityUIStar.class, "StellurgyStarUIButton", this, 64, 20, false);
        EntityNetworkIds.register(new ResourceLocation(Constants.modId, "StellurgySpaceElevatorCapsule"), EntityElevatorCapsule.class, "StellurgySpaceElevatorCapsule", this, 64, 20, true);
        EntityNetworkIds.register(new ResourceLocation(Constants.modId, "StellurgyHoverCraft"), EntityHoverCraft.class, "hovercraft", this, 64, 1, true);

        //TileEntity Registration ---------------------------------------------------------------------------------------------
        GameRegistry.registerTileEntity(TileBrokenPart.class, "ARbrokenPart");
        GameRegistry.registerTileEntity(dev.stannismod.stellurgy.tile.TileWearable.class, "ARwearablePart");
        GameRegistry.registerTileEntity(TileRocketServiceStation.class, "ARserviceStation");
        GameRegistry.registerTileEntity(TileRocketAssemblingMachine.class, "ARrocketBuilder");
        //GameRegistry.registerTileEntity(TileModelRender.class, "ARmodelRenderer");
        GameRegistry.registerTileEntity(TileFuelingStation.class, "ARfuelingStation");
        GameRegistry.registerTileEntity(TileRocketMonitoringStation.class, "ARmonitoringStation");
        //GameRegistry.registerTileEntity(TileMissionController.class, "ARmissionControlComp");
        GameRegistry.registerTileEntity(TileOrbitalLaserDrill.class, "ARspaceLaser");
        GameRegistry.registerTileEntity(TilePrecisionAssembler.class, "ARprecisionAssembler");
        GameRegistry.registerTileEntity(TileObservatory.class, "ARobservatory");
        GameRegistry.registerTileEntity(TileCrystallizer.class, "ARcrystallizer");
        GameRegistry.registerTileEntity(TileCuttingMachine.class, "ARcuttingmachine");
        GameRegistry.registerTileEntity(TileDataBus.class, "ARdataBus");
        GameRegistry.registerTileEntity(TileDataBusBig.class, "ARdataBusBig");
        GameRegistry.registerTileEntity(TileSatelliteHatch.class, "ARsatelliteHatch");
        GameRegistry.registerTileEntity(TileInvHatch.class, "ARinventoryHatch");
        GameRegistry.registerTileEntity(TileGuidanceComputerAccessHatch.class, "ARguidanceComputerHatch");
        GameRegistry.registerTileEntity(TileSatelliteBuilder.class, "ARsatelliteBuilder");
        GameRegistry.registerTileEntity(TileSatelliteTerminal.class, "StellurgyTileEntitySatelliteControlCenter");
        GameRegistry.registerTileEntity(TileTerraformingTerminal.class, "StellurgyTileEntityTerraformingTerminal");
        GameRegistry.registerTileEntity(TileAstrobodyDataProcessor.class, "ARplanetAnalyser");
        GameRegistry.registerTileEntity(TileGuidanceComputer.class, "ARguidanceComputer");
        GameRegistry.registerTileEntity(TileAdvancedFlightComputer.class, "ARadvancedFlightComputer");
        GameRegistry.registerTileEntity(dev.stannismod.stellurgy.tile.TileNavigationComputer.class, "ARnavigationComputer");
        GameRegistry.registerTileEntity(TilePilotSeat.class, "ARpilotSeat");
        GameRegistry.registerTileEntity(dev.stannismod.stellurgy.tile.hyperdrive.TileHyperdriveGenerator.class, "ARhyperdriveGenerator");
        GameRegistry.registerTileEntity(dev.stannismod.stellurgy.tile.hyperdrive.TileJumpFieldEmitter.class, "ARjumpFieldEmitter");
        GameRegistry.registerTileEntity(dev.stannismod.stellurgy.tile.hyperdrive.TileJumpCapacitor.class, "ARjumpCapacitor");
        GameRegistry.registerTileEntity(dev.stannismod.stellurgy.tile.hyperdrive.TileGravityDampener.class, "ARgravityDampener");
        GameRegistry.registerTileEntity(TileElectricArcFurnace.class, "ARelectricArcFurnace");
        GameRegistry.registerTileEntity(TilePlanetSelector.class, "StellurgyTilePlanetSelector");
        //GameRegistry.registerTileEntity(TileModelRenderRotatable.class, "StellurgyTileModelRenderRotatable");
        GameRegistry.registerTileEntity(TileMaterial.class, "StellurgyTileMaterial");
        GameRegistry.registerTileEntity(TileLathe.class, "StellurgyTileLathe");
        GameRegistry.registerTileEntity(TileRollingMachine.class, "StellurgyTileMetalBender");
        GameRegistry.registerTileEntity(TileStationAssembler.class, "StellurgyStationBuilder");
        GameRegistry.registerTileEntity(TileElectrolyser.class, "StellurgyElectrolyser");
        GameRegistry.registerTileEntity(TileChemicalReactor.class, "StellurgyChemicalReactor");
        GameRegistry.registerTileEntity(TileOxygenVent.class, "StellurgyOxygenVent");
        GameRegistry.registerTileEntity(TileGasChargePad.class, "StellurgyOxygenCharger");
        GameRegistry.registerTileEntity(TileCO2Scrubber.class, "ARCO2Scrubber");
        // These eleven ids are NEW - they have never been written into a save, so unlike the frozen
        // AR* strings further down there is nothing here to keep readable, and they are spelled the
        // way the renamed ids around them are rather than transliterated from the AR* form.
        GameRegistry.registerTileEntity(TileAirRecirculator.class, "StellurgyAirRecirculator");
        GameRegistry.registerTileEntity(TileGasSeparator.class, "StellurgyGasSeparator");
        GameRegistry.registerTileEntity(dev.stannismod.stellurgy.tile.atmosphere.TileLifeSupportPlant.class, "StellurgyLifeSupportPlant");
        GameRegistry.registerTileEntity(dev.stannismod.stellurgy.tile.atmosphere.TileVentilationDuct.class, "StellurgyVentilationDuct");
        GameRegistry.registerTileEntity(dev.stannismod.stellurgy.tile.infrastructure.TileJettisonPort.class, "StellurgyJettisonPort");
        GameRegistry.registerTileEntity(dev.stannismod.stellurgy.tile.heat.TileHeatPipe.class, "StellurgyHeatPipe");
        GameRegistry.registerTileEntity(dev.stannismod.stellurgy.tile.heat.TileHeatAccumulator.class, "StellurgyHeatAccumulator");
        GameRegistry.registerTileEntity(dev.stannismod.stellurgy.tile.heat.TileHeatRadiator.class, "StellurgyHeatRadiator");
        GameRegistry.registerTileEntity(dev.stannismod.stellurgy.tile.heat.TileHeatChiller.class, "StellurgyHeatChiller");
        GameRegistry.registerTileEntity(dev.stannismod.stellurgy.tile.heat.TileHeatDump.class, "StellurgyHeatDump");
        GameRegistry.registerTileEntity(dev.stannismod.stellurgy.tile.heat.TileHeatIntakeDuct.class, "StellurgyHeatIntakeDuct");
        GameRegistry.registerTileEntity(TileWarpController.class, "StellurgyStationMonitor");
        GameRegistry.registerTileEntity(TileAtmosphereDetector.class, "StellurgyOxygenDetector");
        GameRegistry.registerTileEntity(TileStationOrientationController.class, "StellurgyOrientationControl");
        GameRegistry.registerTileEntity(TileStationGravityController.class, "StellurgyGravityControl");
        GameRegistry.registerTileEntity(TileMicrowaveReciever.class, "StellurgyMicrowaveReciever");
        GameRegistry.registerTileEntity(TileSuitWorkStation.class, "StellurgySuitWorkStation");
        GameRegistry.registerTileEntity(TileRocketLoader.class, "StellurgyRocketLoader");
        GameRegistry.registerTileEntity(TileRocketUnloader.class, "StellurgyRocketUnloader");
        GameRegistry.registerTileEntity(TileBiomeScanner.class, "StellurgyBiomeScanner");
        GameRegistry.registerTileEntity(TileAtmosphereTerraformer.class, "StellurgyAttTerraformer");
        GameRegistry.registerTileEntity(TileLandingPad.class, "StellurgyLandingPad");
        GameRegistry.registerTileEntity(TileUnmannedVehicleAssembler.class, "StellurgyStationDeployableRocketAssembler");
        GameRegistry.registerTileEntity(TileFluidTank.class, "StellurgyFluidTank");
        GameRegistry.registerTileEntity(TileRocketFluidUnloader.class, "StellurgyFluidUnloader");
        GameRegistry.registerTileEntity(TileRocketFluidLoader.class, "StellurgyFluidLoader");
        GameRegistry.registerTileEntity(TileSolarPanel.class, "StellurgySolarGenerator");
        GameRegistry.registerTileEntity(TileDockingPort.class, "StellurgyDockingPort");
        GameRegistry.registerTileEntity(TileStationAltitudeController.class, "StellurgyStationAltitudeController");
        GameRegistry.registerTileEntity(TileRailgun.class, "StellurgyRailgun");
        GameRegistry.registerTileEntity(TileHolographicPlanetSelector.class, "ARplanetHoloSelector");
        GameRegistry.registerTileEntity(TileSeal.class, "StellurgyBlockSeal");
        GameRegistry.registerTileEntity(TileSpaceElevator.class, "StellurgySpaceElevator");
        GameRegistry.registerTileEntity(TileBeacon.class, "StellurgyBeacon");
        //FROZEN save id — the misspelling is deliberate, do not "fix" it. This exact string is
        //written as the tile's NBT "id" into every saved chunk and into packed rockets and
        //stations (StorageChunk); a mismatch drops the tile silently on load — the block
        //survives, its network id, mode and priority do not.
        //TODO(0.1.0): rename to StellurgyTransceiver only behind a compat alias that keeps the old id
        //readable — MissingMappings covers Forge registries, not TileEntity.REGISTRY.
        GameRegistry.registerTileEntity(TileWirelessTransceiver.class, "StellurgyTransciever");
        GameRegistry.registerTileEntity(TileBlackHoleGenerator.class, "ARblackholegenerator");
        GameRegistry.registerTileEntity(TilePump.class, new ResourceLocation(Constants.modId, "ARpump"));
        GameRegistry.registerTileEntity(TileCentrifuge.class, new ResourceLocation(Constants.modId, "StellurgyCentrifuge"));
        GameRegistry.registerTileEntity(TilePrecisionLaserEtcher.class, new ResourceLocation(Constants.modId, "StellurgyPrecisionLaserEtcher"));
        GameRegistry.registerTileEntity(TileSolarArray.class, new ResourceLocation(Constants.modId, "StellurgySolarArray"));
        GameRegistry.registerTileEntity(TileOrbitalRegistry.class, new ResourceLocation(Constants.modId, "orbitalRegistry"));
        GameRegistry.registerTileEntity(dev.stannismod.stellurgy.tile.weapon.TileTurret.class,
                new ResourceLocation(Constants.modId, "ARturret"));
        GameRegistry.registerTileEntity(dev.stannismod.stellurgy.tile.weapon.TileWeaponConsole.class,
                new ResourceLocation(Constants.modId, "ARweaponConsole"));
        GameRegistry.registerTileEntity(dev.stannismod.stellurgy.tile.sensor.TileFireControlSensor.class,
                new ResourceLocation(Constants.modId, "ARfireControlSensor"));

        if (dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig().enableGravityController)
            GameRegistry.registerTileEntity(TileAreaGravityController.class, "StellurgyGravityMachine");


        //Register machine recipes
        libVulpes.registerRecipeHandler(TileCuttingMachine.class, event.getModConfigurationDirectory().getAbsolutePath() + "/" + dev.stannismod.stellurgy.api.StellurgyConfiguration.configFolder + "/CuttingMachine.xml");
        libVulpes.registerRecipeHandler(TilePrecisionAssembler.class, event.getModConfigurationDirectory().getAbsolutePath() + "/" + dev.stannismod.stellurgy.api.StellurgyConfiguration.configFolder + "/PrecisionAssembler.xml");
        libVulpes.registerRecipeHandler(TileChemicalReactor.class, event.getModConfigurationDirectory().getAbsolutePath() + "/" + dev.stannismod.stellurgy.api.StellurgyConfiguration.configFolder + "/ChemicalReactor.xml");
        libVulpes.registerRecipeHandler(TileCrystallizer.class, event.getModConfigurationDirectory().getAbsolutePath() + "/" + dev.stannismod.stellurgy.api.StellurgyConfiguration.configFolder + "/Crystallizer.xml");
        libVulpes.registerRecipeHandler(TileElectrolyser.class, event.getModConfigurationDirectory().getAbsolutePath() + "/" + dev.stannismod.stellurgy.api.StellurgyConfiguration.configFolder + "/Electrolyser.xml");
        libVulpes.registerRecipeHandler(TileElectricArcFurnace.class, event.getModConfigurationDirectory().getAbsolutePath() + "/" + dev.stannismod.stellurgy.api.StellurgyConfiguration.configFolder + "/ElectricArcFurnace.xml");
        libVulpes.registerRecipeHandler(TileLathe.class, event.getModConfigurationDirectory().getAbsolutePath() + "/" + dev.stannismod.stellurgy.api.StellurgyConfiguration.configFolder + "/Lathe.xml");
        libVulpes.registerRecipeHandler(TileRollingMachine.class, event.getModConfigurationDirectory().getAbsolutePath() + "/" + dev.stannismod.stellurgy.api.StellurgyConfiguration.configFolder + "/RollingMachine.xml");
        libVulpes.registerRecipeHandler(BlockSmallPlatePress.class, event.getModConfigurationDirectory().getAbsolutePath() + "/" + dev.stannismod.stellurgy.api.StellurgyConfiguration.configFolder + "/SmallPlatePress.xml");
        libVulpes.registerRecipeHandler(TileCentrifuge.class, event.getModConfigurationDirectory().getAbsolutePath() + "/" + dev.stannismod.stellurgy.api.StellurgyConfiguration.configFolder + "/Centrifuge.xml");
        libVulpes.registerRecipeHandler(TilePrecisionLaserEtcher.class, event.getModConfigurationDirectory().getAbsolutePath() + "/" + dev.stannismod.stellurgy.api.StellurgyConfiguration.configFolder + "/PrecisionLaserEtcher.xml");


        //AUDIO

        //MOD-SPECIFIC ENTRIES --------------------------------------------------------------------------------------------------------------------------


        //Register Space Objects
        SpaceObjectManager.registerSpaceObjectType("genericObject", SpaceStationObject.class);


        //Register item/block crap
        proxy.preinit();

        //Register machines
        machineRecipes.registerMachine(TileElectrolyser.class);
        machineRecipes.registerMachine(TileCuttingMachine.class);
        machineRecipes.registerMachine(TileLathe.class);
        machineRecipes.registerMachine(TilePrecisionAssembler.class);
        machineRecipes.registerMachine(TileElectricArcFurnace.class);
        machineRecipes.registerMachine(TileChemicalReactor.class);
        machineRecipes.registerMachine(TileRollingMachine.class);
        machineRecipes.registerMachine(TileCrystallizer.class);
        machineRecipes.registerMachine(TileCentrifuge.class);
        machineRecipes.registerMachine(TilePrecisionLaserEtcher.class);

        // Valkyrien Skies is vendored into Stellurgy and hosted by Stellurgy's mod container: drive its lifecycle
        // from Stellurgy's own handlers (VS is no longer a separate @Mod with its own @EventHandler methods).
        valkyrienSkies.preInit(event);

        // Advanced Force Field System (shield subsystem) was a separate mod and is folded into Stellurgy's
        // mod container: drive its lifecycle from Stellurgy's own handlers, as with VS above.
        affs.preInit(event);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void registerEnchants(RegistryEvent.Register<Enchantment> evt) {
        //Enchantments
        StellurgyAPI.enchantmentSpaceProtection = new EnchantmentSpaceBreathing();
        StellurgyAPI.enchantmentSpaceProtection.setRegistryName(new ResourceLocation("stellurgy:spacebreathing"));
        evt.getRegistry().register(StellurgyAPI.enchantmentSpaceProtection);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void registerItems(RegistryEvent.Register<Item> evt) {
        //Items -------------------------------------------------------------------------------------
        StellurgyItems.itemWafer = new ItemIngredient(1).setUnlocalizedName("stellurgy:wafer").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemCircuitPlate = new ItemIngredient(2).setUnlocalizedName("stellurgy:circuitplate").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemIC = new ItemIngredient(6).setUnlocalizedName("stellurgy:circuitIC").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemMisc = new ItemIngredient(2).setUnlocalizedName("stellurgy:miscpart").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemSawBlade = new ItemIngredient(1).setUnlocalizedName("stellurgy:sawBlade").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemSpaceStationChip = new ItemStationChip().setUnlocalizedName("stationChip").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemSpaceElevatorChip = new ItemSpaceElevatorChip().setUnlocalizedName("elevatorChip").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemAsteroidChip = new ItemAsteroidChip().setUnlocalizedName("asteroidChip").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemSpaceStation = new ItemPackedStructure().setUnlocalizedName("station");
        StellurgyItems.itemSmallAirlockDoor = new ItemDoor(StellurgyBlocks.blockAirLock).setUnlocalizedName("smallAirlock").setCreativeTab(tabAdvRocketry);
        //Short.MAX_VALUE is forge's wildcard, don't use it
        StellurgyItems.itemCarbonScrubberCartridge = new Item().setMaxDamage(Short.MAX_VALUE - 1).setUnlocalizedName("carbonScrubberCartridge").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemCarbonDust = new Item().setUnlocalizedName("carbonDust").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemLens = new ItemIngredient(1).setUnlocalizedName("stellurgy:lens").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemSatellitePowerSource = new ItemIngredient(2).setUnlocalizedName("stellurgy:satellitePowerSource").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemSatellitePrimaryFunction = new ItemIngredient(7).setUnlocalizedName("stellurgy:satellitePrimaryFunction").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemThermite = new ItemThermite().setUnlocalizedName("thermite").setCreativeTab(tabAdvRocketry);

        //TODO: move registration in the case we have more than one chip type
        StellurgyItems.itemDataUnit = new ItemData().setUnlocalizedName("stellurgy:dataUnit").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemMemoryCrystal = new dev.stannismod.stellurgy.item.ItemMemoryCrystal().setUnlocalizedName("stellurgy:memoryCrystal").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemRepairWelder = new dev.stannismod.stellurgy.item.ItemRepairWelder().setUnlocalizedName("stellurgy:repairWelder").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemOreScanner = new ItemOreScanner().setUnlocalizedName("OreScanner").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemQuartzCrucible = new ItemBlock(StellurgyBlocks.blockQuartzCrucible).setUnlocalizedName("qcrucible").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemSatellite = new ItemSatellite().setUnlocalizedName("satellite").setCreativeTab(tabAdvRocketry).setMaxStackSize(1);
        StellurgyItems.itemSatelliteIdChip = new ItemSatelliteIdentificationChip().setUnlocalizedName("satelliteIdChip").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemPlanetIdChip = new ItemPlanetIdentificationChip().setUnlocalizedName("planetIdChip").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemBiomeChanger = new ItemBiomeChanger().setUnlocalizedName("biomeChanger").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemWeatherController = new ItemWeatherController().setUnlocalizedName("weatherController").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemBasicLaserGun = new ItemBasicLaserGun().setUnlocalizedName("basicLaserGun").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemHovercraft = new ItemHovercraft().setUnlocalizedName("hovercraft").setCreativeTab(tabAdvRocketry);

        //Suit Component Registration
        StellurgyItems.itemJetpack = new ItemJetpack().setCreativeTab(tabAdvRocketry).setUnlocalizedName("jetPack");
        StellurgyItems.itemPressureTank = new ItemPressureTank(4, (int) (1000 * StellurgyConfiguration.getCurrentConfig().suitTankCapacity)).setCreativeTab(tabAdvRocketry).setUnlocalizedName("stellurgy:pressureTank");
        StellurgyItems.itemUpgrade = new ItemUpgrade(6).setCreativeTab(tabAdvRocketry).setUnlocalizedName("stellurgy:itemUpgrade");
        StellurgyItems.itemAtmAnalyser = new ItemAtmosphereAnalzer().setCreativeTab(tabAdvRocketry).setUnlocalizedName("atmAnalyser");
        StellurgyItems.itemBeaconFinder = new ItemBeaconFinder().setCreativeTab(tabAdvRocketry).setUnlocalizedName("beaconFinder");

        //Armor registration
        StellurgyItems.itemSpaceSuit_Helmet = new ItemSpaceArmor(ArmorMaterial.LEATHER, EntityEquipmentSlot.HEAD, 4).setCreativeTab(tabAdvRocketry).setUnlocalizedName("spaceHelmet");
        StellurgyItems.itemSpaceSuit_Chest = new ItemSpaceChest(ArmorMaterial.LEATHER, EntityEquipmentSlot.CHEST, 6).setCreativeTab(tabAdvRocketry).setUnlocalizedName("spaceChest");
        StellurgyItems.itemSpaceSuit_Leggings = new ItemSpaceArmor(ArmorMaterial.LEATHER, EntityEquipmentSlot.LEGS, 4).setCreativeTab(tabAdvRocketry).setUnlocalizedName("spaceLeggings");
        StellurgyItems.itemSpaceSuit_Boots = new ItemSpaceArmor(ArmorMaterial.LEATHER, EntityEquipmentSlot.FEET, 4).setCreativeTab(tabAdvRocketry).setUnlocalizedName("spaceBoots");
        StellurgyItems.itemSealDetector = new ItemSealDetector().setMaxStackSize(1).setCreativeTab(tabAdvRocketry).setUnlocalizedName("sealDetector");

        //Tools
        StellurgyItems.itemJackhammer = new ItemJackHammer(ToolMaterial.DIAMOND).setUnlocalizedName("jackhammer").setCreativeTab(tabAdvRocketry);
        StellurgyItems.itemJackhammer.setHarvestLevel("jackhammer", 3);
        StellurgyItems.itemJackhammer.setHarvestLevel("pickaxe", 3);

        //Register Satellite Properties
        SatelliteRegistry.registerSatelliteProperty(new ItemStack(StellurgyItems.itemSatellitePrimaryFunction, 1, 0), new SatelliteProperties().setSatelliteType(SatelliteRegistry.getKey(SatelliteOptical.class)));
        SatelliteRegistry.registerSatelliteProperty(new ItemStack(StellurgyItems.itemSatellitePrimaryFunction, 1, 1), new SatelliteProperties().setSatelliteType(SatelliteRegistry.getKey(SatelliteComposition.class)));
        SatelliteRegistry.registerSatelliteProperty(new ItemStack(StellurgyItems.itemSatellitePrimaryFunction, 1, 2), new SatelliteProperties().setSatelliteType(SatelliteRegistry.getKey(SatelliteMassScanner.class)));
        SatelliteRegistry.registerSatelliteProperty(new ItemStack(StellurgyItems.itemSatellitePrimaryFunction, 1, 3), new SatelliteProperties().setSatelliteType(SatelliteRegistry.getKey(SatelliteMicrowaveEnergy.class)));
        SatelliteRegistry.registerSatelliteProperty(new ItemStack(StellurgyItems.itemSatellitePrimaryFunction, 1, 4), new SatelliteProperties().setSatelliteType(SatelliteRegistry.getKey(SatelliteOreMapping.class)));
        SatelliteRegistry.registerSatelliteProperty(new ItemStack(StellurgyItems.itemSatellitePrimaryFunction, 1, 5), new SatelliteProperties().setSatelliteType(SatelliteRegistry.getKey(SatelliteBiomeChanger.class)));
        SatelliteRegistry.registerSatelliteProperty(new ItemStack(StellurgyItems.itemSatellitePrimaryFunction, 1, 6), new SatelliteProperties().setSatelliteType(SatelliteRegistry.getKey(SatelliteWeatherController.class)));
        SatelliteRegistry.registerSatelliteProperty(new ItemStack(StellurgyItems.itemSatellitePowerSource, 1, 0), new SatelliteProperties().setPowerGeneration(4));
        SatelliteRegistry.registerSatelliteProperty(new ItemStack(StellurgyItems.itemSatellitePowerSource, 1, 1), new SatelliteProperties().setPowerGeneration(40));
        SatelliteRegistry.registerSatelliteProperty(new ItemStack(LibVulpesItems.itemBattery, 1, 0), new SatelliteProperties().setPowerStorage(10000));
        SatelliteRegistry.registerSatelliteProperty(new ItemStack(LibVulpesItems.itemBattery, 1, 1), new SatelliteProperties().setPowerStorage(40000));
        SatelliteRegistry.registerSatelliteProperty(new ItemStack(StellurgyItems.itemDataUnit, 1, 0), new SatelliteProperties().setMaxData(1000));


        //Item Registration
        //Circuit pieces
        LibVulpesBlocks.registerItem(StellurgyItems.itemWafer.setRegistryName("wafer"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemCircuitPlate.setRegistryName("itemCircuitPlate"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemIC.setRegistryName("ic"));
        //Chips
        LibVulpesBlocks.registerItem(StellurgyItems.itemSatelliteIdChip.setRegistryName("satelliteIdChip"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemPlanetIdChip.setRegistryName("planetIdChip"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemAsteroidChip.setRegistryName("asteroidChip"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemSpaceElevatorChip.setRegistryName("elevatorChip"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemSpaceStationChip.setRegistryName("spaceStationChip"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemDataUnit.setRegistryName("dataUnit"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemMemoryCrystal.setRegistryName("memoryCrystal"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemRepairWelder.setRegistryName("repairWelder"));
        //Satellite bits
        LibVulpesBlocks.registerItem(StellurgyItems.itemSatellite.setRegistryName("satellite"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemSatellitePowerSource.setRegistryName("satellitePowerSource"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemSatellitePrimaryFunction.setRegistryName("satellitePrimaryFunction"));
        //Spacesuit
        LibVulpesBlocks.registerItem(StellurgyItems.itemSpaceSuit_Helmet.setRegistryName("spaceHelmet"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemSpaceSuit_Chest.setRegistryName("spaceChestplate"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemSpaceSuit_Leggings.setRegistryName("spaceLeggings"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemSpaceSuit_Boots.setRegistryName("spaceBoots"));
        //Space suit modifiers
        LibVulpesBlocks.registerItem(StellurgyItems.itemPressureTank.setRegistryName("pressureTank"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemUpgrade.setRegistryName("itemUpgrade"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemBeaconFinder.setRegistryName("beaconFinder"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemJetpack.setRegistryName("jetPack"));
        //Handheld tools
        LibVulpesBlocks.registerItem(StellurgyItems.itemAtmAnalyser.setRegistryName("atmAnalyser"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemSealDetector.setRegistryName("sealDetector"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemOreScanner.setRegistryName("oreScanner"));
        if (dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig().enableTerraforming){
            LibVulpesBlocks.registerItem(StellurgyItems.itemBiomeChanger.setRegistryName("biomeChanger"));
            LibVulpesBlocks.registerItem(StellurgyItems.itemWeatherController.setRegistryName("weatherController"));
        }
        LibVulpesBlocks.registerItem(StellurgyItems.itemJackhammer.setRegistryName("jackHammer"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemBasicLaserGun.setRegistryName("basicLaserGun"));
        //Misc
        LibVulpesBlocks.registerItem(StellurgyItems.itemMisc.setRegistryName("misc"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemSawBlade.setRegistryName("sawBladeIron"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemQuartzCrucible.setRegistryName("iquartzcrucible"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemLens.setRegistryName("lens"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemThermite.setRegistryName("thermite"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemCarbonScrubberCartridge.setRegistryName("carbonScrubberCartridge"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemCarbonDust.setRegistryName("carbonDust"));
        // dustCarbon is the ore-dictionary name every 1.12 tech mod uses for powdered carbon, so
        // our recirculator output feeds their recipes and theirs feeds ours. The dictionary is a
        // registry we do not namespace: joining it is the whole point, not a side effect.
        net.minecraftforge.oredict.OreDictionary.registerOre("dustCarbon", StellurgyItems.itemCarbonDust);
        LibVulpesBlocks.registerItem(StellurgyItems.itemSmallAirlockDoor.setRegistryName("smallAirlockDoor"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemHovercraft.setRegistryName("hoverCraft"));
        LibVulpesBlocks.registerItem(StellurgyItems.itemSpaceStation.setRegistryName("spaceStation"));


        OreDictionary.registerOre("waferSilicon", new ItemStack(StellurgyItems.itemWafer, 1, 0));
        OreDictionary.registerOre("ingotCarbon", new ItemStack(StellurgyItems.itemMisc, 1, 1));
        OreDictionary.registerOre("itemLens", StellurgyItems.itemLens);
        OreDictionary.registerOre("lensPrecisionLaserEtcher", StellurgyItems.itemLens);
        OreDictionary.registerOre("itemSilicon", MaterialRegistry.getItemStackFromMaterialAndType("Silicon", AllowedProducts.getProductByName("INGOT")));
        OreDictionary.registerOre("dustThermite", new ItemStack(StellurgyItems.itemThermite));
        OreDictionary.registerOre("slab", new ItemStack(Blocks.STONE_SLAB));

        Item.getItemFromBlock(StellurgyBlocks.blockEngine).setMaxDamage(10);
        Item.getItemFromBlock(StellurgyBlocks.blockAdvEngine).setMaxDamage(10);
        Item.getItemFromBlock(StellurgyBlocks.blockBipropellantEngine).setMaxDamage(10);
        Item.getItemFromBlock(StellurgyBlocks.blockAdvBipropellantEngine).setMaxDamage(10);
        Item.getItemFromBlock(StellurgyBlocks.blockNuclearEngine).setMaxDamage(10);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void registerBlocks(RegistryEvent.Register<Block> evt) {
        //Blocks -------------------------------------------------------------------------------------
        //Machines
        //Machine parts
        StellurgyBlocks.blockConcrete = new Block(Material.ROCK).setUnlocalizedName("concrete").setCreativeTab(tabAdvRocketry).setHardness(3f).setResistance(16f);
        StellurgyBlocks.blockBlastBrick = new BlockMultiBlockComponentVisible(Material.ROCK).setCreativeTab(tabAdvRocketry).setUnlocalizedName("blastBrick").setHardness(3F).setResistance(15F);
        StellurgyBlocks.blockStructureTower = new BlockAlphaTexture(Material.IRON).setUnlocalizedName("structuretower").setCreativeTab(tabAdvRocketry).setHardness(2f);
        StellurgyBlocks.blockLens = new BlockLens().setUnlocalizedName("lens").setCreativeTab(tabAdvRocketry).setHardness(0.3f);
        // The tiers differ ONLY in reflectance, deliberately: the film they share is the same
        // thickness, so what a better mirror buys is that less of each hit stays in it.
        StellurgyBlocks.blockMirrorPlatingAluminium = new BlockMirrorPlating(0.90D, MIRROR_FILM_DISSIPATION)
                .setUnlocalizedName("mirrorPlatingAluminium").setCreativeTab(tabAdvRocketry);
        StellurgyBlocks.blockMirrorPlatingSilver = new BlockMirrorPlating(0.96D, MIRROR_FILM_DISSIPATION)
                .setUnlocalizedName("mirrorPlatingSilver").setCreativeTab(tabAdvRocketry);
        StellurgyBlocks.blockMirrorPlatingGold = new BlockMirrorPlating(0.97D, MIRROR_FILM_DISSIPATION)
                .setUnlocalizedName("mirrorPlatingGold").setCreativeTab(tabAdvRocketry);
        // Heavy plating swallows twice what light does; nothing else separates the two, because what a
        // body meets is the voxel and not the shape inside it.
        StellurgyBlocks.blockReactivePlate = new BlockReactivePlating(REACTIVE_PLATE_CAPACITY)
                .setUnlocalizedName("reactivePlate").setCreativeTab(tabAdvRocketry);
        StellurgyBlocks.blockReactiveBlock = new BlockReactivePlating(REACTIVE_PLATE_CAPACITY * 2)
                .setUnlocalizedName("reactiveBlock").setCreativeTab(tabAdvRocketry);
        StellurgyBlocks.blockSolarPanel = new Block(Material.IRON).setUnlocalizedName("solarPanel").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockSolarArrayPanel = new BlockMultiBlockComponentVisibleAlphaTexture(Material.IRON).setUnlocalizedName("solararraypanel").setCreativeTab(tabAdvRocketry).setHardness(1).setResistance(1f);
        StellurgyBlocks.blockQuartzCrucible = new BlockQuartzCrucible().setUnlocalizedName("qcrucible").setCreativeTab(tabAdvRocketry);
        StellurgyBlocks.blockSawBlade = new BlockMotor(Material.IRON, 1f).setCreativeTab(tabAdvRocketry).setUnlocalizedName("sawBlade").setHardness(2f);
        //Singleblock machines
        StellurgyBlocks.blockPlatePress = new BlockSmallPlatePress().setUnlocalizedName("platepress").setCreativeTab(tabAdvRocketry).setHardness(2f);
        StellurgyBlocks.blockPlatePressHead = new BlockSmallPlatePressHead().setUnlocalizedName("platepress_head").setHardness(2f);
        StellurgyBlocks.blockVacuumLaser = new BlockVacuumLaser(Material.IRON).setUnlocalizedName("vacuumLaser").setCreativeTab(tabAdvRocketry).setHardness(4f);
        StellurgyBlocks.blockPump = new BlockPump(TilePump.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("pump").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockSuitWorkStation = new BlockSuitWorkstation(TileSuitWorkStation.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("suitWorkStation").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockPressureTank = new BlockPressurizedFluidTank(Material.IRON).setUnlocalizedName("pressurizedTank").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockSolarGenerator = new BlockSolarGenerator(TileSolarPanel.class, GuiHandler.guiId.MODULAR.ordinal()).setCreativeTab(tabAdvRocketry).setHardness(3f).setUnlocalizedName("solarGenerator");
        StellurgyBlocks.blockTransciever = new BlockTransceiver(TileWirelessTransceiver.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("wirelessTransceiver").setCreativeTab(tabAdvRocketry).setHardness(3f);
        //Multiblock machines
        //T1 processing
        StellurgyBlocks.blockArcFurnace = new BlockMultiblockMachine(TileElectricArcFurnace.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("electricArcFurnace").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockRollingMachine = new BlockMultiblockMachine(TileRollingMachine.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("rollingMachine").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockLathe = new BlockMultiblockMachine(TileLathe.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("lathe").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockCrystallizer = new BlockMultiblockMachine(TileCrystallizer.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("Crystallizer").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockCuttingMachine = new BlockMultiblockMachine(TileCuttingMachine.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("cuttingMachine").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockPrecisionAssembler = new BlockMultiblockMachine(TilePrecisionAssembler.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("precisionAssemblingMachine").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockElectrolyser = new BlockMultiblockMachine(TileElectrolyser.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("electrolyser").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockChemicalReactor = new BlockMultiblockMachine(TileChemicalReactor.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("chemreactor").setCreativeTab(tabAdvRocketry).setHardness(3f);
        //T2 processing
        StellurgyBlocks.blockPrecisionLaserEngraver = new BlockMultiblockMachine(TilePrecisionLaserEtcher.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("precisionlaseretcher").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockObservatory = new BlockMultiblockMachine(TileObservatory.class, GuiHandler.guiId.MODULARNOINV.ordinal()).setUnlocalizedName("observatory").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockPlanetAnalyser = new BlockMultiblockMachine(TileAstrobodyDataProcessor.class, GuiHandler.guiId.MODULARNOINV.ordinal()).setUnlocalizedName("planetanalyser").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockCentrifuge = new BlockMultiblockMachine(TileCentrifuge.class, GuiHandler.guiId.MODULAR.ordinal()).setCreativeTab(tabAdvRocketry).setHardness(3f).setUnlocalizedName("centrifuge");
        StellurgyBlocks.blockSatelliteBuilder = new BlockMultiblockMachine(TileSatelliteBuilder.class, GuiHandler.guiId.MODULAR.ordinal()).setCreativeTab(tabAdvRocketry).setHardness(3f).setUnlocalizedName("satelliteBuilder");
        
        //Energy
        StellurgyBlocks.blockBlackHoleGenerator = new BlockMultiblockMachine(TileBlackHoleGenerator.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("blackholegenerator").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockMicrowaveReciever = new BlockMultiblockMachine(TileMicrowaveReciever.class, GuiHandler.guiId.MODULAR.ordinal()).setCreativeTab(tabAdvRocketry).setHardness(3f).setUnlocalizedName("microwaveReciever");
        StellurgyBlocks.blockSolarArray = new BlockMultiblockMachine(TileSolarArray.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("solararray").setCreativeTab(tabAdvRocketry).setHardness(3f);
        //Aux/huge
        StellurgyBlocks.blockBeacon = new BlockBeacon(TileBeacon.class, GuiHandler.guiId.MODULAR.ordinal()).setCreativeTab(tabAdvRocketry).setUnlocalizedName("beacon").setHardness(3f);
        StellurgyBlocks.blockBiomeScanner = new BlockMultiblockMachine(TileBiomeScanner.class, GuiHandler.guiId.MODULARNOINV.ordinal()).setUnlocalizedName("biomeScanner").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockRailgun = new BlockMultiblockMachine(TileRailgun.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("railgun").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockSpaceElevatorController = new BlockMultiblockMachine(TileSpaceElevator.class, GuiHandler.guiId.MODULAR.ordinal()).setCreativeTab(tabAdvRocketry).setUnlocalizedName("spaceElevatorController").setHardness(3f);
        //Configurable stuff
        if (StellurgyConfiguration.getCurrentConfig().enableTerraforming)
            //StellurgyBlocks.blockAtmosphereTerraformer = new BlockMultiblockMachine(TileAtmosphereTerraformer.class, GuiHandler.guiId.MODULARNOINV.ordinal()).setUnlocalizedName("atmosphereTerraformer").setCreativeTab(tabAdvRocketry).setHardness(3f);
            StellurgyBlocks.blockAtmosphereTerraformer = new BlockAtmosphereTerraformer(TileAtmosphereTerraformer.class, GuiHandler.guiId.MODULARNOINV.ordinal()).setUnlocalizedName("atmosphereTerraformer").setCreativeTab(tabAdvRocketry).setHardness(3f);
        if (StellurgyConfiguration.getCurrentConfig().enableGravityController)
            StellurgyBlocks.blockGravityMachine = new BlockMultiblockMachine(TileAreaGravityController.class, GuiHandler.guiId.MODULARNOINV.ordinal()).setUnlocalizedName("gravityMachine").setCreativeTab(tabAdvRocketry).setHardness(3f);
        if (StellurgyConfiguration.getCurrentConfig().enableLaserDrill)
            StellurgyBlocks.blockSpaceLaser = new BlockOrbitalLaserDrill().setHardness(2f).setCreativeTab(tabAdvRocketry);
        if (StellurgyConfiguration.getCurrentConfig().enableOrbitalRegistry)
            StellurgyBlocks.blockOrbitalRegistry = new BlockMultiblockMachine(TileOrbitalRegistry.class, GuiHandler.guiId.MODULARNOINV.ordinal()).setCreativeTab(tabAdvRocketry).setHardness(3f).setUnlocalizedName("orbitalRegistry");



        //Docking blocks
        StellurgyBlocks.blockLaunchpad = new BlockLinkedHorizontalTexture(Material.ROCK).setUnlocalizedName("pad").setCreativeTab(tabAdvRocketry).setHardness(2f).setResistance(10f);
        StellurgyBlocks.blockLandingPad = new BlockLandingPad(Material.ROCK).setUnlocalizedName("dockingPad").setHardness(3f).setCreativeTab(tabAdvRocketry);
        StellurgyBlocks.blockDockingPort = new BlockStationModuleDockingPort(Material.IRON).setUnlocalizedName("stationMarker").setCreativeTab(tabAdvRocketry).setHardness(3f);
        //Rocket blocks
        StellurgyBlocks.blockGenericSeat = new BlockSeat(Material.CLOTH).setUnlocalizedName("seat").setCreativeTab(tabAdvRocketry).setHardness(0.5f);
        StellurgyBlocks.blockPilotSeat = new BlockPilotSeat(Material.CLOTH).setUnlocalizedName("pilotSeat").setCreativeTab(tabAdvRocketry).setHardness(0.5f);
        StellurgyBlocks.blockEngine = new BlockRocketMotor(Material.IRON).setUnlocalizedName("rocket").setCreativeTab(tabAdvRocketry).setHardness(2f);
        StellurgyBlocks.blockBipropellantEngine = new BlockBipropellantRocketMotor(Material.IRON).setUnlocalizedName("bipropellantrocket").setCreativeTab(tabAdvRocketry).setHardness(2f);
        StellurgyBlocks.blockAdvEngine = new BlockAdvancedRocketMotor(Material.IRON).setUnlocalizedName("advRocket").setCreativeTab(tabAdvRocketry).setHardness(2f);
        StellurgyBlocks.blockAdvBipropellantEngine = new BlockAdvancedBipropellantRocketMotor(Material.IRON).setUnlocalizedName("advbipropellantRocket").setCreativeTab(tabAdvRocketry).setHardness(2f);
        StellurgyBlocks.blockNuclearEngine = new BlockNuclearRocketMotor(Material.IRON).setUnlocalizedName("nuclearrocket").setCreativeTab(tabAdvRocketry).setHardness(2f);
        StellurgyBlocks.blockFuelTank = new BlockFuelTank(Material.IRON).setUnlocalizedName("fuelTank").setCreativeTab(tabAdvRocketry).setHardness(2f);
        StellurgyBlocks.blockBipropellantFuelTank = new BlockBipropellantFuelTank(Material.IRON).setUnlocalizedName("bipropellantfueltank").setCreativeTab(tabAdvRocketry).setHardness(2f);
        StellurgyBlocks.blockOxidizerFuelTank = new BlockOxidizerFuelTank(Material.IRON).setUnlocalizedName("oxidizerfueltank").setCreativeTab(tabAdvRocketry).setHardness(2f);
        StellurgyBlocks.blockNuclearFuelTank = new BlockNuclearFuelTank(Material.IRON).setUnlocalizedName("nuclearfueltank").setCreativeTab(tabAdvRocketry).setHardness(2f);
        StellurgyBlocks.blockNuclearCore = new BlockNuclearCore(Material.IRON).setUnlocalizedName("nuclearcore").setCreativeTab(tabAdvRocketry).setHardness(2f);
        // The gun family. Each part states what it is worth and nothing else; the numbers a built
        // gun ends up with are the sum, which is why a longer barrel is a real decision rather than
        // a tier. A part contributes only when it is placed against a gun, so these are ordinary
        // blocks with no wiring of their own.
        StellurgyBlocks.blockTurret = new dev.stannismod.stellurgy.block.weapon.BlockTurret()
                .setUnlocalizedName("turret").setCreativeTab(tabAdvRocketry);
        StellurgyBlocks.blockGunBarrel = new dev.stannismod.stellurgy.block.weapon.BlockGunPart(
                builder -> builder.addMuzzleSpeed(0.9D).addImpactEnergy(8).addSpreadDegrees(-0.8D)
                        .addLifetimeTicks(20).addEnergyPerShot(50).addHeatPerShot(1))
                .setUnlocalizedName("gunBarrel").setCreativeTab(tabAdvRocketry);
        StellurgyBlocks.blockGunAmmoFeed = new dev.stannismod.stellurgy.block.weapon.BlockGunPart(
                builder -> builder.speedUpFireIntervalBy(3).addImpactEnergy(6).addEnergyPerShot(75)
                        .addHeatPerShot(2)
                        .declareInput(dev.stannismod.stellurgy.api.weapon.GunInput.FORGE_ENERGY))
                .setUnlocalizedName("gunAmmoFeed").setCreativeTab(tabAdvRocketry);
        // The one part that makes a gun a BEAM rather than a thrower. Power per TICK, so a bigger
        // laser is a laser with more emitters rather than a bigger number written beside one; the
        // declared kind is what makes it priced against the ablation column and absorbed whole by a
        // shell instead of being thrown back off it.
        StellurgyBlocks.blockGunBeamEmitter = new dev.stannismod.stellurgy.block.weapon.BlockGunPart(
                builder -> builder.addBeamPowerPerTick(4_000).setKind(
                        dev.stannismod.stellurgy.api.damage.ImpactKind.BEAM)
                        .addHeatPerShot(1).addHeatCapacity(20)
                        .declareInput(dev.stannismod.stellurgy.api.weapon.GunInput.FORGE_ENERGY))
                .setUnlocalizedName("gunBeamEmitter").setCreativeTab(tabAdvRocketry);
        StellurgyBlocks.blockGunCooling = new dev.stannismod.stellurgy.block.weapon.BlockGunPart(
                builder -> builder.addHeatCapacity(40).addCoolingPerTick(2).addTraverseDegreesPerTick(0.5D))
                .setUnlocalizedName("gunCooling").setCreativeTab(tabAdvRocketry);
        StellurgyBlocks.blockWeaponConsole = new BlockTile(dev.stannismod.stellurgy.tile.weapon.TileWeaponConsole.class,
                GuiHandler.guiId.MODULARNOINV.ordinal()).setUnlocalizedName("weaponConsole")
                .setCreativeTab(tabAdvRocketry).setHardness(3f);
        // The battery's eyes. A node of the same network the guns are on, so a sensor placed against
        // a gun feeds it with no wiring, and one placed alone feeds nothing - which is honest: there
        // is nothing for it to hand a contact to.
        StellurgyBlocks.blockFireControlSensor = new BlockTile(dev.stannismod.stellurgy.tile.sensor.TileFireControlSensor.class,
                GuiHandler.guiId.MODULARNOINV.ordinal()).setUnlocalizedName("fireControlSensor")
                .setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockGuidanceComputer = new BlockTile(TileGuidanceComputer.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("guidanceComputer").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockAdvancedFlightComputer = new dev.stannismod.stellurgy.block.BlockAdvancedFlightComputer(GuiHandler.guiId.MODULARNOINV.ordinal()).setUnlocalizedName("advancedFlightComputer").setCreativeTab(tabAdvRocketry).setHardness(3f);
        // MODULARNOINV, not MODULAR: the console needs the whole panel for its own controls, and a
        // MODULAR window spends y 89..143 on the player's inventory grid - which is exactly where
        // half of this console's buttons used to be drawn, unclickable. NOINV keeps the hotbar, so a
        // crystal can still be dragged into the two slots.
        StellurgyBlocks.blockNavigationComputer = new dev.stannismod.stellurgy.libvulpes.block.BlockTile(dev.stannismod.stellurgy.tile.TileNavigationComputer.class, GuiHandler.guiId.MODULARNOINV.ordinal()).setUnlocalizedName("navigationComputer").setCreativeTab(tabAdvRocketry).setHardness(3f);
        // The hyperdrive family. The two controllers carry tile entities because they have state or
        // are measured; the coil, cell, sink and emitter are structure — what they are worth is
        // decided by how many of them the player welded together, not by anything they hold.
        StellurgyBlocks.blockHyperdriveGenerator = new dev.stannismod.stellurgy.block.BlockShipMachine(dev.stannismod.stellurgy.tile.hyperdrive.TileHyperdriveGenerator.class).setUnlocalizedName("hyperdriveGenerator").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockHyperdriveCoil = new Block(Material.IRON).setUnlocalizedName("hyperdriveCoil").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockJumpFieldEmitter = new dev.stannismod.stellurgy.block.BlockShipMachine(dev.stannismod.stellurgy.tile.hyperdrive.TileJumpFieldEmitter.class).setUnlocalizedName("jumpFieldEmitter").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockJumpCapacitor = new dev.stannismod.stellurgy.block.BlockShipMachine(dev.stannismod.stellurgy.tile.hyperdrive.TileJumpCapacitor.class).setUnlocalizedName("jumpCapacitor").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockJumpCapacitorCell = new Block(Material.IRON).setUnlocalizedName("jumpCapacitorCell").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockJumpHeatSink = new Block(Material.IRON).setUnlocalizedName("jumpHeatSink").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockGravityDampener = new dev.stannismod.stellurgy.block.BlockShipMachine(dev.stannismod.stellurgy.tile.hyperdrive.TileGravityDampener.class).setUnlocalizedName("gravityDampener").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockIntake = new BlockIntake(Material.IRON).setUnlocalizedName("gasIntake").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockDrill = new BlockMiningDrill().setUnlocalizedName("drill").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockLandingFloat = new Block(Material.IRON).setUnlocalizedName("landingfloat").setCreativeTab(tabAdvRocketry).setHardness(1).setResistance(1f);
        StellurgyBlocks.blockServiceMonitor = new RotatableBlock(Material.IRON).setUnlocalizedName("servicemonitor").setCreativeTab(tabAdvRocketry).setHardness(1).setResistance(1f);
        StellurgyBlocks.blockInvHatch = new BlockInvHatch(Material.IRON).setUnlocalizedName("invhatch").setCreativeTab(tabAdvRocketry).setHardness(1).setResistance(1f);
        //Assembly machines
        StellurgyBlocks.blockRocketBuilder = new BlockTileWithMultitooltip(TileRocketAssemblingMachine.class, GuiHandler.guiId.MODULARNOINV.ordinal()).setUnlocalizedName("rocketAssembler").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockStationBuilder = new BlockTileWithMultitooltip(TileStationAssembler.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("stationAssembler").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockDeployableRocketBuilder = new BlockTileWithMultitooltip(TileUnmannedVehicleAssembler.class, GuiHandler.guiId.MODULARNOINV.ordinal()).setUnlocalizedName("deployableRocketAssembler").setCreativeTab(tabAdvRocketry).setHardness(3f);
        //Infrastructure machines
        StellurgyBlocks.blockLoader = new BlockStellurgyHatch(Material.IRON).setUnlocalizedName("loader").setCreativeTab(tabAdvRocketry).setHardness(3f);
        // Big Data Bus Hatch
        StellurgyBlocks.blockDataBusBig = new BlockDataBusBig(Material.IRON).setUnlocalizedName("databusbig").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockFuelingStation = new BlockTileRedstoneEmitter(TileFuelingStation.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("fuelStation").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockMonitoringStation = new BlockTileNeighborUpdate(TileRocketMonitoringStation.class, GuiHandler.guiId.MODULARNOINV.ordinal()).setCreativeTab(tabAdvRocketry).setHardness(3f).setUnlocalizedName("monitoringstation");
        StellurgyBlocks.blockSatelliteControlCenter = new BlockTile(TileSatelliteTerminal.class, GuiHandler.guiId.MODULAR.ordinal()).setCreativeTab(tabAdvRocketry).setHardness(3f).setUnlocalizedName("satelliteMonitor");
        StellurgyBlocks.blockTerraformingTerminal = new BlockTileTerraformer(TileTerraformingTerminal.class, GuiHandler.guiId.MODULAR.ordinal()).setCreativeTab(tabAdvRocketry).setHardness(3f).setUnlocalizedName("terraformingTerminal");
        StellurgyBlocks.blockServiceStation = new BlockTile(TileRocketServiceStation.class, GuiHandler.guiId.MODULAR.ordinal()).setCreativeTab(tabAdvRocketry).setHardness(3f).setUnlocalizedName("serviceStation");


        //Station machines
        StellurgyBlocks.blockWarpShipMonitor = new BlockWarpController(TileWarpController.class, GuiHandler.guiId.MODULARNOINV.ordinal()).setCreativeTab(tabAdvRocketry).setHardness(3f).setUnlocalizedName("stationmonitor");
        StellurgyBlocks.blockOrientationController = new BlockTile(TileStationOrientationController.class, GuiHandler.guiId.MODULAR.ordinal()).setCreativeTab(tabAdvRocketry).setUnlocalizedName("orientationControl").setHardness(3f);
        StellurgyBlocks.blockGravityController = new BlockTileComparatorOverride(TileStationGravityController.class, GuiHandler.guiId.MODULAR.ordinal()).setCreativeTab(tabAdvRocketry).setUnlocalizedName("gravityControl").setHardness(3f);
        StellurgyBlocks.blockAltitudeController = new BlockTileComparatorOverride(TileStationAltitudeController.class, GuiHandler.guiId.MODULAR.ordinal()).setCreativeTab(tabAdvRocketry).setUnlocalizedName("altitudeController").setHardness(3f);
        StellurgyBlocks.blockPlanetSelector = new BlockTile(TilePlanetSelector.class, GuiHandler.guiId.MODULARFULLSCREEN.ordinal()).setUnlocalizedName("planetSelector").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockPlanetHoloSelector = new BlockHalfTile(TileHolographicPlanetSelector.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("planetHoloSelector").setCreativeTab(tabAdvRocketry).setHardness(3f);
        //Oxygen machines
        StellurgyBlocks.blockCO2Scrubber = new BlockTileComparatorOverride(TileCO2Scrubber.class, GuiHandler.guiId.MODULAR.ordinal()).setCreativeTab(tabAdvRocketry).setUnlocalizedName("scrubber").setHardness(3f);
        StellurgyBlocks.blockAirRecirculator = new BlockTile(TileAirRecirculator.class, GuiHandler.guiId.MODULAR.ordinal()).setCreativeTab(tabAdvRocketry).setUnlocalizedName("airRecirculator").setHardness(3f);
        StellurgyBlocks.blockGasSeparator = new dev.stannismod.stellurgy.block.BlockGasSeparator(TileGasSeparator.class, GuiHandler.guiId.MODULAR.ordinal()).setCreativeTab(tabAdvRocketry).setUnlocalizedName("gasSeparator").setHardness(3f);
        StellurgyBlocks.blockLifeSupportPlant = new BlockTile(dev.stannismod.stellurgy.tile.atmosphere.TileLifeSupportPlant.class, GuiHandler.guiId.MODULAR.ordinal()).setCreativeTab(tabAdvRocketry).setUnlocalizedName("lifeSupportPlant").setHardness(3f);
        StellurgyBlocks.blockVentilationDuct = new dev.stannismod.stellurgy.block.BlockVentilationDuct().setCreativeTab(tabAdvRocketry).setUnlocalizedName("ventilationDuct").setHardness(1f);
        StellurgyBlocks.blockJettisonPort = new BlockTile(dev.stannismod.stellurgy.tile.infrastructure.TileJettisonPort.class, GuiHandler.guiId.MODULAR.ordinal()).setCreativeTab(tabAdvRocketry).setUnlocalizedName("jettisonPort").setHardness(3f);
        StellurgyBlocks.blockHeatPipe = new dev.stannismod.stellurgy.block.BlockHeatPipe().setCreativeTab(tabAdvRocketry).setUnlocalizedName("heatPipe").setHardness(1f);
        StellurgyBlocks.blockHeatAccumulator = new dev.stannismod.stellurgy.block.BlockHeatAccumulator().setCreativeTab(tabAdvRocketry).setUnlocalizedName("heatAccumulator").setHardness(3f);
        StellurgyBlocks.blockHeatRadiator = new dev.stannismod.stellurgy.block.BlockHeatRadiator().setCreativeTab(tabAdvRocketry).setUnlocalizedName("heatRadiator").setHardness(1f);
        StellurgyBlocks.blockHeatChiller = new BlockTile(dev.stannismod.stellurgy.tile.heat.TileHeatChiller.class, GuiHandler.guiId.MODULAR.ordinal()).setCreativeTab(tabAdvRocketry).setUnlocalizedName("heatChiller").setHardness(3f);
        StellurgyBlocks.blockHeatDump = new BlockTile(dev.stannismod.stellurgy.tile.heat.TileHeatDump.class, GuiHandler.guiId.MODULAR.ordinal()).setCreativeTab(tabAdvRocketry).setUnlocalizedName("heatDump").setHardness(3f);
        StellurgyBlocks.blockHeatIntakeDuct = new dev.stannismod.stellurgy.block.BlockHeatIntakeDuct().setCreativeTab(tabAdvRocketry).setUnlocalizedName("heatIntakeDuct").setHardness(1f);
        StellurgyBlocks.blockOxygenVent = new BlockTile(TileOxygenVent.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("oxygenVent").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockOxygenCharger = new BlockHalfTile(TileGasChargePad.class, GuiHandler.guiId.MODULAR.ordinal()).setUnlocalizedName("oxygenCharger").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockOxygenDetection = new BlockRedstoneEmitter(Material.IRON, "stellurgy:atmosphereDetector_active").setUnlocalizedName("atmosphereDetector").setHardness(3f).setCreativeTab(tabAdvRocketry);
        StellurgyBlocks.blockPipeSealer = new BlockSeal(Material.IRON).setUnlocalizedName("pipeSeal").setCreativeTab(tabAdvRocketry).setHardness(0.5f);
        StellurgyBlocks.blockAirLock = new BlockDoor2(Material.IRON).setUnlocalizedName("smallAirlockDoor").setHardness(3f).setResistance(8f);
        //Light sources
        StellurgyBlocks.blockUnlitTorch = new BlockTorchUnlit().setHardness(0.0F).setUnlocalizedName("unlittorch");
        StellurgyBlocks.blockThermiteTorch = new BlockThermiteTorch().setUnlocalizedName("thermiteTorch").setCreativeTab(tabAdvRocketry).setHardness(0.1f).setLightLevel(1f);
        StellurgyBlocks.blockCircleLight = new Block(Material.IRON).setUnlocalizedName("circleLight").setCreativeTab(tabAdvRocketry).setHardness(2f).setLightLevel(1f);
        StellurgyBlocks.blockLightSource = new BlockLightSource();
        StellurgyBlocks.blockRocketFire = new BlockRocketFire();
        //Worldgen
        StellurgyBlocks.blockMoonTurf = new BlockRegolith().setMapColor(MapColor.SNOW).setHardness(0.5F).setUnlocalizedName("turf").setCreativeTab(tabAdvRocketry);
        StellurgyBlocks.blockMoonTurfDark = new BlockRegolith().setMapColor(MapColor.CLAY).setHardness(0.5F).setUnlocalizedName("turfDark").setCreativeTab(tabAdvRocketry);
        StellurgyBlocks.blockHotTurf = new BlockRegolith().setMapColor(MapColor.NETHERRACK).setHardness(0.5F).setUnlocalizedName("hotDryturf").setCreativeTab(tabAdvRocketry);
        StellurgyBlocks.blockBasalt = new Block(Material.ROCK).setUnlocalizedName("basalt").setCreativeTab(tabAdvRocketry).setHardness(5f).setResistance(15f);
        StellurgyBlocks.blocksGeode = new Block(MaterialGeode.geode).setUnlocalizedName("geode").setCreativeTab(tabAdvRocketry).setHardness(6f).setResistance(2000F);
        StellurgyBlocks.blocksGeode.setHarvestLevel("jackhammer", 2);
        StellurgyBlocks.blockCrystal = new BlockCrystal().setUnlocalizedName("crystal").setCreativeTab(tabAdvRocketry).setHardness(2f);
        StellurgyBlocks.blockVitrifiedSand = new Block(Material.SAND).setUnlocalizedName("vitrifiedSand").setCreativeTab(tabAdvRocketry).setHardness(0.5F);
        StellurgyBlocks.blockCharcoalLog = new BlockCharcoalLog().setUnlocalizedName("charcoallog").setCreativeTab(tabAdvRocketry);
        StellurgyBlocks.blockElectricMushroom = new BlockElectricMushroom().setUnlocalizedName("electricMushroom").setCreativeTab(tabAdvRocketry).setHardness(0.0F);
        StellurgyBlocks.blockLightwoodWood = new BlockLightwoodWood().setUnlocalizedName("lightwoodlog").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.sblockLightwoodLeaves = new BlockLightwoodLeaves().setUnlocalizedName("lightwoodleaves").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockLightwoodSapling = new BlockLightwoodSapling().setUnlocalizedName("lightwoodsapling").setCreativeTab(tabAdvRocketry).setHardness(3f);
        StellurgyBlocks.blockLightwoodPlanks = new BlockLightwoodPlanks().setUnlocalizedName("lightwoodplanks").setCreativeTab(tabAdvRocketry).setHardness(3f);


        //Fluid definitions
        final ResourceLocation notFlowing = new ResourceLocation("stellurgy:blocks/fluid/oxygen_still");
        final ResourceLocation flowing = new ResourceLocation("stellurgy:blocks/fluid/oxygen_flow");
        StellurgyFluids.fluidOxygen = new Fluid("oxygen", notFlowing, flowing).setUnlocalizedName("oxygen").setGaseous(true).setDensity(-1000).setViscosity(1000).setColor(0xFF6CE2FF);
        StellurgyFluids.fluidHydrogen = new Fluid("hydrogen", notFlowing, flowing).setUnlocalizedName("hydrogen").setGaseous(true).setDensity(-1000).setViscosity(1000).setColor(0xFFDBC1C1);
        StellurgyFluids.fluidNitrogen = new Fluid("nitrogen", notFlowing, flowing).setUnlocalizedName("nitrogen").setGaseous(true).setDensity(-1000).setViscosity(1000).setColor(0xFFDFE5FE);
        // Name matches GregTechCEu's CarbonDioxide material fluid so the two unify by registry name,
        // the same way oxygen already does -- whichever mod registers first wins and the other falls
        // back to it below. snake_case here is GT's convention, not AR's; it is load-bearing.
        StellurgyFluids.fluidCarbonDioxide = new Fluid("carbon_dioxide", notFlowing, flowing).setUnlocalizedName("carbon_dioxide").setGaseous(true).setDensity(-1000).setViscosity(1000).setColor(0xFFA8A8A8);
        StellurgyFluids.fluidRocketFuel = new Fluid("rocketFuel", notFlowing, flowing).setUnlocalizedName("rocketFuel").setGaseous(false).setLuminosity(2).setDensity(800).setViscosity(1500).setColor(0xFFE5D884);
        StellurgyFluids.fluidEnrichedLava = new Fluid("enrichedLava", new ResourceLocation("stellurgy:blocks/fluid/lava_still"), new ResourceLocation("stellurgy:blocks/fluid/lava_flow")).setUnlocalizedName("enrichedLava").setLuminosity(15).setDensity(3000).setViscosity(6000).setTemperature(1300).setColor(0xFFFFFFFF);

        //Fluid Registration
        if (!FluidRegistry.registerFluid(StellurgyFluids.fluidOxygen))
            StellurgyFluids.fluidOxygen = FluidRegistry.getFluid("oxygen");
        if (!FluidRegistry.registerFluid(StellurgyFluids.fluidHydrogen))
            StellurgyFluids.fluidHydrogen = FluidRegistry.getFluid("hydrogen");
        if (!FluidRegistry.registerFluid(StellurgyFluids.fluidNitrogen))
            StellurgyFluids.fluidNitrogen = FluidRegistry.getFluid("nitrogen");
        if (!FluidRegistry.registerFluid(StellurgyFluids.fluidCarbonDioxide))
            StellurgyFluids.fluidCarbonDioxide = FluidRegistry.getFluid("carbon_dioxide");
        if (!FluidRegistry.registerFluid(StellurgyFluids.fluidRocketFuel))
            StellurgyFluids.fluidRocketFuel = FluidRegistry.getFluid("rocketFuel");
        if (!FluidRegistry.registerFluid(StellurgyFluids.fluidEnrichedLava))
            StellurgyFluids.fluidEnrichedLava = FluidRegistry.getFluid("enrichedLava");

        // For all intents and purposes, they're the same -- Mekanism compat
        FluidUtils.addFluidMapping(StellurgyFluids.fluidOxygen, "liquidoxygen");
        FluidUtils.addFluidMapping(StellurgyFluids.fluidOxygen, "oxynitrogenmix");
        FluidUtils.addFluidMapping(StellurgyFluids.fluidHydrogen, "liquidhydrogen");

        StellurgyBlocks.blockOxygenFluid = new BlockFluid(StellurgyFluids.fluidOxygen, Material.WATER).setUnlocalizedName("oxygenFluidBlock").setCreativeTab(CreativeTabs.MISC);
        StellurgyBlocks.blockHydrogenFluid = new BlockFluid(StellurgyFluids.fluidHydrogen, Material.WATER).setUnlocalizedName("hydrogenFluidBlock").setCreativeTab(CreativeTabs.MISC);
        StellurgyBlocks.blockNitrogenFluid = new BlockFluid(StellurgyFluids.fluidNitrogen, Material.WATER).setUnlocalizedName("nitrogenFluidBlock").setCreativeTab(CreativeTabs.MISC);
        StellurgyBlocks.blockCarbonDioxideFluid = new BlockFluid(StellurgyFluids.fluidCarbonDioxide, Material.WATER).setUnlocalizedName("carbonDioxideFluidBlock").setCreativeTab(CreativeTabs.MISC);
        StellurgyBlocks.blockFuelFluid = new BlockFluid(StellurgyFluids.fluidRocketFuel, new MaterialLiquid(MapColor.YELLOW)).setUnlocalizedName("rocketFuelBlock").setCreativeTab(CreativeTabs.MISC);
        StellurgyBlocks.blockEnrichedLavaFluid = new BlockEnrichedLava(StellurgyFluids.fluidEnrichedLava, Material.LAVA).setUnlocalizedName("enrichedLavaBlock").setCreativeTab(CreativeTabs.MISC).setLightLevel(15);

        //Fluids
        FluidRegistry.addBucketForFluid(StellurgyFluids.fluidHydrogen);
        FluidRegistry.addBucketForFluid(StellurgyFluids.fluidNitrogen);
        FluidRegistry.addBucketForFluid(StellurgyFluids.fluidOxygen);
        FluidRegistry.addBucketForFluid(StellurgyFluids.fluidCarbonDioxide);
        FluidRegistry.addBucketForFluid(StellurgyFluids.fluidRocketFuel);
        FluidRegistry.addBucketForFluid(StellurgyFluids.fluidEnrichedLava);

        //Machines
        //Machine parts
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockConcrete.setRegistryName("concrete"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockBlastBrick.setRegistryName("blastbrick"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockStructureTower.setRegistryName("structureTower"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockLens.setRegistryName("blockLens"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockMirrorPlatingAluminium.setRegistryName("mirrorPlatingAluminium"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockMirrorPlatingSilver.setRegistryName("mirrorPlatingSilver"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockMirrorPlatingGold.setRegistryName("mirrorPlatingGold"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockReactivePlate.setRegistryName("reactivePlate"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockReactiveBlock.setRegistryName("reactiveBlock"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockSolarPanel.setRegistryName("solarPanel"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockSolarArrayPanel.setRegistryName("solararraypanel"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockQuartzCrucible.setRegistryName("quartzcrucible"), null, false);
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockSawBlade.setRegistryName("sawBlade"));
        //Singleblock machines
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockPlatePress.setRegistryName("platepress"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockPlatePressHead.setRegistryName("platepress_head"), null, false);
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockVacuumLaser.setRegistryName("vacuumLaser"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockPump.setRegistryName("blockPump"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockSuitWorkStation.setRegistryName("suitWorkStation"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockPressureTank.setRegistryName("liquidTank"), ItemBlockFluidTank.class, true);
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockSolarGenerator.setRegistryName("solarGenerator"));
        //FROZEN save id — the misspelling is deliberate, do not "fix" it. Shipped since 2018
        //(48610953), so existing worlds hold stellurgy:wirelesstransciever in their
        //level.dat registry snapshot; respelling it drops every placed transceiver and every
        //ItemBlock in storage. Blockstate, block/item models and the recipe result are keyed
        //off this string as well and must move with it.
        //TODO(0.1.0): rename to wirelessTransceiver, but only together with a
        //RegistryEvent.MissingMappings remap of the old name. See CHANGELOG.
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockTransciever.setRegistryName("wirelessTransciever"));
        //Multiblock machines
        //T1 processing
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockArcFurnace.setRegistryName("arcfurnace"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockRollingMachine.setRegistryName("rollingMachine"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockLathe.setRegistryName("lathe"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockCrystallizer.setRegistryName("crystallizer"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockCuttingMachine.setRegistryName("cuttingMachine"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockPrecisionAssembler.setRegistryName("precisionassemblingmachine"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockElectrolyser.setRegistryName("electrolyser"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockChemicalReactor.setRegistryName("chemicalReactor"));
        //T2 processing
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockPrecisionLaserEngraver.setRegistryName("precisionlaseretcher"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockObservatory.setRegistryName("observatory"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockPlanetAnalyser.setRegistryName("planetAnalyser"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockCentrifuge.setRegistryName("centrifuge"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockSatelliteBuilder.setRegistryName("satelliteBuilder"));
       
        //Energy
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockBlackHoleGenerator.setRegistryName("blackholegenerator"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockMicrowaveReciever.setRegistryName("microwaveReciever"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockSolarArray.setRegistryName("solararray"));
        //Aux/huge
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockBeacon.setRegistryName("beacon"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockBiomeScanner.setRegistryName("biomeScanner"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockRailgun.setRegistryName("railgun"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockSpaceElevatorController.setRegistryName("spaceElevatorController"));
        //Configurable stuff
        if (StellurgyConfiguration.getCurrentConfig().enableTerraforming)
            LibVulpesBlocks.registerBlock(StellurgyBlocks.blockAtmosphereTerraformer.setRegistryName("terraformer"));
        if (dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig().enableGravityController)
            LibVulpesBlocks.registerBlock(StellurgyBlocks.blockGravityMachine.setRegistryName("gravityMachine"));
        if (dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig().enableLaserDrill)
            LibVulpesBlocks.registerBlock(StellurgyBlocks.blockSpaceLaser.setRegistryName("spaceLaser"));

        //Docking blocks
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockLaunchpad.setRegistryName("launchpad"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockLandingPad.setRegistryName("landingPad"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockDockingPort.setRegistryName("stationMarker"));
        //Rocket blocks
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockGenericSeat.setRegistryName("seat"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockPilotSeat.setRegistryName("pilotSeat"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockEngine.setRegistryName("rocketmotor"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockBipropellantEngine.setRegistryName("bipropellantrocketmotor"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockAdvEngine.setRegistryName("advRocketmotor"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockAdvBipropellantEngine.setRegistryName("advbipropellantRocketmotor"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockNuclearEngine.setRegistryName("nuclearrocketmotor"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockFuelTank.setRegistryName("fuelTank"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockBipropellantFuelTank.setRegistryName("bipropellantfueltank"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockOxidizerFuelTank.setRegistryName("oxidizerfueltank"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockNuclearFuelTank.setRegistryName("nuclearfueltank"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockNuclearCore.setRegistryName("nuclearcore"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockTurret.setRegistryName("turret"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockWeaponConsole.setRegistryName("weaponConsole"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockFireControlSensor.setRegistryName("fireControlSensor"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockGunBarrel.setRegistryName("gunBarrel"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockGunAmmoFeed.setRegistryName("gunAmmoFeed"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockGunBeamEmitter.setRegistryName("gunBeamEmitter"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockGunCooling.setRegistryName("gunCooling"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockGuidanceComputer.setRegistryName("guidanceComputer"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockAdvancedFlightComputer.setRegistryName("advancedFlightComputer"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockNavigationComputer.setRegistryName("navigationComputer"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockHyperdriveGenerator.setRegistryName("hyperdriveGenerator"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockHyperdriveCoil.setRegistryName("hyperdriveCoil"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockJumpFieldEmitter.setRegistryName("jumpFieldEmitter"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockJumpCapacitor.setRegistryName("jumpCapacitor"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockJumpCapacitorCell.setRegistryName("jumpCapacitorCell"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockJumpHeatSink.setRegistryName("jumpHeatSink"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockGravityDampener.setRegistryName("gravityDampener"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockIntake.setRegistryName("intake"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockDrill.setRegistryName("drill"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockLandingFloat.setRegistryName("landingfloat"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockServiceMonitor.setRegistryName("servicemonitor"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockInvHatch.setRegistryName("invhatch"));
        //Assembly machines
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockRocketBuilder.setRegistryName("rocketBuilder"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockStationBuilder.setRegistryName("stationBuilder"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockDeployableRocketBuilder.setRegistryName("deployableRocketBuilder"));
        //Infrastructure machines
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockLoader.setRegistryName("loader"), ItemBlockMeta.class, false);
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockDataBusBig.setRegistryName("databusbig"), dev.stannismod.stellurgy.item.ItemBlockDataBusBig.class, true);
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockServiceStation.setRegistryName("serviceStation"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockFuelingStation.setRegistryName("fuelingStation"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockMonitoringStation.setRegistryName("monitoringStation"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockSatelliteControlCenter.setRegistryName("satelliteControlCenter"));
        if (StellurgyConfiguration.getCurrentConfig().enableOrbitalRegistry)
            LibVulpesBlocks.registerBlock(StellurgyBlocks.blockOrbitalRegistry.setRegistryName("orbitalRegistry"));

        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockTerraformingTerminal.setRegistryName("terraformingTerminal"));
        //Station machines
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockWarpShipMonitor.setRegistryName("warpMonitor"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockOrientationController.setRegistryName("orientationController"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockGravityController.setRegistryName("gravityController"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockAltitudeController.setRegistryName("altitudeController"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockPlanetSelector.setRegistryName("planetSelector"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockPlanetHoloSelector.setRegistryName("planetHoloSelector"));
        //Oxygen machines
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockCO2Scrubber.setRegistryName("oxygenScrubber"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockAirRecirculator.setRegistryName("airRecirculator"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockGasSeparator.setRegistryName("gasSeparator"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockLifeSupportPlant.setRegistryName("lifeSupportPlant"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockVentilationDuct.setRegistryName("ventilationDuct"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockJettisonPort.setRegistryName("jettisonPort"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockHeatPipe.setRegistryName("heatPipe"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockHeatAccumulator.setRegistryName("heatAccumulator"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockHeatRadiator.setRegistryName("heatRadiator"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockHeatChiller.setRegistryName("heatChiller"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockHeatDump.setRegistryName("heatDump"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockHeatIntakeDuct.setRegistryName("heatIntakeDuct"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockOxygenVent.setRegistryName("oxygenVent"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockOxygenCharger.setRegistryName("oxygenCharger"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockOxygenDetection.setRegistryName("oxygenDetection"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockPipeSealer.setRegistryName("pipeSealer"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockAirLock.setRegistryName("airlock_door"));
        //Light sources
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockUnlitTorch.setRegistryName("unlitTorch"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockThermiteTorch.setRegistryName("thermiteTorch"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockCircleLight.setRegistryName("circleLight"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockLightSource.setRegistryName("lightSource"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockRocketFire.setRegistryName("rocketfire"), null, false);
        //Worldgen
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockMoonTurf.setRegistryName("moonTurf"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockMoonTurfDark.setRegistryName("moonTurf_dark"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockHotTurf.setRegistryName("hotTurf"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockBasalt.setRegistryName("basalt"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blocksGeode.setRegistryName("geode"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockCrystal.setRegistryName("crystal"), ItemBlockCrystal.class, true);
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockVitrifiedSand.setRegistryName("vitrifiedSand"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockCharcoalLog.setRegistryName("charcoalLog"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockElectricMushroom.setRegistryName("electricMushroom"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockLightwoodWood.setRegistryName("alienWood"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.sblockLightwoodLeaves.setRegistryName("alienLeaves"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockLightwoodSapling.setRegistryName("alienSapling"));
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockLightwoodPlanks.setRegistryName("planks"));
        //Fluids
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockOxygenFluid.setRegistryName("oxygenFluid"), null, false);
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockHydrogenFluid.setRegistryName("hydrogenFluid"), null, false);
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockNitrogenFluid.setRegistryName("nitrogenFluid"), null, false);
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockCarbonDioxideFluid.setRegistryName("carbonDioxideFluid"), null, false);
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockFuelFluid.setRegistryName("rocketFuel"), null, false);
        LibVulpesBlocks.registerBlock(StellurgyBlocks.blockEnrichedLavaFluid.setRegistryName("enrichedLavaFluid"), null, false);


        //Register Allowed Products
        materialRegistry.registerMaterial(new dev.stannismod.stellurgy.libvulpes.api.material.Material("TitaniumAluminide", "pickaxe", 1, 0xaec2de, AllowedProducts.getProductByName("PLATE").getFlagValue() | AllowedProducts.getProductByName("INGOT").getFlagValue() | AllowedProducts.getProductByName("NUGGET").getFlagValue() | AllowedProducts.getProductByName("DUST").getFlagValue() | AllowedProducts.getProductByName("STICK").getFlagValue() | AllowedProducts.getProductByName("BLOCK").getFlagValue() | AllowedProducts.getProductByName("GEAR").getFlagValue() | AllowedProducts.getProductByName("SHEET").getFlagValue(), false));
        materialRegistry.registerMaterial(new dev.stannismod.stellurgy.libvulpes.api.material.Material("TitaniumIridium", "pickaxe", 1, 0xd7dfe4, AllowedProducts.getProductByName("PLATE").getFlagValue() | AllowedProducts.getProductByName("INGOT").getFlagValue() | AllowedProducts.getProductByName("NUGGET").getFlagValue() | AllowedProducts.getProductByName("DUST").getFlagValue() | AllowedProducts.getProductByName("STICK").getFlagValue() | AllowedProducts.getProductByName("BLOCK").getFlagValue() | AllowedProducts.getProductByName("GEAR").getFlagValue() | AllowedProducts.getProductByName("SHEET").getFlagValue(), false));

        materialRegistry.registerOres(libVulpes.tabLibVulpesOres);

        //OreDict stuff
        OreDictionary.registerOre("turfMoon", new ItemStack(StellurgyBlocks.blockMoonTurf));
        OreDictionary.registerOre("turfMoon", new ItemStack(StellurgyBlocks.blockMoonTurfDark));
        OreDictionary.registerOre("logWood", new ItemStack(StellurgyBlocks.blockLightwoodWood));
        OreDictionary.registerOre("plankWood", new ItemStack(StellurgyBlocks.blockLightwoodPlanks));
        OreDictionary.registerOre("treeLeaves", new ItemStack(StellurgyBlocks.sblockLightwoodLeaves));
        OreDictionary.registerOre("treeSapling", new ItemStack(StellurgyBlocks.blockLightwoodSapling));
        OreDictionary.registerOre("concrete", new ItemStack(StellurgyBlocks.blockConcrete));
        OreDictionary.registerOre("casingCentrifuge", new ItemStack(LibVulpesBlocks.blockAdvStructureBlock));
    }

    @SideOnly(Side.CLIENT)
    @SubscribeEvent
    public void registerModels(ModelRegistryEvent event) {
        proxy.preInitItems();
        proxy.preInitBlocks();
    }

    @SubscribeEvent
    public void registerRecipes(RegistryEvent<IRecipe> evt) {
        GameRegistry.addSmelting(MaterialRegistry.getMaterialFromName("Dilithium").getProduct(AllowedProducts.getProductByName("ORE")), MaterialRegistry.getMaterialFromName("Dilithium").getProduct(AllowedProducts.getProductByName("DUST")), 0);

        //Register the machine recipes
        machineRecipes.registerAllMachineRecipes();
    }

    @EventHandler
    public void load(FMLInitializationEvent event) {
        libVulpes.init(event);
        StellurgyAdvancements.register();
        proxy.init();

        MinecraftForge.EVENT_BUS.register(new WirelessNetworkRegistryHandler());
        //Register Alloys
        MaterialRegistry.registerMixedMaterial(new MixedMaterial(TileElectricArcFurnace.class, "oreRutile", new ItemStack[]{MaterialRegistry.getMaterialFromName("Titanium").getProduct(AllowedProducts.getProductByName("INGOT"))}));


        // EXACTLY ONE GUI handler may be registered on this container: Forge keeps one per mod in
        // NetworkRegistry, so a second call silently OVERWRITES the first - it does not chain. That
        // overwrite is what once broke the station-chip button re-open, when a libVulpes handler
        // registered here was replaced by Stellurgy's and the MODULAR* ids resolved to null.
        //
        // So the chain is built explicitly instead: the router sends offset ids
        // (>= AffsGuiRouter.AFFS_GUI_BASE) to the vendored shield system's handler and everything
        // else to Stellurgy's, which in turn delegates every non-Stellurgy id on to libVulpes. Add a handler by
        // extending this chain, never by calling registerGuiHandler again.
        NetworkRegistry.INSTANCE.registerGuiHandler(this, new dev.stannismod.stellurgy.integration.affs.AffsGuiRouter(
                new dev.stannismod.stellurgy.affs.gui.GuiHandler(), new dev.stannismod.stellurgy.inventory.GuiHandler()));
        planetWorldType = new WorldTypePlanetGen("PlanetCold");
        spaceWorldType = new WorldTypeSpace("Space");

        //Biomes --------------------------------------------------------------------------------------

        String[] biomeBlackList = config.getStringList("BlacklistedBiomes", "Planet",
                new String[]{
                        Biomes.SKY.getRegistryName().toString(),
                        Biomes.HELL.getRegistryName().toString(),
                        Biomes.VOID.getRegistryName().toString(),
                },
                "List of Biomes to be blacklisted from spawning as BiomeIds during terraforming");
        String[] biomeHighPressure = config.getStringList("HighPressureBiomes", "Planet", new String[]{StellurgyBiomes.swampDeepBiome.getRegistryName().toString(), StellurgyBiomes.stormLandsBiome.getRegistryName().toString()}, "Biomes that only spawn on worlds with pressures over 125, will override blacklist.  Defaults: StormLands, DeepSwamp");
        String[] biomeSingle = config.getStringList("SingleBiomes", "Planet", new String[]{StellurgyBiomes.volcanicBarren.getRegistryName().toString(), StellurgyBiomes.swampDeepBiome.getRegistryName().toString(), StellurgyBiomes.crystalChasms.getRegistryName().toString(), StellurgyBiomes.alienForest.getRegistryName().toString(), Biomes.DESERT_HILLS.getRegistryName().toString(),
                Biomes.MUSHROOM_ISLAND.getRegistryName().toString(), Biomes.EXTREME_HILLS.getRegistryName().toString(), Biomes.ICE_PLAINS.getRegistryName().toString()}, "Some worlds have a chance of spawning single biomes contained in this list.  Defaults: deepSwamp, crystalChasms, alienForest, desert hills, mushroom island, extreme hills, ice plains");

        config.save();

        //Prevent these biomes from spawning normally
        StellurgyBiomes.instance.registerBlackListBiome(StellurgyBiomes.moonBiome);
        StellurgyBiomes.instance.registerBlackListBiome(StellurgyBiomes.moonBiomeDark);
        StellurgyBiomes.instance.registerBlackListBiome(StellurgyBiomes.hotDryBiome);
        StellurgyBiomes.instance.registerBlackListBiome(StellurgyBiomes.spaceBiome);
        StellurgyBiomes.instance.registerBlackListBiome(StellurgyBiomes.volcanic);

        //Read BlackList from config and register Blacklisted biomes
        for (String string : biomeBlackList) {
            try {
                Biome biome = StellurgyBiomes.getBiome(string);

                if (biome == null)
                    logger.warn(String.format("Error blackListing biome  \"%s\", a biome with that ID does not exist!", string));
                else
                    StellurgyBiomes.instance.registerBlackListBiome(biome);
            } catch (NumberFormatException e) {
                logger.warn("Error blackListing \"" + string + "\".  It is not a valid number or Biome ResourceLocation");
            }
        }

        if (dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig().blackListAllVanillaBiomes) {
            StellurgyBiomes.instance.blackListVanillaBiomes();
        }


        //Read and Register High Pressure biomes from config
        for (String string : biomeHighPressure) {
            try {
                Biome biome = StellurgyBiomes.getBiome(string);

                if (biome == null)
                    logger.warn(String.format("Error registering high pressure biome \"%s\", a biome with that ID does not exist!", string));
                else
                    StellurgyBiomes.instance.registerHighPressureBiome(biome);
            } catch (NumberFormatException e) {
                logger.warn("Error registering high pressure biome \"" + string + "\".  It is not a valid number or Biome ResourceLocation");
            }
        }

        //Read and Register Single biomes from config
        for (String string : biomeSingle) {
            try {
                Biome biome = StellurgyBiomes.getBiome(string);

                if (biome == null)
                    logger.warn(String.format("Error registering single biome \"%s\", a biome with that ID does not exist!", string));
                else
                    StellurgyBiomes.instance.registerSingleBiome(biome);
            } catch (NumberFormatException e) {
                logger.warn("Error registering single biome \"" + string + "\".  It is not a valid number or Biome ResourceLocation");
            }
        }


        //Data mapping 'D'

        List<BlockMeta> list = new LinkedList<>();
        list.add(new BlockMeta(StellurgyBlocks.blockLoader, 0));
        list.add(new BlockMeta(StellurgyBlocks.blockLoader, 8));
        list.add(new BlockMeta(StellurgyBlocks.blockDataBusBig, 0));
        TileMultiBlock.addMapping('D', list);

        machineRecipes.createAutoGennedRecipes(modProducts);

        valkyrienSkies.init(event);
        affs.init(event);
    }


    @EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        libVulpes.postInit(event);

        if (weights != null) {
            throw new IllegalStateException("the weight table is written once per process");
        }
        weights = new WeightEngine("config/advRocketry/weights.json");

        CapabilitySpaceArmor.register();
        // The player's own bindings: one home for what this mod holds on him, attached to the
        // player and written with him. Registered beside its siblings; unlike them its storage does
        // real work, because a player is not a host that persists its own NBT.
        dev.stannismod.stellurgy.player.CapabilityPlayerBindings.register();
        dev.stannismod.stellurgy.api.capability.CapabilityWear.register();
        dev.stannismod.stellurgy.api.capability.CapabilityDamageAware.register();
        dev.stannismod.stellurgy.api.capability.CapabilityHeatEmitter.register();
        dev.stannismod.stellurgy.api.capability.CapabilityHeatPump.register();
        dev.stannismod.stellurgy.api.capability.CapabilityHeatSink.register();
        //Need to raise the Max Entity Radius to allow player interaction with rockets
        World.MAX_ENTITY_RADIUS = 20;

        //Register multiblock items with the projector
        //Basic processing machines
        ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileElectricArcFurnace(), (BlockTile) StellurgyBlocks.blockArcFurnace);
        ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileRollingMachine(), (BlockTile) StellurgyBlocks.blockRollingMachine);
        ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileLathe(), (BlockTile) StellurgyBlocks.blockLathe);
        ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileCrystallizer(), (BlockTile) StellurgyBlocks.blockCrystallizer);
        ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileCuttingMachine(), (BlockTile) StellurgyBlocks.blockCuttingMachine);
        ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TilePrecisionAssembler(), (BlockTile) StellurgyBlocks.blockPrecisionAssembler);
        ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileElectrolyser(), (BlockTile) StellurgyBlocks.blockElectrolyser);
        ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileChemicalReactor(), (BlockTile) StellurgyBlocks.blockChemicalReactor);
        //T2 processing machines
        ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TilePrecisionLaserEtcher(), (BlockTile) StellurgyBlocks.blockPrecisionLaserEngraver);
        ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileObservatory(), (BlockTile) StellurgyBlocks.blockObservatory);
        ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileAstrobodyDataProcessor(), (BlockTile) StellurgyBlocks.blockPlanetAnalyser);
        ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileCentrifuge(), (BlockTile) StellurgyBlocks.blockCentrifuge);
        ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileSatelliteBuilder(), (BlockTile) StellurgyBlocks.blockSatelliteBuilder);
        //Power generation
        ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileBlackHoleGenerator(), (BlockTile) StellurgyBlocks.blockBlackHoleGenerator);
        ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileMicrowaveReciever(), (BlockTile) StellurgyBlocks.blockMicrowaveReciever);
        ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileSolarArray(), (BlockTile) StellurgyBlocks.blockSolarArray);
        //Auxillary machines
        ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileBeacon(), (BlockTile) StellurgyBlocks.blockBeacon);
        ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileBiomeScanner(), (BlockTile) StellurgyBlocks.blockBiomeScanner);
        ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileRailgun(), (BlockTile) StellurgyBlocks.blockRailgun);
        ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileSpaceElevator(), (BlockTile) StellurgyBlocks.blockSpaceElevatorController);
        //Config-controlled machines
        if (StellurgyConfiguration.getCurrentConfig().enableTerraforming)
            ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileAtmosphereTerraformer(), (BlockTile) StellurgyBlocks.blockAtmosphereTerraformer);
        if (dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig().enableGravityController)
            ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileAreaGravityController(), (BlockTile) StellurgyBlocks.blockGravityMachine);
        if (dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig().enableLaserDrill)
            ((ItemProjector) LibVulpesItems.itemHoloProjector).registerMachine(new TileOrbitalLaserDrill(), (BlockTile) StellurgyBlocks.blockSpaceLaser);

        proxy.registerEventHandlers();
        proxy.registerKeyBindings();
        //TODO: debug
        //ClientCommandHandler.instance.registerCommand(new Debugger());

        PlanetEventHandler handle = new PlanetEventHandler();
        MinecraftForge.EVENT_BUS.register(handle);
        MinecraftForge.ORE_GEN_BUS.register(handle);

        // Async weather fix
        MinecraftForge.EVENT_BUS.register(new EntityEventHandler());
        MinecraftForge.EVENT_BUS.register(new dev.stannismod.stellurgy.subsystem.heat.HotSlugPhysics());
        // Re-seat a returning player on the ship deck he logged out on: being aboard a ship
        // survives a relog, at any ship attitude.
        // Safe without VS on the classpath: every ship call inside goes through the
        // VSIntegration seam, which no-ops when the physics mod is absent.
        MinecraftForge.EVENT_BUS.register(new dev.stannismod.stellurgy.integration.vs.DeckHold());
        // Async weather info injection
        MinecraftForge.EVENT_BUS.register(new dev.stannismod.stellurgy.world.weather.PlanetWeatherEventHandler());
        // Acid rain damage on planets flagged acidicRain
        MinecraftForge.EVENT_BUS.register(new dev.stannismod.stellurgy.event.AcidRainHandler());
        // Forget a block's damage record when a player breaks or replaces that block
        MinecraftForge.EVENT_BUS.register(new dev.stannismod.stellurgy.damage.DamageInvalidationHandler());

        WirelessDataTickHandler wirelessTickHandler = new WirelessDataTickHandler();
        MinecraftForge.EVENT_BUS.register(wirelessTickHandler);

        InputSyncHandler inputSync = new InputSyncHandler();
        MinecraftForge.EVENT_BUS.register(inputSync);

        MinecraftForge.EVENT_BUS.register(new MapGenLander());
        instance.installGravityManager(new GravityHandler());

        // Compat stuff
        if (Loader.isModLoaded("galacticraftcore") && dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig().overrideGCAir) {
            GalacticCraftHandler eventHandler = new GalacticCraftHandler();
            MinecraftForge.EVENT_BUS.register(eventHandler);
            if (event.getSide().isClient())
                FMLCommonHandler.instance().bus().register(eventHandler);
        }
        CompatibilityMgr.isSpongeInstalled = Loader.isModLoaded("sponge");
        // Asked here, beside the other one, because this is where the loader's answer becomes
        // available and because two "is that mod here" questions asked in two places drift apart.
        // Nothing reads it yet — see the field, which says why it is kept anyway.
        CompatibilityMgr.isGregtechInstalled = Loader.isModLoaded("gregtech");
        VSIntegration.init();
        // End compat stuff

        MinecraftForge.EVENT_BUS.register(dev.stannismod.stellurgy.stations.SpaceObjectManagerEvents.class);
        // Keeps /time off the worlds whose skip is locked. Registered unconditionally: it stands
        // aside the moment no loaded world is locked, so the default-everything case pays nothing.
        MinecraftForge.EVENT_BUS.register(new dev.stannismod.stellurgy.world.TimeCommandGuard());
        // Movable-ship space subsystem GC ticker (idle unless a server-start builds the controller).
        MinecraftForge.EVENT_BUS.register(new dev.stannismod.stellurgy.space.SpaceSubsystemEvents());
        // Login restore (a returning player goes back to his ship, not to a stale pool slot) and the
        // cell-divergence hook. Independent of the controller so it stays quiet while it is down.
        // The two binding owners are KEPT, not just registered: returning a player to the plain
        // world is a direct call on every subsystem that holds something of his, and a caller that
        // had to rediscover these instances would be reaching for a bus instead — which is what
        // this replaced, and which could not say in what order the five releases ran, whether they
        // all ran, or what a thrown one meant.
        dev.stannismod.stellurgy.space.SpaceEventHandler spaceEvents =
                new dev.stannismod.stellurgy.space.SpaceEventHandler();
        MinecraftForge.EVENT_BUS.register(spaceEvents);
        // Hyperspace is a void with ships in it and nothing else: leaving your ship out there is
        // fatal. Idle on every tick that has no hyperspace world and no player in it.
        dev.stannismod.stellurgy.space.HyperspaceVoid hyperspaceVoid =
                new dev.stannismod.stellurgy.space.HyperspaceVoid();
        MinecraftForge.EVENT_BUS.register(hyperspaceVoid);
        playerRelease = new dev.stannismod.stellurgy.player.PlayerRelease(
                spaceEvents, hyperspaceVoid);
        MinecraftForge.EVENT_BUS.register(ServerStateEvents.class);

        GameRegistry.registerWorldGenerator(new OreGenerator(), 100);

        ForgeChunkManager.setForcedChunkLoadingCallback(instance, new WorldEvents());


        //Register mixed material's recipes
        for (MixedMaterial material : MaterialRegistry.getMixedMaterialList()) {
            RecipesMachine.getInstance().addRecipe(material.getMachine(), material.getProducts(), 100, 10, material.getInput());
        }

        //Register space dimension
        net.minecraftforge.common.DimensionManager.registerDimension(dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig().spaceDimId, DimensionManager.spaceDimensionType);

        StellurgyConfiguration.loadPostInit();
        //TODO recipes?
        machineRecipes.registerXMLRecipes();

        TilePlugBase.energy_multiplier =  dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig(). blockEnergyHatchCapacityMultiplier;
        TileFluidHatch.capacityMultiplier =  dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig().blockLiquidHatchCapacityMultiplier;

        valkyrienSkies.postInit(event);
        affs.postInit(event);
    }

    @EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        for (int dimId : serverDimensions().getLoadedDimensions()) {
            DimensionProperties properties = serverDimensions().getDimensionProperties(dimId);
            if (!properties.isNativeDimension && properties.getId() == serverDimensions().getMoonId() && !Loader.isModLoaded("GalacticraftCore")) {
                properties.isNativeDimension = true;
            }
        }

        // Layer-1 universe registry: worlds are loaded (seed + map storage reachable) and the star
        // catalogue is built (createAndLoadDimensions ran at serverAboutToStart), so place every system.
        dev.stannismod.stellurgy.universe.UniverseRegistry.populate(
                net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance(),
                serverDimensions());
        // Layer-2: restore the persisted ship ledger (settled positions survive a restart) now that the
        // overworld MapStorage is reachable, before any player logs in.
        dev.stannismod.stellurgy.space.SpaceSubsystem.onServerStarted(spaceSubsystem);
        // The two API services whose STATE belongs to this server, published together and released
        // together in serverStopped. Here rather than in either object's constructor: a constructor
        // runs from its class's own static initialiser, at whatever moment something first touches
        // the class, which may be before Forge has assigned this mod instance at all.
        attachServerServices(serverSpaceObjects(), serverDimensions());
    }

    @EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        // Before worlds load: the first tile to load registers into this server's networks, which
        // the server state builds.
        beginServerLifetime();
        // Populate dimension properties before worlds get loaded
        serverDimensions().createAndLoadDimensions(resetFromXml);
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new StellurgyCommandRoot());
        // Test-only /stellurgytest probe surface — no-op unless -Dstellurgy.tests=true
        // (or a harness-spawned server sets -Dforge.test.server=true).
        TestProbeCommandRegistration.registerIfTestMode(event);

        // Movable-ship space subsystem: register the slot pool + build the subsystem this server will
        // run. Built here and HELD here — the mod is its owner; the step gives back what to hold and
        // hands back what we already had when it stands down, so this assignment cannot lose one.
        spaceSubsystem = dev.stannismod.stellurgy.space.SpaceSubsystem.buildForServer(spaceSubsystem);

        //Regenerate Chemical Reactor armor recipes
        TileChemicalReactor.reloadRecipesSpecial();

        //Load Asteroids from XML
        File file = new File("./config/" + dev.stannismod.stellurgy.api.StellurgyConfiguration.configFolder + "/asteroidConfig.xml");
        logger.info("Checking for asteroid config at " + file.getAbsolutePath());
        if (!file.exists()) {
            logger.info(file.getAbsolutePath() + " not found, generating");
            try {
                file.createNewFile();
                BufferedWriter stream;
                stream = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
                stream.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Asteroids>"
                        + "\n\t<asteroid name=\"Small Asteroid\" distance=\"10\" mass=\"200\" massVariability=\"0.5\" minLevel=\"0\" probability=\"20\" richness=\"0.3\" richnessVariability=\"0.5\">"
                        + "\n\t\t<ore itemStack=\"minecraft:iron_ore\" chance=\"15\" />"
                        + "\n\t\t<ore itemStack=\"minecraft:gold_ore\" chance=\"10\" />"
                        + "\n\t\t<ore itemStack=\"minecraft:redstone_ore\" chance=\"10\" />"
                        + "\n\t</asteroid>"
                        + "\n\t<asteroid name=\"Light Asteroid\" distance=\"60\" mass=\"200\" massVariability=\"0.5\" minLevel=\"0\" probability=\"15\" richness=\"0.2\" richnessVariability=\"0.5\">"
                        + "\n\t\t<ore itemStack=\"libvulpes:ore0;9\" chance=\"20\" />"
                        + "\n\t\t<ore itemStack=\"libvulpes:ore0;8\" chance=\"10\" />"
                        + "\n\t\t<ore itemStack=\"minecraft:quartz_block\" chance=\"5\" />"
                        + "\n\t</asteroid>"
                        + "\n\t<asteroid name=\"Iridium Enriched asteroid\" distance=\"100\" mass=\"75\" massVariability=\"0.5\" minLevel=\"0\" probability=\"2\" richness=\"0.2\" richnessVariability=\"0.3\">"
                        + "\n\t\t<ore itemStack=\"minecraft:iron_ore\" chance=\"25\" />"
                        + "\n\t\t<ore itemStack=\"libvulpes:ore0 10\" chance=\"5\" />"
                        + "\n\t</asteroid>"
                        + "\n\t<asteroid name=\"Strange Asteroid\" distance=\"120\" mass=\"50\" massVariability=\"0.5\" minLevel=\"0\" probability=\"1\" richness=\"0.2\" richnessVariability=\"0.5\">"
                        + "\n\t\t<ore itemStack=\"libvulpes:ore0;0\" chance=\"20\" />"
                        + "\n\t\t<ore itemStack=\"minecraft:emerald_ore\" chance=\"5\" />"
                        + "\n\t</asteroid>"
                        + "\n</Asteroids>");
                stream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        XMLAsteroidLoader load = new XMLAsteroidLoader();
        try {
            if (load.loadFile(file)) {
                for (Asteroid asteroid : load.loadPropertyFile()) {
                    serverDimensions().getAsteroidTypes().put(asteroid.ID, asteroid);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        // End load asteroids from XML


        file = new File("./config/" + dev.stannismod.stellurgy.api.StellurgyConfiguration.configFolder + "/oreConfig.xml");
        logger.info("Checking for ore config at " + file.getAbsolutePath());
        if (!file.exists()) {
            logger.info(file.getAbsolutePath() + " not found, generating");
            try {

                file.createNewFile();
                BufferedWriter stream;
                stream = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
                stream.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<OreConfig>\n</OreConfig>");
                stream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            XMLOreLoader oreLoader = new XMLOreLoader();
            try {
                if (oreLoader.loadFile(file)) {
                    List<SingleEntry<HashedBlockPosition, OreGenProperties>> mapping = oreLoader.loadPropertyFile();
                    dev.stannismod.stellurgy.util.OreGenTable oreTable = serverState().oreTable;

                    for (Entry<HashedBlockPosition, OreGenProperties> entry : mapping) {
                        int pressure = entry.getKey().x;
                        int temp = entry.getKey().y;

                        if (pressure == -1) {
                            if (temp != -1) {
                                oreTable.setOresForTemperature(Temps.values()[temp], entry.getValue());
                            }
                        } else if (temp == -1) {
                            oreTable.setOresForPressure(AtmosphereTypes.values()[pressure], entry.getValue());
                        } else {
                            oreTable.setOresForPressureAndTemp(AtmosphereTypes.values()[pressure], Temps.values()[temp], entry.getValue());
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        //End open and load ore files

        valkyrienSkies.serverStart(event);
    }


    /**
     * The worlds are still up and the last save has not run yet — the only moment a ship that is mid-jump
     * can be brought fully up to date before the server writes what a returning player will resume from.
     */
    @EventHandler
    public void serverStopping(net.minecraftforge.fml.common.event.FMLServerStoppingEvent event) {
        dev.stannismod.stellurgy.space.SpaceSubsystem.onServerStopping(spaceSubsystem);
    }

    @EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        // Released here, by the owner: the subsystem belonged to the server that has just stopped.
        spaceSubsystem = null;
        detachServerServices();
        weights.save();
        endServerLifetime();
    }

    @SubscribeEvent
    public void registerOre(OreRegisterEvent event) {
        //Register ore products
        if (!dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig().allowMakingItemsForOtherMods)
            return;

        for (AllowedProducts product : AllowedProducts.getAllAllowedProducts()) {
            if (event.getName().startsWith(product.name().toLowerCase(Locale.ENGLISH))) {
                HashSet<String> list = modProducts.computeIfAbsent(product, k -> new HashSet<>());
                list.add(event.getName().substring(product.name().length()));
            }
        }
    }

    // pretty hacky way and Stellurgy also have it's own syncing mechanism, but i just dont trust it
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            EntityPlayerMP player = (EntityPlayerMP) event.player;

            for (ISpaceObject spaceObject : SpaceObjectManager.getSpaceManager().getSpaceObjects()) {
                if (spaceObject instanceof SpaceStationObject) {
                    SpaceStationObject station = (SpaceStationObject) spaceObject;
                    PacketHandler.sendToPlayer(new PacketSyncKnownPlanets(station.getId(), station.getKnownPlanetList()), player);
                }
            }

            // An ALPHA world model is told to the player, on the world it applies to, every time he
            // arrives. Not once and not in a changelog: what it warns about is that this world may have
            // no way forward, and that is worth knowing before he invests another evening in it.
            java.util.Optional.ofNullable(dev.stannismod.stellurgy.universe.UniverseRegistry.get(player.getServer()))
                    .flatMap(dev.stannismod.stellurgy.universe.UniverseRegistry::activeSchema).ifPresent(schema -> {
                if (!schema.isStable()) {
                    player.sendMessage(new net.minecraft.util.text.TextComponentTranslation(
                            "msg.stellurgy.universe.alpha", schema.label())
                            .setStyle(new net.minecraft.util.text.Style()
                                    .setColor(net.minecraft.util.text.TextFormatting.GOLD)));
                }
            });
        }
    }
}
