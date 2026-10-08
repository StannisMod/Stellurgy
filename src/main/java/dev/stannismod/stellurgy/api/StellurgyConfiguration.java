package dev.stannismod.stellurgy.api;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.oredict.OreDictionary;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import dev.stannismod.stellurgy.api.fuel.FuelRegistry;
import dev.stannismod.stellurgy.api.fuel.FuelRegistry.FuelType;
import dev.stannismod.stellurgy.atmosphere.AirState;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.integration.MatterOvedriveIntegration;
import dev.stannismod.stellurgy.util.Asteroid;
import dev.stannismod.stellurgy.util.SealableBlockHandler;

import java.io.IOException;
import java.io.InvalidClassException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Stores config variables
 */

public class StellurgyConfiguration {
    public static final String configFolder = "advRocketry";
    private final static byte MAGIC_CODE = (byte) 197;
    private final static long MAGIC_CODE_PT2 = 2932031007403L; // Prime

    private final static String WORLDGEN = "World and Ore Generation";
    private final static String ROCKET = "Rockets";
    private final static String STATION = "Station Configuration";
    private final static String PLANET = Constants.CONFIG_CATEGORY_PLANET;
    private final static String OXYGEN = "Oxygen System";
    private final static String ENERGY = "Energy Production";
    private final static String HEAT = "Ship Thermal System";
    private final static String MISSION = "Resource Collection Missions";
    private final static String WEAPONS = "Weapons";
    private final static String PERFORMANCE = "Performance";
    private final static String CLIENT = "Client";
    private final static String COMPAT = "Compatibility";
    /** OWNER: the LOADER — log4j hands out one object per name for the launch, and this class asks
     *  for it by name like every other class here does. Nothing releases it because nothing may.
     *  Not to be confused with the configuration below, which is the SERVER's while one is joined.
     *
     * Effectively final, process lifetime: built once at class initialisation.
     */
    private static final Logger logger = LogManager.getLogger(Constants.modId);

    /**
     * The raw string lists read from the config file. Effectively final, process lifetime: written only
     * by {@link #loadPreInit()}; all but geodeOres, blackHoleGeneratorTiming and orbitalLaserOres are read
     * again (and may be rewritten) by {@link #loadPostInit()}.
     */
    private static String[] sealableBlockWhiteList, sealableBlockBlackList, breakableTorches, blackListRocketBlocksStr, entityList, geodeOres, blackHoleGeneratorTiming, orbitalLaserOres, liquidMonopropellant, liquidBipropellantFuel, liquidBipropellantOxidizer, liquidNuclearWorkingFluid;
    /**
     * This process's own configuration, read from its file at pre-init. Effectively final, client /
     * dedicated-server lifetime: the reference is never replaced; its fields are filled by
     * {@link #loadPreInit()} / {@link #loadPostInit()} and rewritten only by the operator reloads
     * (config sync, {@code /addtorch}, {@code /addsealant}).
     */
    private static final StellurgyConfiguration ownConfig = new StellurgyConfiguration();

    // ASM compat fix for PlusTiC Portly tools rotating Stellurgy rockets on release.

    //Only to be set in preinit
    public net.minecraftforge.common.config.Configuration config;
    @ConfigProperty(needsSync = true)
    public int stationClearanceHeight = 1000;
    @ConfigProperty(needsSync = true)
    public int transBodyInjection = 0;
    @ConfigProperty(needsSync = true)
    public double asteroidTBIBurnMult = 1.0;
    @ConfigProperty(needsSync = true)
    public double warpTBIBurnMult = 10.0;
    @ConfigProperty(needsSync = true)
    public int dataBusBigMultiplier = 4;
    @ConfigProperty(needsSync = true)
    public int spaceDimId = -2;
    /** Lowest dimension id a planet may take; read once at pre-init. */
    @ConfigProperty
    public int minDimension = 2;
    // Movable-ship space subsystem (server-authoritative; loaded in loadPreInit, never network-synced).
    // There is deliberately NO enable flag here: space is the mod's subject rather than one of its
    // features, and it registers wherever the mod runs. See SpaceSubsystem.shouldRegister.
    public int spaceCellPoolSize = 10;
    public String spaceCellGcPolicy = "both";
    public int spaceCellMaxAgeTicks = 1728000;
    public int spaceMaxStoredCells = 4096;
    /** Fallback home-system anchor ("sectorX,sectorY,sectorZ") a tier-2 entry uses when the launch
     *  planet has no galactic placement in the universe registry. */
    public String spaceHomeSystemCoord = "0,0,0";
    /** Whether an in-flight hyperspace transit advances while its aboard crew are offline: "always"
     *  (advance whenever the server is up) or "crew-online" (pause while every aboard crew member is
     *  disconnected). Unmanned transits always advance. */
    public String spaceTransitOfflineProgress = "always";
    @ConfigProperty
    public int fuelPointsPer10Mb = 10;
    @ConfigProperty(needsSync = true)
    public int stationSize = 1024;
    @ConfigProperty(needsSync = true)
    public double rocketThrustMultiplier;
    @ConfigProperty(needsSync = true)
    public double nuclearCoreThrustRatio;
    @ConfigProperty(needsSync = true)
    public double fuelCapacityMultiplier;
    @ConfigProperty
    public boolean rocketRequireFuel = true;
    @ConfigProperty
    public boolean canBeFueledByHand = true;
    @ConfigProperty(needsSync = true)
    public boolean nuclearRocketsRespectArtifactGating = true;
    @ConfigProperty(needsSync = true)
    public boolean nuclearRocketsRequireArtifactForGatedStations = false;
    @ConfigProperty
    public boolean enableNausea = true;
    /** Damage taken per second in a vacuum. A configured NUMBER, so it lives with the other
     *  configured numbers: it used to be a public static on {@code AtmosphereVacuum} that the config
     *  loader reached over and wrote, which put a value the server owns in a class that only spends
     *  it. The behaviour that reads it is a row in the hazard table now, and it reads it at the
     *  moment it hurts somebody rather than snapshotting it at load. */
    @ConfigProperty
    public int vacuumDamage = 1;
    /**
     * What the air's chemistry does to living things. Neither switches a SYSTEM off: zones, machines
     * and gases run either way. {@code breathingRequiresO2} covers everything about oxygen in a body —
     * respiration, a suit's tank, suffocation and oxygen toxicity; {@code enableToxicity} covers
     * poison. Vacuum, pressure and heat are physics and answer to neither.
     */
    @ConfigProperty
    public boolean breathingRequiresO2 = true;
    @ConfigProperty
    public boolean enableToxicity = true;
    @ConfigProperty(needsSync = true)
    public boolean launchingDestroysBlocks;
    @ConfigProperty(needsSync = true)
    public float buildSpeedMultiplier = 1f;
    @ConfigProperty
    public boolean generateCopper;
    @ConfigProperty
    public int copperPerChunk;
    @ConfigProperty
    public int copperClumpSize;
    @ConfigProperty
    public boolean generateTin;
    @ConfigProperty
    public int tinPerChunk;
    @ConfigProperty
    public int tinClumpSize;
    @ConfigProperty
    public boolean generateDilithium;
    @ConfigProperty
    public int dilithiumClumpSize;
    @ConfigProperty
    public int dilithiumPerChunk;
    @ConfigProperty
    public int dilithiumPerChunkMoon;
    @ConfigProperty
    public int aluminumPerChunk;
    @ConfigProperty
    public int aluminumClumpSize;
    @ConfigProperty
    public boolean generateAluminum;
    @ConfigProperty
    public boolean generateIridium;
    @ConfigProperty
    public int IridiumClumpSize;
    @ConfigProperty
    public int IridiumPerChunk;
    @ConfigProperty
    public boolean generateRutile;
    @ConfigProperty
    public int rutilePerChunk;
    @ConfigProperty
    public int rutileClumpSize;
    @ConfigProperty
    public boolean allowMakingItemsForOtherMods;
    @ConfigProperty
    public boolean scrubberRequiresCartrige;
    @ConfigProperty
    public boolean overrideGCAir;
    @ConfigProperty
    public boolean electricPlantsSpawnLightning;
    @ConfigProperty
    public boolean allowSawmillVanillaWood;
    @ConfigProperty
    public int atmosphereHandleBitMask;
    @ConfigProperty
    public boolean automaticRetroRockets;
    @ConfigProperty
    public boolean advancedVFX;
    @ConfigProperty
    public boolean enableLaserDrill;
    @ConfigProperty
    public boolean enableOrbitalRegistry;
    @ConfigProperty
    public int spaceSuitOxygenTime;
    @ConfigProperty
    public float suitTankCapacity;
    @ConfigProperty
    public float travelTimeMultiplier;
    @ConfigProperty
    public int maxBiomesPerPlanet;
    @ConfigProperty
    public boolean enableTerraforming;
    @ConfigProperty(needsSync = true)
    public double gasCollectionMult;
    @ConfigProperty(needsSync = true)
    public double gasHarvestAmountMultiplier;
    @ConfigProperty(needsSync = true)
    public boolean gasHarvestInfinite;
    @ConfigProperty(needsSync = true)
    public double terraformSpeed;
    @ConfigProperty
    public boolean terraformRequiresFluid;
    @ConfigProperty
    public float microwaveRecieverMulitplier;
    @ConfigProperty
    public boolean blackListAllVanillaBiomes;
    @ConfigProperty
    public double asteroidMiningTimeMult;
    @ConfigProperty
    public boolean canPlayerRespawnInSpace;
    @ConfigProperty
    public boolean forcePlayerRespawnInSpace;
    @ConfigProperty
    public boolean perDimWorldInfo = true;
    @ConfigProperty
    public boolean enableCustomPlanetWeather = true;
    @ConfigProperty
    public boolean allowTimeSkipOnPlanets = false;
    @ConfigProperty
    public boolean allowTimeSkipOnOverworld = true;
    @ConfigProperty
    public boolean logPlanetWeatherWrapping = true;
    @ConfigProperty
    public boolean forcePlanetWeatherWorldInfoWrapper = false;
    @ConfigProperty(needsSync = true)
    public float minAtmosphereDensityForRain = 75f;
    @ConfigProperty
    public float acidRainDamage = 1f;
    @ConfigProperty
    public int acidRainDamageInterval = 20;
    @ConfigProperty
    public float spaceLaserPowerMult;
    @ConfigProperty
    public float blockTankCapacity;
    @ConfigProperty
    public float blockEnergyHatchCapacityMultiplier;
    @ConfigProperty
    public float blockLiquidHatchCapacityMultiplier;
    @ConfigProperty
    public LinkedList<Integer> laserBlackListDims = new LinkedList<>();
    @ConfigProperty
    public LinkedList<String> standardLaserDrillOres = new LinkedList<>();
    @ConfigProperty
    public boolean laserDrillPlanet;
    /**
     * list of entities of which atmospheric effects should not be applied
     **/
    @ConfigProperty
    public LinkedList<Class> bypassEntity = new LinkedList<>();
    @ConfigProperty
    public LinkedList<Block> torchBlocks = new LinkedList<>();
    @ConfigProperty
    public LinkedList<Block> blackListRocketBlocks = new LinkedList<>();
    @ConfigProperty
    public LinkedList<String> standardGeodeOres = new LinkedList<>();
    @ConfigProperty
    public boolean geodeOresBlackList;
    @ConfigProperty
    public boolean laserDrillOresBlackList;
    @ConfigProperty
    public int oxygenVentSize;
    /**
     * The oxygen band, the ignition floor and the crew's draw, in the COMPOSITION's own unit rather
     * than the millionths the config file states them in.
     * <p>
     * These are compared against, and subtracted from, a zone's partial pressures every tick, so they
     * are held in the unit those pressures are in — the conversion happens once, where the file is
     * read, instead of at every comparison. A config file stays in parts per million because that is
     * what an oxygen fraction and an exposure limit are quoted in; see {@code AirState.PER_PPM}.
     */
    @ConfigProperty
    public long lifeSupportMinPartialO2;
    @ConfigProperty
    public long lifeSupportMaxPartialO2;
    @ConfigProperty
    public long lifeSupportCombustionMinPartialO2;
    @ConfigProperty
    public long lifeSupportRespirationRate;
    @ConfigProperty
    public int lifeSupportAirHeatCapacity;
    @ConfigProperty
    public long lifeSupportRecirculatorRate;
    @ConfigProperty
    public int lifeSupportRecirculatorPower;
    @ConfigProperty
    public long lifeSupportCarbonPerDust;
    /**
     * A scrubber's draw and what one cartridge charge pays for, in the respiration rate's unit: the
     * composition's unit times the zone volume in blocks, so an amount of gas rather than a pressure.
     */
    @ConfigProperty
    public long lifeSupportScrubberRate;
    @ConfigProperty
    public long lifeSupportScrubberCo2PerCharge;
    @ConfigProperty
    public int jettisonPortIntervalTicks;
    @ConfigProperty
    public int jettisonPortClearance;
    @ConfigProperty
    public int lifeSupportFluidPerAtmBlock;
    @ConfigProperty
    public long lifeSupportSeparatorRate;
    @ConfigProperty
    public int lifeSupportSeparatorPower;
    @ConfigProperty
    public int lifeSupportPlantRate;
    @ConfigProperty
    public int lifeSupportPlantPower;
    @ConfigProperty
    public int lifeSupportPlantCarbonPerDust;
    @ConfigProperty
    public int lifeSupportDuctThroughput;
    @ConfigProperty
    public int lifeSupportPortFePerTick;
    @ConfigProperty
    public long lifeSupportBreachVentRate;
    @ConfigProperty
    public boolean shipHeat;
    @ConfigProperty
    public int shipHeatAmbientKelvin;
    @ConfigProperty
    public int shipHeatPipeCapacity;
    @ConfigProperty
    public int shipHeatAccumulatorCapacity;
    @ConfigProperty
    public int shipHeatPipeThroughput;
    @ConfigProperty
    public int shipHeatWasteFraction;
    @ConfigProperty
    public int shipHeatRadiatorCellPower;
    @ConfigProperty
    public int shipHeatRadiatorReferenceKelvin;
    @ConfigProperty
    public int shipHeatRadiatorClearance;
    @ConfigProperty
    public int shipHeatChillerThroughput;
    @ConfigProperty
    public int shipHeatChillerCopFraction;
    @ConfigProperty
    public int shipHeatChillerCapacity;
    @ConfigProperty
    public int shipHeatStarFluxReferenceKelvin;
    @ConfigProperty
    public int shipHeatShieldAttenuation;
    @ConfigProperty
    public int planetGreenhouseCeilingAtm;
    @ConfigProperty
    public int shipHeatChillerMaxCop;
    @ConfigProperty
    public int shipHeatCabinConductionFraction;
    @ConfigProperty
    public int shipHeatCrewVeryHotKelvin;
    @ConfigProperty
    public int shipHeatCrewSuperheatedKelvin;
    @ConfigProperty
    public int shipHeatDriveRefusalKelvin;
    @ConfigProperty
    public int shipHeatSlugMarginKelvin;
    @ConfigProperty
    public int shipHeatSlugJoulesPerUnit;
    @ConfigProperty
    public int shipHeatMeltCheckTicks;
    @ConfigProperty
    public int shipHeatDumpThroughput;
    @ConfigProperty
    public int shipHeatDumpTriggerKelvin;
    @ConfigProperty
    public int shipHeatHullSkinFraction;
    @ConfigProperty
    public int solarGeneratorMult;
    @ConfigProperty
    public boolean gravityAffectsFuel;
    @ConfigProperty
    public boolean lowGravityBoots;
    @ConfigProperty
    public float jetPackThrust;
    @ConfigProperty
    public boolean enableGravityController;
    @ConfigProperty(needsSync = true)
    public boolean planetsMustBeDiscovered;
    @ConfigProperty
    public boolean generateGeodes;
    @ConfigProperty
    public int geodeBaseSize;
    @ConfigProperty
    public int geodeVariation;
    @ConfigProperty
    public int terraformliquidRate;
    @ConfigProperty
    public boolean dropExTorches;
    @ConfigProperty
    public double oxygenVentConsumptionMult;
    @ConfigProperty
    public int terraformPlanetSpeed;
    @ConfigProperty
    public int planetDiscoveryChance;
    /**
     * The shipped telescope-survey defaults, named so that the code that REGISTERS them and the test
     * that MEASURES what they cost cannot drift apart. A default whose consequences are stated
     * somewhere other than where the default lives is a number nobody is checking.
     *
     * <p>Measured together, at the stock star table and star spacing: a full-depth pointing holds
     * about 77 000 looks, reaches 1 768 light years, registers of the order of thirty systems, and
     * takes roughly six hundred steps.</p>
     */
    public static final double DEFAULT_TELESCOPE_LIMITING_MAGNITUDE = 8d;
    /** @see #DEFAULT_TELESCOPE_LIMITING_MAGNITUDE */
    public static final double DEFAULT_TELESCOPE_CONE_HALF_ANGLE_DEGREES = 1d;
    /**
     * How much BRIGHTER than the detection limit a system must be before an instrument can make out
     * what is in it — 6.5 magnitudes, and the number is derived rather than chosen.
     *
     * <p>Seeing that a point of light is there and measuring what orbits it are not the same
     * observation. Detection is conventionally called at a signal-to-noise of about 5 — enough to
     * say "something is there". Characterisation is transit photometry and spectroscopy, and a
     * usable spectrum wants an SNR around 100. Signal-to-noise grows as the square root of the
     * photons collected, so the flux ratio between the two is {@code (100/5)² = 400}, and
     * {@code 2.5·log10(400) = 6.5} magnitudes.</p>
     *
     * <p><b>What it costs at the shipped aperture</b>, measured: detection reaches 161 ly for a
     * sun-like star and 1 359 ly for a blue giant; resolution reaches 8.1 ly and 68 ly. Against a
     * mean star separation of 4.23 ly that means an early instrument resolves its nearest few
     * neighbours and hands back coordinates for everything else — which is the progression the
     * aperture ladder exists to sell.</p>
     */
    public static final double DEFAULT_TELESCOPE_RESOLVE_MARGIN_MAGNITUDES = 6.5d;
    /** @see #DEFAULT_TELESCOPE_LIMITING_MAGNITUDE */
    public static final int DEFAULT_TELESCOPE_SCAN_MAX_CELLS = 200_000;
    /** @see #DEFAULT_TELESCOPE_LIMITING_MAGNITUDE */
    public static final int DEFAULT_TELESCOPE_SCAN_BASE_TICKS = 20;
    /** @see #DEFAULT_TELESCOPE_LIMITING_MAGNITUDE */
    public static final int DEFAULT_TELESCOPE_SCAN_CELLS_PER_STEP = 130;

    @ConfigProperty
    public double telescopeLimitingMagnitude;
    @ConfigProperty
    public double telescopeConeHalfAngleDegrees;
    @ConfigProperty
    public double telescopeResolveMarginMagnitudes;
    @ConfigProperty
    public int telescopeScanMaxCells;
    @ConfigProperty
    public int telescopeScanBaseTicks;
    @ConfigProperty
    public int telescopeScanCellsPerStep;
    @ConfigProperty
    public int telescopePassiveRadiusSteps;
    @ConfigProperty
    public double telescopeObscuredAtMagnitudes;
    @ConfigProperty
    public int telescopeSurveyDataPerStep;
    @ConfigProperty
    public boolean allowNonStellurgyBiomesInTerraforming;
    @ConfigProperty
    public double oxygenVentPowerMultiplier;
    @ConfigProperty
    public boolean skyOverride;

    /**
     * Whether the cell sky writes each body's name and distance beside it. ON by
     * default: it is how a pilot — and a human checking the game — reads that a body is receding
     * without a probe. OFF removes the label entirely; it does not dim it.
     *
     * <p>{@code @ConfigProperty} is not optional even for a CLIENT-only flag that needs no syncing:
     * joining a server replaces the whole config object with a copy built by the annotation-driven
     * copy constructor, and an un-annotated field is not copied — it silently reverts to its Java
     * default for as long as the player is connected, which for a boolean is {@code false}. That is
     * why the initializer below states the default too rather than leaving it implied.</p>
     */
    @ConfigProperty
    public boolean skyBodyLabels = true;
    @ConfigProperty
    public boolean overworldsealevelterraforming;
    @ConfigProperty
    public boolean planetSkyOverride;
    @ConfigProperty
    public boolean stationSkyOverride;
    @ConfigProperty
    public boolean allowTerraformNonStellurgy;
    @ConfigProperty
    public float crystalliserMaximumGravity;
    @ConfigProperty
    public boolean allowZeroGSpacestations;
    @ConfigProperty
    public float blackHolePowerMultiplier;
    @ConfigProperty
    public int defaultItemTimeBlackHole;
    @ConfigProperty
    public Map<ItemStack, Integer> blackHoleGeneratorBlocks = new HashMap<>();
    @ConfigProperty
    public String[] lavaCentrifugeOutputs;
    @ConfigProperty
    public int lavaCentrifugeTime;
    @ConfigProperty
    public int lavaCentrifugePower;
    @ConfigProperty
    public boolean generateVanillaStructures;
    @ConfigProperty
    public boolean generateCraters;
    @ConfigProperty
    public boolean generateVolcanos;
    @ConfigProperty(needsSync = true)
    public boolean experimentalSpaceFlight;

    @ConfigProperty
    public boolean advancedWeightSystem;
    /**
     * Multiplier on the mass of what a craft's machines HOLD — items in inventories, fluids in tanks —
     * and on nothing else: not the blocks, not the people aboard. {@code 0} makes cargo weightless.
     * Synced because the client prices a rocket's weight too.
     */
    @ConfigProperty(needsSync = true)
    public double contentMassScale = 1.0;
    @ConfigProperty(needsSync = true)
    public double fuelMassScale = 1.0;
    @ConfigProperty(needsSync = true)
    public double minLaunchTWR = 1.05;
    @ConfigProperty(needsSync = true)
    public double wearThrustPenaltyMax = 0.5;
    @ConfigProperty(needsSync = true)
    public double wearWarnProbability = 0.05;
    @ConfigProperty(needsSync = true)
    public boolean wearCriticalBlocksLaunch = false;
    /**
     * Whether two craft refuse to pass through one another. Until this existed they simply
     * overlapped and nothing happened; what it does is crude on purpose — both stop while their
     * boxes overlap, nothing is conserved — so it is a switch a server can turn off whole.
     */
    @ConfigProperty(needsSync = true)
    public boolean shipsCollide = true;
    @ConfigProperty(needsSync = true)
    public double serviceStationStandaloneRepairMultiplier = 3.0;
    /**
     * Share of a block's own crafting recipe charged to repair it from destroyed-adjacent back to
     * pristine BY HAND, spread over its stages. 1.0 means a full hand repair costs about what the
     * block costs — the welder's advantage over simply replacing it is that the block, and whatever
     * its tile entity holds, stays where it is.
     */
    @ConfigProperty(needsSync = true)
    public double repairCostPerStageFraction = 1.0;
    @ConfigProperty(needsSync = true)
    public int repairWelderEnergyPerStage = 2000;
    @ConfigProperty(needsSync = true)
    public int repairWelderCapacity = 100000;
    /**
     * Below this speed, in blocks per tick, a shot mirrored off a shield is ended at the shell rather
     * than left alive. A body deflected to nearly nothing has to be somewhere if it is an entity; a
     * record does not, and a cloud of near-motionless rounds loitering against a shell is both a
     * simulation cost and a lie about what is in the air.
     */
    @ConfigProperty(needsSync = true)
    public double shotReflectionSpeedFloor = 0.05;
    @ConfigProperty(needsSync = true)
    public double shotPenetrationSpeedFloor = 0.05;
    /**
     * The widest a shot's body may be treated as, in blocks, however wide it was declared. A body
     * sweeps a cylinder rather than a line, and the blocks one step examines grow with the SQUARE of
     * its width, so this is what keeps an absurd calibre from being a way of making the server do
     * arbitrary work. It caps the geometry only: the declared cross-section still prices the shot.
     */
    @ConfigProperty(needsSync = true)
    public double shotBodyRadiusCap = 2.0;
    /**
     * How much dearer a block is to BOIL AWAY than to push through, when nothing has written it its
     * own ablation row. Both figures are energy per unit of volume removed — the same dimension — and
     * they are nowhere near the same magnitude: for steel, being pushed through costs of order a
     * gigajoule per cubic metre while heating, melting and vaporising it costs tens of them. So per
     * joule a kinetic round removes far more hull than a beam does, and a laser buys precision,
     * instantaneity and nothing to reload instead of digging power.
     */
    @ConfigProperty(needsSync = true)
    public double ablationResistanceFactor = 20.0;
    /**
     * The power density below which a beam does not drill at all — it warms the plate and the energy
     * is conducted away — as energy per unit of the body's cross-section, in the units an impact
     * budget is in. <b>Off by default, and the reason is worth reading before turning it on.</b>
     *
     * <p>The worry it was written for is real: a linear ablation law would let a faint beam held long
     * enough cut a battleship. But the law is NOT linear in the way that worry assumes — a stage is
     * bought whole or not at all, and a beam that cannot afford one keeps its energy and buys nothing
     * this tick or any other. Since a stage costs `perStage x ablation x area / referenceArea`, "can
     * this beam afford a stage" is ALREADY the question "is this beam intense enough", with a
     * threshold of `perStage x ablation / referenceArea` that each block sets for itself. A faint beam
     * therefore never accumulates, and the battleship is safe without this knob.</p>
     *
     * <p>What the knob buys, at a value ABOVE that affordability line, is the thing the pricing does
     * NOT do: a sub-threshold beam's energy is ABSORBED. Without it a beam too weak to mark a plate
     * does not warm it — it passes clean through with everything it arrived with, a free x-ray of the
     * hull. That, rather than the battleship, is why the threshold is set.</p>
     *
     * <p><b>The default sits above the affordability line of METAL.</b> The line is
     * `perStage x ablation / referenceArea`, which each block sets for itself: with the shipped table
     * that is of order 8 000 for stone and 38 500 for an iron block. At 50 000 a small emitter does
     * nothing whatever to a metal hull, however long it is held, and its energy stays in the plate —
     * which is the qualitative gap between a big emitter and a small one, and the reason a pulsed
     * laser is worth building. Zero disables it and restores the x-ray.</p>
     */
    @ConfigProperty(needsSync = true)
    public double beamAblationIntensityThreshold = 50000.0;
    /**
     * How glancing a hit has to be before a solid round skips off METAL instead of digging in, as the
     * angle between the round and the surface normal in degrees: 0 is square-on, 90 is a pure graze.
     * Only metal deflects — a round never skips off a plank wall — so a player meets bouncing rounds
     * where a player expects them. 90 disables ricochet entirely.
     */
    @ConfigProperty(needsSync = true)
    public double ricochetIncidenceDegrees = 65.0;
    /**
     * How much of its speed a ricocheting round keeps. Below 1 a bounce costs something, which is what
     * stops a round skipping between two plates forever; at 1 a graze is free.
     */
    @ConfigProperty(needsSync = true)
    public double ricochetRestitution = 0.75;
    /**
     * How many shots one world may carry at once. A refusal, not an eviction: dropping somebody
     * else's round to make room would turn a burst of cheap fire into a way of deleting incoming fire.
     */
    @ConfigProperty(needsSync = true)
    public int maxShotsPerWorld = 256;
    /**
     * How near a player's eye a round's PATH — or a held beam's lit LENGTH — must pass before that
     * player is told about it, in blocks. Weapon fire is a server record, so being told is the only
     * way a client can draw any of it; sending every round to everybody in the world would put a
     * battery's whole rate of fire on every player's connection, including the ones on the far side
     * of a planet. Zero switches the whole drawing channel off: the mechanics still work and nothing
     * is drawn. One radius covers both families deliberately — it is the same question about the
     * same guns, and two knobs would be two answers.
     */
    @ConfigProperty(needsSync = true)
    public int shotVisibilityRadius = 256;
    /**
     * Whether the fire-control sensor searches for targets at all. With this off the block still
     * exists and still says what it is, and it acquires nothing, publishes nothing and draws no
     * power — so a battery falls back to being pointed by hand, which is exactly what it was before
     * the sensor existed rather than a broken version of it.
     */
    @ConfigProperty(needsSync = true)
    public boolean enableFireControlSensor = true;
    /** How far a fire-control sensor can look at all, in blocks. Its envelope, not its lock range. */
    @ConfigProperty(needsSync = true)
    public double fireControlSensorRadius = 96.0;
    /**
     * Ticks between sweeps. A contact's position is re-read by the gun every tick from the entity
     * itself, so this is the cadence at which the sensor reconsiders WHICH thing to shoot at, not
     * the cadence at which the mount is allowed to follow it.
     */
    @ConfigProperty(needsSync = true)
    public int fireControlSensorScanIntervalTicks = 10;
    /** How many contacts one sensor can hold. A bound on work, and on how much a readout can say. */
    @ConfigProperty(needsSync = true)
    public int fireControlSensorMaxTracks = 8;
    /**
     * FE per tick an ACTIVE sensor draws. An illuminating sensor is a machine that is running; a
     * listening one costs nothing, which is what makes going quiet a genuine option rather than a
     * penalty. A sensor that cannot pay falls back to listening rather than lying about its lock.
     */
    @ConfigProperty(needsSync = true)
    public int fireControlSensorActiveEnergyPerTick = 40;
    /**
     * The lock quality an ACTIVE sensor holds a contact at inside its envelope. This is the
     * passive/active gap: everything a listening sensor gets is bounded by what the target radiates,
     * and this is what illuminating buys instead.
     */
    @ConfigProperty(needsSync = true)
    public double fireControlSensorActiveLockQuality = 0.95;
    /**
     * How well a contact must be resolved before a gun will fire at it, 0..1. Below it a battery
     * still tracks — knowing something is out there and being unable to hit it is a real state, and
     * the reason to switch the sensor on.
     */
    @ConfigProperty(needsSync = true)
    public double fireControlSensorLockQualityToFire = 0.25;
    /**
     * Whether acquisition is limited to hostile mobs and players. Off, a defence battery opens up on
     * whatever wanders past, which is a legitimate way to run a perimeter and a poor default.
     */
    @ConfigProperty(needsSync = true)
    public boolean fireControlSensorAcquireHostilesOnly = true;
    /**
     * How far gone a turret's own block must be, 0..1, before its traverse slows and then seizes.
     * The ORDER of the two rungs is the mechanic and is not configurable; where they sit is balance.
     *
     * <p>Note what is deliberately absent: no flag disables this. Damage and wear advance the same
     * stage counter, so a switch here would be a switch that makes ships unkillable — and the
     * parts-wear flag gates wear where wear ACCRUES, never where a consequence is read.</p>
     */
    @ConfigProperty(needsSync = true)
    public double turretDerateDamageFraction = 0.25;
    @ConfigProperty(needsSync = true)
    public double turretJamDamageFraction = 0.75;
    @ConfigProperty(needsSync = true)
    public double wearTankLeakChanceMax = 0.5;
    @ConfigProperty(needsSync = true)
    public double wearTankLeakFuelLoss = 0.25;
    @ConfigProperty(needsSync = true)
    public double wearSeatBlockStageFraction = 0.7;

    @ConfigProperty
    public boolean partsWearSystem;

    @ConfigProperty
    public double increaseWearIntensityProb;

    public StellurgyConfiguration() {}

    public StellurgyConfiguration(StellurgyConfiguration config) {
        Field[] fields = StellurgyConfiguration.class.getDeclaredFields();
        List<Field> fieldList = new ArrayList<>(fields.length);

        // getDeclaredFields returns an unordered list, so we need to sort them
        for (Field field : fields) {
            if (field.isAnnotationPresent(ConfigProperty.class))
                fieldList.add(field);
        }

        fieldList.sort(Comparator.comparing(Field::getName));


        // Structural (container-level) copy of every @ConfigProperty field.
        // Collection/Map fields get a fresh container so the copy never aliases
        // the source config's Map/List/Set — the old `field.getClass()` test was
        // always false (getClass() is java.lang.reflect.Field), so every
        // collection used to be shallow-copied by reference. Element references
        // are shared, which is fine because config data is treated as immutable.
        for (Field field : fieldList) {
            try {
                Class<?> type = field.getType();
                Object value = field.get(config);
                if (value != null && Map.class.isAssignableFrom(type)) {
                    Map copy = (Map) value.getClass().newInstance();
                    copy.putAll((Map) value);
                    field.set(this, copy);
                } else if (value != null && Collection.class.isAssignableFrom(type)) {
                    Collection copy = (Collection) value.getClass().newInstance();
                    copy.addAll((Collection) value);
                    field.set(this, copy);
                } else {
                    field.set(this, value);
                }
            } catch (IllegalArgumentException | InstantiationException | IllegalAccessException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * The configuration in force for the CALLER: a server's own; on a client, the configuration the
     * connected server sent, or the client's own when it sent none (a local server, which runs this
     * very configuration) or there is no connection. Before Forge has injected the proxy no
     * connection can exist, so the process's own is the answer.
     */
    public static StellurgyConfiguration getCurrentConfig() {
        dev.stannismod.stellurgy.common.CommonProxy proxy = dev.stannismod.stellurgy.Stellurgy.proxy;
        return proxy == null ? ownConfig : proxy.configInForce(ownConfig);
    }

    /**
     * A partial pressure or a rate of one, authored in millionths of an atmosphere and returned in
     * the composition's own unit.
     * <p>
     * The single place the two units meet. A person writing a config file thinks in parts per million
     * because that is how an oxygen fraction and an exposure limit are quoted; the model stores a
     * thousand times finer so a trace atmosphere still has digits left. Converting HERE, once, is what
     * lets every comparison downstream be a plain {@code >=} against a zone's own numbers.
     * <p>
     * The bound is the one the TYPE imposes and nothing tighter: a Forge integer property, so whatever
     * fits in one. Deciding here that no pack may want a threshold above one atmosphere would be a
     * balance opinion wearing a limit's clothes, and a world like Venus is exactly where it would be
     * wrong. The product cannot overflow either way — the internal unit holds some nine billion
     * atmospheres.
     */
    private static long partialPressure(net.minecraftforge.common.config.Configuration config,
                                        String key, int defaultPpm, String comment) {
        return config.get(OXYGEN, key, defaultPpm, comment, 0, Integer.MAX_VALUE).getInt()
                * AirState.PER_PPM;
    }

    public static void loadPreInit() {

        StellurgyConfiguration stellurgyConfig = ownConfig;
        net.minecraftforge.common.config.Configuration config = stellurgyConfig.config;

        //General
        stellurgyConfig.allowMakingItemsForOtherMods = config.get(Configuration.CATEGORY_GENERAL, "makeMaterialsForOtherMods", true, "Allow Stellurgy machines to make rods/plates e.g for other mods. May increase load time.").getBoolean();
        stellurgyConfig.allowSawmillVanillaWood = config.get(Configuration.CATEGORY_GENERAL, "sawMillCutVanillaWood", true, "Allow the cutting machine to turn vanilla wood into planks.").getBoolean();
        stellurgyConfig.lowGravityBoots = config.get(Configuration.CATEGORY_GENERAL, "lowGravityBoots", false, "Make low-gravity boots work only on low-gravity planets.").getBoolean();
        stellurgyConfig.jetPackThrust = (float) config.get(Configuration.CATEGORY_GENERAL, "jetPackForce", 1.3, "Amount of force the jetpack provides with respect to gravity, 1 is the same acceleration as caused by Earth's gravity, 2 is 2x the acceleration caused by Earth's gravity, etc.  To make jetpack only work on low gravity planets, simply set it to a value less than 1").getDouble();
        stellurgyConfig.buildSpeedMultiplier = (float) config.get(Configuration.CATEGORY_GENERAL, "buildSpeedMultiplier", 1f, "Multiplier for Rocket Assembler speed. (0.5 is twice as fast 2 is half as fast)").getDouble();
        stellurgyConfig.blockTankCapacity = (float) config.get(Configuration.CATEGORY_GENERAL, "blockTankCapacity", 1.0f, "Multiplier for pressurized tank's (block) capacity.", 0, Float.MAX_VALUE).getDouble();
        stellurgyConfig.blockEnergyHatchCapacityMultiplier = (float) config.get(Configuration.CATEGORY_GENERAL, "blockEnergyHatchCapacityMultiplier", 1.0f, "Multiplier for energy hatch capacity.", 0, Float.MAX_VALUE).getDouble();
        stellurgyConfig.blockLiquidHatchCapacityMultiplier = (float) config.get(Configuration.CATEGORY_GENERAL, "blockLiquidHatchCapacityMultiplier", 1.0f, "Multiplier for liquid hatch capacity.", 0, Float.MAX_VALUE).getDouble();
        stellurgyConfig.dataBusBigMultiplier = config.getInt("dataBusBigMultiplier", Configuration.CATEGORY_GENERAL, 4, 1, 20, "Multiplier for the Advanced Data Bus capacity. (Base=2000 -> default= 4 * 2000 = 8000)");

        //Enriched Lava in the centrifuge
        stellurgyConfig.lavaCentrifugeOutputs = config.getStringList("lavaCentrifugeOutputs", Configuration.CATEGORY_GENERAL, new String[]{"nuggetCopper:100", "nuggetIron:100", "nuggetTin:100", "nuggetLead:100", "nuggetSilver:100", "nuggetGold:75", "nuggetDiamond:10", "nuggetUranium:10", "nuggetIridium:1"}, "List of Centrifuge outputs from enriched lava. Format: <oredict>:<weight>.");
        stellurgyConfig.lavaCentrifugePower = config.getInt("lavaCentrifugePower", Configuration.CATEGORY_GENERAL, 10,0,999999,"Power per tick, used to process enriched lava.");
        stellurgyConfig.lavaCentrifugeTime  = config.getInt("lavaCentrifugeTime", Configuration.CATEGORY_GENERAL, 50,0,999999,"Time used to process 250 mB of enriched lava.");
        stellurgyConfig.crystalliserMaximumGravity = (float) config.get(Configuration.CATEGORY_GENERAL, "crystalliserMaximumGravity", 0f, "Maximum gravity where the crystalliser works. Set 0.0 to disable.").getDouble();
        stellurgyConfig.enableLaserDrill = config.get(Configuration.CATEGORY_GENERAL, "EnableLaserDrill", true, "Enable the laser drill.").getBoolean();
        stellurgyConfig.spaceLaserPowerMult = (float) config.get(Configuration.CATEGORY_GENERAL, "LaserDrillPowerMultiplier", 1d, "Multiplier for laser drill power use.").getDouble();
        stellurgyConfig.laserDrillPlanet = config.get(Configuration.CATEGORY_GENERAL, "laserDrillPlanet", false, "If true, laser drill mines blocks below, (false makes it a VoidMiner with improved performance! especially when using Void Cobble: ON in GUI)").getBoolean();
        String[] str = config.getStringList("spaceLaserDimIdBlackList", Configuration.CATEGORY_GENERAL, new String[]{}, "Dimensions where the laser drill cannot mine.");
        stellurgyConfig.enableTerraforming = config.get(Configuration.CATEGORY_GENERAL, "EnableTerraforming", true, "Enable terraforming blocks and items.").getBoolean();
        stellurgyConfig.terraformSpeed = config.get(Configuration.CATEGORY_GENERAL, "terraformMult", 1f, "Multiplier for atmosphere change speed").getDouble();
        //stellurgyConfig.terraformPlanetSpeed = config.get(Configuration.CATEGORY_GENERAL, "terraformBlockPerTick", 1, "Max number of blocks allowed to be changed per tick").getInt();
        stellurgyConfig.terraformRequiresFluid = config.get(Configuration.CATEGORY_GENERAL, "TerraformerRequiresFluids", true, "Require fluids to run the Terraformer.").getBoolean();
        stellurgyConfig.terraformliquidRate = config.get(Configuration.CATEGORY_GENERAL, "TerraformerFluidConsumeRate", 40, "mB/t used by the Terraformer.").getInt();
        stellurgyConfig.allowTerraformNonStellurgy = config.get(Configuration.CATEGORY_GENERAL, "allowTerraformingNonStellurgyWorlds", false, "Allow terraforming in non-Stellurgy dimensions, including the overworld.").getBoolean();
        stellurgyConfig.enableGravityController = config.get(Configuration.CATEGORY_GENERAL, "enableGravityMachine", true, "Enable the gravity controller.").getBoolean();
        stellurgyConfig.allowNonStellurgyBiomesInTerraforming = config.get(Configuration.CATEGORY_GENERAL, "allowNonStellurgyBiomesInTerraforming", false, "non-Stellurgy biomes from mods with custom world gen cannot be decorated in terraforming. If you want fully decorated terraforming with only default biomes, set this to false").getBoolean();
        stellurgyConfig.enableOrbitalRegistry = config.get(Configuration.CATEGORY_GENERAL,"EnableOrbitalRegistry",true, "Enable the orbital registry.").getBoolean();


        //Oxygen
        stellurgyConfig.breathingRequiresO2 = config.get(OXYGEN, "breathingRequiresO2", true, "Living things need oxygen: they breathe it into CO2, a suit spends its tank, and air with too little or too much of it hurts. When false none of that happens to anybody, but life support still runs — zones keep their gases and machines still move them. Vacuum, pressure and heat are not affected.").getBoolean();
        stellurgyConfig.enableToxicity = config.get(OXYGEN, "enableToxicity", true, "Poisons in the air build up a dose in whoever breathes them and hurt past it. When false nobody takes a dose or poison damage, and the air still holds and reports its poisons.").getBoolean();
        stellurgyConfig.vacuumDamage = config.get(OXYGEN, "vacuumDamage", 1, "Damage taken per second in a vacuum.").getInt();
        stellurgyConfig.overrideGCAir = config.get(OXYGEN, "OverrideGCAir", true, "Disable Galacticraft air and use Stellurgy oxygen on GC planets.").getBoolean();
        stellurgyConfig.oxygenVentConsumptionMult = config.get(OXYGEN, "oxygenVentConsumptionMultiplier", 1f, "Multiplier for oxygen vent O2 use per tick.").getDouble();
        stellurgyConfig.oxygenVentPowerMultiplier = config.get(OXYGEN, "OxygenVentPowerMultiplier", 1.0f, "Multiplier for oxygen vent power use.", 0, Float.MAX_VALUE).getDouble();
        stellurgyConfig.spaceSuitOxygenTime = config.get(OXYGEN, "spaceSuitO2Buffer", 30, "Maximum suit O2 buffer time in minutes.").getInt();
        stellurgyConfig.suitTankCapacity = (float) config.get(OXYGEN, "suitTankCapacity", 1.0f, "Multiplier for suit extra tank capacity.", 0, Float.MAX_VALUE).getDouble();
        stellurgyConfig.scrubberRequiresCartrige = config.get(OXYGEN, "scrubberRequiresCartrige", true, "Require cartridges for oxygen scrubbers.").getBoolean();
        stellurgyConfig.lifeSupportMinPartialO2 = partialPressure(config, "lifeSupportMinPartialO2", 160000, "Oxygen partial pressure below which a zone stops being breathable, in millionths of an atmosphere (210000 is sea-level air).");
        stellurgyConfig.lifeSupportCombustionMinPartialO2 = partialPressure(config, "lifeSupportCombustionMinPartialO2", 150000, "Oxidiser partial pressure below which nothing will burn, in millionths of an atmosphere. This is NOT the breathing threshold and must not be set to it: a room can be too thin to breathe and still light a torch, which is why the two are separate numbers. Real materials stop burning a little below where a person stops coping, which is where the default sits. Set it to 0 and nothing burns anywhere.");
        stellurgyConfig.lifeSupportMaxPartialO2 = partialPressure(config, "lifeSupportMaxPartialO2", 300000, "Oxygen partial pressure above which a zone becomes toxic and fire-prone, in millionths of an atmosphere.");
        stellurgyConfig.lifeSupportRespirationRate = partialPressure(config, "lifeSupportRespirationRate", 2000, "Oxygen a single crew member turns into CO2 each second, in millionths of an atmosphere times the zone volume in blocks. Larger rooms therefore last proportionally longer.");
        stellurgyConfig.lifeSupportAirHeatCapacity = config.get(OXYGEN, "lifeSupportAirHeatCapacity", 40, "How much heat one block of air at one atmosphere absorbs per kelvin. This is what makes a compartment a heat reservoir rather than an empty space: a big pressurised room warms slowly and holds the warmth, a small or half-pressurised one swings fast, and a vacuum holds nothing at all. Set it to 0 and air stops carrying heat, which leaves every zone reading ambient forever.", 0, Integer.MAX_VALUE).getInt();
        stellurgyConfig.lifeSupportRecirculatorRate = partialPressure(config, "lifeSupportRecirculatorRate", 6000, "CO2 a single recirculator turns back into oxygen each second, in millionths of an atmosphere. At the default it keeps up with three crew in a room of any size.");
        stellurgyConfig.lifeSupportRecirculatorPower = config.get(OXYGEN, "lifeSupportRecirculatorPower", 400, "Power a recirculator draws per operation. Reversing combustion is endothermic: the energy cost is the point, not a tax.", 0, Integer.MAX_VALUE).getInt();
        stellurgyConfig.lifeSupportCarbonPerDust = partialPressure(config, "lifeSupportCarbonPerDust", 60000, "CO2 that must be regenerated before one carbon dust is produced, in millionths of an atmosphere. At the defaults a recirculator running flat out yields a dust every ten seconds.");
        stellurgyConfig.lifeSupportScrubberRate = partialPressure(config, "lifeSupportScrubberRate", 2000, "CO2 a single scrubber beside a sealed oxygen vent absorbs from the vent's room each second, in the same unit as lifeSupportRespirationRate. The default is one crew member's breath, so N scrubbers keep up with N crew; that crew count is a progression choice, not a measurement. The CO2 is gone: a scrubber returns no oxygen, which is what the recirculator is for.");
        stellurgyConfig.lifeSupportScrubberCo2PerCharge = partialPressure(config, "lifeSupportScrubberCo2PerCharge", 20000, "CO2 one charge of a scrubber cartridge absorbs, in the same unit as lifeSupportScrubberRate. The default is ten seconds at the default rate, which keeps a cartridge's life under full load what it was before scrubbers touched the air: one charge every 200 ticks. A scrubber in a room with nothing to absorb spends nothing.");
        stellurgyConfig.jettisonPortIntervalTicks = config.get(OXYGEN, "jettisonPortIntervalTicks", 20, "How often a jettison port tries to throw its contents overboard, in ticks. This is a duty cycle, not a throttle on how much leaves: the port ejects whatever stack it holds, so a faster port empties a busier scrubber line rather than exporting more per firing.", 1, Integer.MAX_VALUE).getInt();
        stellurgyConfig.jettisonPortClearance = config.get(OXYGEN, "jettisonPortClearance", 3, "How many blocks in front of a jettison port must be empty before it will fire. The port refuses rather than firing into a wall, and it HOLDS its cargo while blocked instead of voiding it, so the only cost of a badly placed port is that nothing leaves.", 1, 64).getInt();
        stellurgyConfig.lifeSupportFluidPerAtmBlock = config.get(OXYGEN, "lifeSupportFluidPerAtmBlock", 1000, "How many millibuckets of gas one whole atmosphere of partial pressure amounts to in ONE block of room. This is the exchange rate between air in a room and gas in a pipe: at the default, emptying a 20-block cabin of its 0.21 atm of oxygen yields 4200 mB.", 1, Integer.MAX_VALUE).getInt();
        stellurgyConfig.lifeSupportSeparatorRate = partialPressure(config, "lifeSupportSeparatorRate", 20000, "Partial pressure a separator moves between room and tank each second, in millionths of an atmosphere. At the default it clears a badly stale room in under ten seconds.");
        stellurgyConfig.lifeSupportSeparatorPower = config.get(OXYGEN, "lifeSupportSeparatorPower", 300, "Power a separator draws per operation.", 0, Integer.MAX_VALUE).getInt();
        stellurgyConfig.lifeSupportPlantRate = config.get(OXYGEN, "lifeSupportPlantRate", 240000, "Regeneration a central life-support plant can supply to its ventilation network each second, in millionths of an atmosphere TIMES the served zone's volume in blocks. Unlike the per-room recirculator this is an absolute amount of gas, so one number can be split across rooms of different sizes: at the default it clears about 13000 millionths per second from a 18-block cabin, or half that from two of them.", 0, Integer.MAX_VALUE).getInt();
        stellurgyConfig.lifeSupportPlantPower = config.get(OXYGEN, "lifeSupportPlantPower", 2000, "Power a central plant draws per second while regenerating at its full rate; a plant running below capacity draws proportionally less. Reversing combustion is endothermic — the energy is the mechanic, and centralising it is what buys the better rate.", 0, Integer.MAX_VALUE).getInt();
        stellurgyConfig.lifeSupportPlantCarbonPerDust = config.get(OXYGEN, "lifeSupportPlantCarbonPerDust", 1200000, "Regeneration work a central plant must do before one carbon dust is produced, in the same millionths-times-blocks unit as lifeSupportPlantRate. The default is the per-room figure scaled to a nominal 20-block cabin, so a plant and a recirculator yield the same dust for the same gas.", 1, Integer.MAX_VALUE).getInt();
        stellurgyConfig.lifeSupportDuctThroughput = config.get(OXYGEN, "lifeSupportDuctThroughput", 120000, "Regeneration work one ventilation duct block will carry each second, in the same unit as lifeSupportPlantRate. This is a THROUGHPUT, not a gas content: the duct carries a rate, and running a second line is how a ship supports more crew.", 0, Integer.MAX_VALUE).getInt();
        stellurgyConfig.lifeSupportPortFePerTick = config.get(OXYGEN, "lifeSupportPortFePerTick", 1, "Energy a ventilation port draws each tick to hold its zone, in FE per tick. It is not wired to the port: a life-support plant pays it through the ventilation ducts that connect the two, so a port with no plant behind it holds no zone. 0 makes ports free, but they still need a plant on their network.", 0, Integer.MAX_VALUE).getInt();
        stellurgyConfig.lifeSupportBreachVentRate = partialPressure(config, "lifeSupportBreachVentRate", 50000, "How fast a breached zone loses its air to space, in millionths of an atmosphere per second across all three gases. At the default a sea-level room empties in about twenty seconds, which is the window a player has to close a bulkhead or patch the hull. 0 disables venting: a breached room then keeps its air, which is the pre-3.0.0 behaviour.");
        stellurgyConfig.dropExTorches = config.get(OXYGEN, "dropExtinguishedTorches", false, "Drop an extinguished torch instead of a vanilla torch, when breaking an extinguished torch.").getBoolean();
        sealableBlockWhiteList = config.getStringList("sealableBlockWhiteList", OXYGEN, new String[]{}, "Blocks that should count as sealable. Format: modid:block  for example \"minecraft:chest\"");
        sealableBlockBlackList = config.getStringList("sealableBlockBlackList", OXYGEN, new String[]{}, "Blocks that should not count as sealable.  Format: modid:block  for example \"minecraft:chest\"");
        breakableTorches = config.getStringList("torchBlocks", OXYGEN, new String[]{}, "Blocks treated like torches in non-combustible atmospheres. Placement is blocked, and existing blocks are broken and dropped. Format: modid:block");
        entityList = config.getStringList("entityAtmBypass", OXYGEN, new String[]{}, "List entities not affected by atmosphere effects");

        //Station
        stellurgyConfig.spaceDimId = config.get(STATION, "spaceStationId", -2, "Dimension ID used for space stations.").getInt();
        stellurgyConfig.stationSize = config.get(STATION, "SpaceStationBuildRadius", 1024, "Maximum space station build radius. Should be a power of 2 (512, 1024, 2048, 4096, ...).  CAUTION: CHANGING THIS OPTION WILL DAMAGE EXISTING STATIONS!!!").getInt();
        stellurgyConfig.allowZeroGSpacestations = config.get(STATION, "allowZeroGSpacestations", false, "Allow stations to fully disable gravity.  It's possible to get stuck and require teleport, you have been warned!").getBoolean();
        stellurgyConfig.travelTimeMultiplier = (float) config.get(STATION, "warpTravelTime", 1f, "Multiplier for warp travel time").getDouble();

        //Missions
        stellurgyConfig.asteroidMiningTimeMult = config.get(MISSION, "miningMissionTmeMultiplier", 1.0, "Multiplier for mining mission time.").getDouble();
        stellurgyConfig.gasCollectionMult = config.get(MISSION, "gasMissionMultiplier", 1.0, "Multiplier for gas mission time.").getDouble();
        stellurgyConfig.gasHarvestAmountMultiplier = config.get(
            MISSION, "gasHarvestAmountMultiplier", 1.0,
            "Per-mission harvest cap = 64,000 mB × multiplier. Ignored if gasHarvestInfinite=true."
        ).getDouble();

        stellurgyConfig.gasHarvestInfinite = config.get(
            MISSION, "gasHarvestInfinite", false,
            "True sets gasHarvestAmount = MaxInt (2,147,483,647 mB), and ignores the 'gasHarvestAmountMultiplier'"
        ).getBoolean();


        //Energy Production
        stellurgyConfig.shipHeat = config.get(HEAT, "shipHeat", true, "Machines make waste heat, coolant loops carry it and hold it, and a loop that is given more than it can get rid of warms up. When false no block makes or stores heat and every loop reads as ambient, which is how the game behaved before the thermal system existed.").getBoolean();
        stellurgyConfig.shipHeatAmbientKelvin = config.get(HEAT, "shipHeatAmbientKelvin", 293, "The temperature a coolant loop sits at when it is holding nothing, in kelvin. 293 is room temperature; everything a loop reads above this is heat it is currently carrying.", 1, 5000).getInt();
        stellurgyConfig.shipHeatPipeCapacity = config.get(HEAT, "shipHeatPipeCapacity", 20, "How much heat one pipe block absorbs per kelvin. At the default a pipe holds about 20000 heat units over the roughly 1000 K between room temperature and the point where a loop starts damaging what it runs through, so a long run is a real heat sink and not just a wire.", 0, Integer.MAX_VALUE).getInt();
        stellurgyConfig.shipHeatAccumulatorCapacity = config.get(HEAT, "shipHeatAccumulatorCapacity", 1000, "How much heat one accumulator block absorbs per kelvin — fifty pipes' worth at the defaults, which is what makes a block of it worth the space when a jump has to be survived.", 0, Integer.MAX_VALUE).getInt();
        stellurgyConfig.shipHeatPipeThroughput = config.get(HEAT, "shipHeatPipeThroughput", 200000, "Heat one pipe block will carry each second between the loop and what is attached to it. The default is exactly one mid-game reactor at cruise, so a second reactor wants a second run rather than a longer one.", 0, Integer.MAX_VALUE).getInt();
        stellurgyConfig.shipHeatWasteFraction = config.get(HEAT, "shipHeatWasteFraction", 300, "How much of the energy a machine spends comes back out as waste heat a coolant loop can pick up, in thousandths. The rest is taken away by the air around the machine, which is why a planetside base needs no thermal build at all.", 0, 1000).getInt();
        stellurgyConfig.shipHeatRadiatorCellPower = config.get(HEAT, "shipHeatRadiatorCellPower", 6000, "How much heat one radiating cell sheds each second when the loop is at the reference temperature and the surroundings are cold. This is a POINT ON THE CURVE, not the whole answer: the law is quartic, so the same cell sheds far less at a cooler loop temperature and far more at a hotter one, and that is what makes a chiller worth building.", 0, Integer.MAX_VALUE).getInt();
        stellurgyConfig.shipHeatRadiatorReferenceKelvin = config.get(HEAT, "shipHeatRadiatorReferenceKelvin", 500, "The loop temperature at which a cell sheds exactly shipHeatRadiatorCellPower, in kelvin. Move this and every cell is rescaled without the curve changing shape.", 1, 5000).getInt();
        stellurgyConfig.shipHeatRadiatorClearance = config.get(HEAT, "shipHeatRadiatorClearance", 10, "How many blocks must be empty in front of a radiating cell for it to work. Anything in the way means the heat comes back to the ship, which is deliberately not modelled — the cell simply stops working and reports where the obstruction is. Rejection is meant to be built with margin, so losing one cell is a degradation and not a failure.", 1, 64).getInt();
        stellurgyConfig.shipHeatChillerThroughput = config.get(HEAT, "shipHeatChillerThroughput", 120000, "How much heat one chiller shifts from its cold loop to its hot loop each second. This is a SIZE, not a temperature: the chiller sets no temperature anywhere — the heat it moves piles up in the hot loop against that loop's own capacity, and the temperature follows. Build a bigger hot loop and it climbs slower; build more radiators on it and it stops climbing.", 0, Integer.MAX_VALUE).getInt();
        stellurgyConfig.shipHeatChillerCopFraction = config.get(HEAT, "shipHeatChillerCopFraction", 500, "What fraction of the thermodynamic ideal a real chiller manages, in thousandths. The ideal is Carnot — the efficiency falls as the gap between the two loops widens — so this number cannot buy a machine past physics, only closer to it. 500 is a real machine at half of ideal. The work it spends JOINS the hot side, so the radiators must shed the heat plus the work, and driving the hot loop further costs more for less. That is the tier's ceiling, and it appears rather than being placed.", 1, 1000).getInt();
        stellurgyConfig.shipHeatChillerCapacity = config.get(HEAT, "shipHeatChillerCapacity", 200, "How much heat one chiller absorbs per kelvin. A chiller is a lump of metal and refrigerant bolted to its hot loop, so it counts as part of that loop's thermal mass — worth ten pipes at the defaults, which is why a hot side with a chiller on it climbs noticeably slower than its pipes alone would explain.", 0, Integer.MAX_VALUE).getInt();
        stellurgyConfig.shipHeatStarFluxReferenceKelvin = config.get(HEAT, "shipHeatStarFluxReferenceKelvin", 278, "The temperature a radiating cell settles at in unshaded starlight as strong as Earth's, in kelvin. This is how much of a star reaches a ship, stated as a temperature so it can be compared with a loop's own directly: a cell in that much sunlight cannot be cooled below this by any amount of area, and a closer or brighter star scales it. Set it to 0 and starlight stops warming ships entirely.", 0, 5000).getInt();
        stellurgyConfig.shipHeatShieldAttenuation = config.get(HEAT, "shipHeatShieldAttenuation", 900, "How much of the heat arriving from outside a raised shield keeps off the ship, in thousandths. A shield is sunscreen and never a wall: whatever is written here, the game refuses to let it reach all of it, so a ship parked in a star always heats — slowly, but always. The shield pays for this with its generator's draw, which is itself a heat source, so tanking a star costs you twice.", 0, 1000).getInt();
        stellurgyConfig.planetGreenhouseCeilingAtm = config.get(PLANET, "planetGreenhouseCeilingAtm", 100, "The thickest atmosphere, in whole atmospheres, whose greenhouse warming is still worked out from the correlation rather than held constant. The correlation is a fit through two known worlds - Earth at 1 and Venus at 92 - so 100 is just past the last of them and everything beyond is extrapolation nobody has measured. Raising this does not make hot worlds hotter in any way that is known to be right; it makes the game answer confidently about pressures the formula was never shown. Set it to 0 to switch the bound off entirely and extrapolate without limit, which is what the game did before the pressure ceiling moved.", 0, 100000).getInt();
        stellurgyConfig.shipHeatChillerMaxCop = config.get(HEAT, "shipHeatChillerMaxCop", 50, "The most heat one unit of a chiller's work can ever move, however small the gradient it is working against. Carnot says that number climbs without limit as the two loops approach the same temperature, and a machine that can move unlimited heat for nothing is not a machine - so this is where the curve is cut off. It decides how cheap a chiller gets in the easy case and nothing else: across any gradient worth building for, the formula answers well below it.", 1, 1000).getInt();
        stellurgyConfig.shipHeatCabinConductionFraction = config.get(HEAT, "shipHeatCabinConductionFraction", 2, "How much of the gap between a coolant loop and the air of a sealed room its blocks stand in crosses into that air each second, in thousandths. This is the only thing that makes a hot ship hot to be INSIDE: pipes are objects in a room, and a room with hot pipes in it warms up. At the default a loop that has lost its cooling takes about half a minute to make its cabin hostile, which is time to notice and do something. Set it to 0 and a loop never warms the air around it - the crew rungs below then never fire, and the ship cooks only its own hull.", 0, 1000).getInt();
        stellurgyConfig.shipHeatCrewVeryHotKelvin = config.get(HEAT, "shipHeatCrewVeryHotKelvin", 323, "How hot a compartment's AIR has to get, in kelvin, before the room itself starts hurting the crew - the same hostile atmosphere a scorching planet presents, with the same suit protecting against it. 323 K is 50 degrees C: survivable in a suit, not survivable in shirtsleeves. This is a SHIP's air, measured where a person is standing, which is why it sits far below the temperature at which a whole PLANET is called too hot - that number is an average over a globe with cold latitudes in it. Set it to 0 to switch this rung off; with shipHeat off nothing warms a room and it never fires anyway.", 0, 5000).getInt();
        stellurgyConfig.shipHeatCrewSuperheatedKelvin = config.get(HEAT, "shipHeatCrewSuperheatedKelvin", 373, "How hot a compartment's AIR has to get, in kelvin, before it is not merely hostile but lethal - four times the damage of the rung below it. 373 K is boiling water; a suit still buys time, and nothing else does. It sits below the temperature at which things catch fire, so a room burns its crew before it burns itself. Set it to 0 to switch this rung off.", 0, 5000).getInt();
        stellurgyConfig.shipHeatDriveRefusalKelvin = config.get(HEAT, "shipHeatDriveRefusalKelvin", 773, "How hot the coolant loop a hyperspace drive is bolted to may get, in kelvin, before the drive refuses to fire. 773 K is 500 degrees C - hundreds of degrees above anything a crew survives, so a ship that trips this was flown with nobody left to care. The refusal is FREE: it is raised before the capacitor burst, so a pilot who is told this has lost nothing but the trip. Set it to 0 to switch the check off entirely.", 0, 5000).getInt();
        stellurgyConfig.shipHeatSlugMarginKelvin = config.get(HEAT, "shipHeatSlugMarginKelvin", 100, "How far below its own melting point a heat slug is charged, in kelvin. This is the margin that keeps a slug a solid object you can eject and pick up again instead of a puddle in the machine, so it is subtracted from every material's usable span. Raise it for a safer, weaker slug.", 0, 5000).getInt();
        stellurgyConfig.shipHeatSlugJoulesPerUnit = config.get(HEAT, "shipHeatSlugJoulesPerUnit", 1000, "How many real joules one heat unit stands for when a slug's capacity is worked out from its material. The materials table is ordinary physics in SI, and this is the single place those joules become the currency the rest of the thermal system deals in - so it scales every slug at once and changes no material's standing relative to another. At the default a litre of iron is worth about five thousand heat units.", 1, Integer.MAX_VALUE).getInt();
        stellurgyConfig.shipHeatMeltCheckTicks = config.get(HEAT, "shipHeatMeltCheckTicks", 20, "How often a coolant loop looks at what it is cooking, in ticks. This is the melting rung: past a material's own limit the block stops being damaged and is gone. Checking is the expensive half - it walks the loop and its neighbours - so this is the freshness of that answer rather than a balance number. Raising it does not make a ship safer, only slower to lose its hull.", 1, 1200).getInt();
        stellurgyConfig.shipHeatDumpThroughput = config.get(HEAT, "shipHeatDumpThroughput", 40000, "How much heat an emergency dump pushes into the slug it is charging each second. Deliberately well under what a radiator array sheds: the dump buys seconds while something else is fixed, and a value large enough to keep a ship cool would turn an emergency into a cooling system that eats iron.", 0, Integer.MAX_VALUE).getInt();
        stellurgyConfig.shipHeatHullSkinFraction = config.get(HEAT, "shipHeatHullSkinFraction", 350, "How much of the difference between the air a ship encloses and the space outside reaches its outer skin, in thousandths. A hull is insulated, so the skin sits far colder than the cabin — and that gap is the only reason shutting the radiators is worth doing, because an uninsulated hull out-glows the array on its own. At the default a ship running silent is found at roughly a tenth of the distance the same ship is found at with its radiators open. It can never reach zero: whatever is written here, some of the ship's warmth gets out, so going dark buys range and never invisibility.", 0, 1000).getInt();
        stellurgyConfig.shipHeatDumpTriggerKelvin = config.get(HEAT, "shipHeatDumpTriggerKelvin", 700, "How hot the loop must be before an emergency dump will run at all, in kelvin. Below this it does nothing and costs nothing - a ship that is coping does not throw its cargo overboard. 700 K is far past anything a working ship sits at and short of the temperature at which the drive refuses to fire.", 1, 5000).getInt();
        stellurgyConfig.solarGeneratorMult = config.get(ENERGY, "solarGeneratorMultiplier", 1, "Power produced per tick by the solar generator.").getInt();
        stellurgyConfig.microwaveRecieverMulitplier = (float) config.get(ENERGY, "MicrowaveRecieverMultiplier", 1f, "Multiplier for microwave receiver power output.").getDouble();
        stellurgyConfig.defaultItemTimeBlackHole = config.get(ENERGY, "defaultBurnTime", 500, "Burn time in ticks for items not listed in blackHoleTimings.").getInt();
        stellurgyConfig.blackHolePowerMultiplier = config.get(ENERGY, "blackHoleGeneratorMultiplier", 1, "Multiplier for black hole generator power output.").getInt();
        blackHoleGeneratorTiming = config.get(ENERGY, "blackHoleTimings", new String[]{"minecraft:stone;1", "minecraft:dirt;1", "minecraft:netherrack;1", "minecraft:cobblestone;1"}, "List of blocks and burn times for the black hole generator. Format: modid:block:meta;ticks where meta is optional").getStringList();

        //Planet
        stellurgyConfig.planetsMustBeDiscovered = config.get(PLANET, "planetsMustBeDiscovered", false, "Planets must be discovered in the warp controller before being visible").getBoolean();
        stellurgyConfig.planetDiscoveryChance = config.get(PLANET, "planetDiscoveryChance", 5, "Chance of planet discovery in the warp controller, chance is 1/n", 1, Integer.MAX_VALUE).getInt();
        stellurgyConfig.telescopeLimitingMagnitude = config.get(PLANET, "telescopeLimitingMagnitude", DEFAULT_TELESCOPE_LIMITING_MAGNITUDE, "How faint a star an observatory can still register, in APPARENT MAGNITUDE - the scale astronomy measures brightness on, where SMALLER IS BRIGHTER and five magnitudes is a factor of a hundred in received light. This is the instrument's aperture, and it is what its reach is derived FROM: a survey walks outwards only as far as the brightest star it could possibly see would still be above this limit, so a better aperture reaches farther by seeing more rather than by being told a bigger number. Reference points: 6 is roughly the naked eye, 8 (the default) reaches a sun-like star at about 160 light years and a blue giant at 1360, and each 5 magnitudes multiplies every one of those distances by ten. Dust counts against the same limit, so a cloud in the way shortens the reach in exactly the way distance does.", -30d, 40d).getDouble();
        stellurgyConfig.telescopeConeHalfAngleDegrees = config.get(PLANET, "telescopeConeHalfAngleDegrees", DEFAULT_TELESCOPE_CONE_HALF_ANGLE_DEGREES, "How wide a patch of sky one pointing covers, in DEGREES from the axis to the edge. A survey is a cone with its apex at the observatory, so this is its opening: narrow in degrees, and still enormous at the far end because the same angle subtends more space the farther out it is read. Widening it multiplies the work by the SQUARE, so a pointing twice as wide is four times the survey.", 0.001d, 89d).getDouble();
        stellurgyConfig.telescopeResolveMarginMagnitudes = config.get(PLANET, "telescopeResolveMarginMagnitudes", DEFAULT_TELESCOPE_RESOLVE_MARGIN_MAGNITUDES, "How much BRIGHTER than telescopeLimitingMagnitude a system must be before the instrument can make out what is IN it, in magnitudes. Seeing that a point of light is there and measuring what orbits it are not the same observation: detection is called at a signal-to-noise of about 5, while a usable spectrum wants about 100, and since signal-to-noise grows as the square root of the photons collected that is a flux ratio of 400 - i.e. 6.5 magnitudes. Everything the survey registers but cannot resolve is still written down as a POSITION, so a weak instrument hands back a list of places worth flying to and a better one tells you what is at them. Set it to 0 to make anything detectable also resolvable, which is how the survey behaved before the distinction existed.", 0d, 40d).getDouble();
        stellurgyConfig.telescopeScanMaxCells = config.get(PLANET, "telescopeScanMaxCells", DEFAULT_TELESCOPE_SCAN_MAX_CELLS, "Hard ceiling on how many LOOKS one survey may hold (one per star territory along the pointing, not one per cell of sky crossed). A pointing that would exceed it is SHORTENED until it fits, exactly as its width used to be narrowed - a sweep may be long, but never unbounded. At the shipped aperture and opening a full-depth pointing holds about 77 000 looks, so this leaves room to raise the aperture a little before the ceiling starts cutting the reach.", 1, Integer.MAX_VALUE).getInt();
        stellurgyConfig.telescopeScanBaseTicks = config.get(PLANET, "telescopeScanBaseTicks", DEFAULT_TELESCOPE_SCAN_BASE_TICKS, "Ticks one STEP of a survey takes. A pointing's cost in time is carried by how many steps it needs and not by how far it reaches, because a deeper pointing already holds proportionally more looks. Only applies with planetsMustBeDiscovered on; without research, an observation is instant.", 0, Integer.MAX_VALUE).getInt();
        stellurgyConfig.telescopeScanCellsPerStep = config.get(PLANET, "telescopeScanCellsPerStep", DEFAULT_TELESCOPE_SCAN_CELLS_PER_STEP, "How many looks one step of a survey resolves. This is the bound that stops a sweep from enumerating everything at once. With the shipped defaults a full-depth pointing is about 600 steps, i.e. roughly ten minutes of clear night.", 1, Integer.MAX_VALUE).getInt();
        stellurgyConfig.telescopeSurveyDataPerStep = config.get(PLANET, "telescopeSurveyDataPerStep", 0, "Distance data one step of a survey consumes, drawn from the observatory's data buses the same way its asteroid scan draws. A step with too little data waits rather than resolving, so an unfed instrument stalls instead of working for free. Zero (the default) means a survey costs nothing - what it should cost is a balance question, not a mechanic one.", 0, Integer.MAX_VALUE).getInt();
        stellurgyConfig.telescopeObscuredAtMagnitudes = config.get(PLANET, "telescopeObscuredAtMagnitudes", 5d, "How much dust a survey can see THROUGH, in magnitudes of visual extinction - the unit astronomy measures interstellar dust in. A nebula between the instrument and what it is looking at dims it; past this much, the survey can still tell that a system is there but can no longer make out its bodies, and writes the bare coordinate instead. The default is the real boundary at which faint objects behind a cloud disappear: ~1 magnitude is noticeable dimming, ~5 is where things start vanishing, ~10 is an opaque dark cloud. Raise it to see through thicker clouds; set it to 0 to turn concealment off entirely.", 0d, Double.MAX_VALUE).getDouble();
        stellurgyConfig.telescopePassiveRadiusSteps = config.get(PLANET, "telescopePassiveRadiusSteps", 1, "How far, in STAR TERRITORIES, the passive local radar reaches around the observatory's own. 0 is the system you are standing in and nothing else; 1 (the default) adds the twenty-six territories around it. Territories and not cells: one look already yields every body of the system that owns it, so a radius counted in cells never reached a neighbour at all - two cells was a fifth of the way to the innermost planet of the system the instrument was already standing in. Passive costs nothing; the pointing is what looks far away.", 0, Integer.MAX_VALUE).getInt();
        stellurgyConfig.minDimension = config.getInt("minDimension", PLANET, 2, -127, 8000, "Lowest dimension ID that can be used for planets.");
        stellurgyConfig.canPlayerRespawnInSpace = config.get(PLANET, "allowPlanetRespawn", false, "Allow bed respawn on planets with breathable air.").getBoolean();
        stellurgyConfig.forcePlayerRespawnInSpace = config.get(PLANET, "forcePlanetRespawn", false, "Allow bed respawn on planets even without breathable air. Requires 'allowPlanetRespawn=true'.").getBoolean();
        stellurgyConfig.perDimWorldInfo = config.get(PLANET, Constants.CONFIG_KEY_PER_DIM_WORLD_INFO, true, "Master switch for Stellurgy's per-dimension WorldInfo overrides on planets: per-planet weather AND per-planet time-of-day / working beds. When false, planets use the vanilla shared-overworld WorldInfo and NONE of the weather/time mixins are woven — fully classic behaviour. The sub-toggles below (enableCustomPlanetWeather) only take effect when this is true.").getBoolean();
        stellurgyConfig.enableCustomPlanetWeather = config.get(PLANET, "enableCustomPlanetWeather", true, "Sub-toggle of perDimWorldInfo (no effect when that is false): if true, each Stellurgy planet has its own weather state (rain, thunder, /weather, isRaining); if false, weather delegates to the overworld while per-dimension time-of-day still applies.").getBoolean();
        stellurgyConfig.allowTimeSkipOnPlanets = config.get(PLANET, "allowTimeSkipOnPlanets", false, "Whether a bed or /time may SKIP a planet's time of day forward. Off by default: a planet's day is the turning of a body in an orbit, so its night is lived through rather than slept away. This does NOT stop the day cycle - a planet still turns at its own rotational period - and a bed still sets your spawn point. Turn it on to keep the classic Minecraft conveniences everywhere. Space stations and asteroid fields are governed by neither this nor allowTimeSkipOnOverworld.").getBoolean();
        stellurgyConfig.allowTimeSkipOnOverworld = config.get(PLANET, "allowTimeSkipOnOverworld", true, "The same, for the overworld, where it is ON by default: a player who has not left home yet is still playing Minecraft, and beds and /time work the way he expects. Turn it off to hold the overworld to the same rule as the planets.").getBoolean();
        stellurgyConfig.logPlanetWeatherWrapping = config.get(PLANET, "logPlanetWeatherWrapping", true, "Log an info line every time a Stellurgy planet's WorldInfo is wrapped for per-dimension weather. Useful for diagnosing weather-wrapping issues; safe to disable in production.").getBoolean();
        stellurgyConfig.forcePlanetWeatherWorldInfoWrapper = config.get(PLANET, "forcePlanetWeatherWorldInfoWrapper", false, "Force per-dimension weather wrapping on every secondary (non-overworld) dimension, including non-Stellurgy dims of other mods. Compatibility/debug flag — do NOT enable unless you know exactly what you are doing.").getBoolean();
        stellurgyConfig.minAtmosphereDensityForRain = (float) config.get(PLANET, "minAtmosphereDensityForRain", 75d, "Minimum atmosphere density (0-100 scale, same as planet atmosphereDensity) required for rain/snow and thunder on a planet. Below this, rain is suppressed regardless of the planet's weather markers, and thunder cannot occur. Thin/airless worlds stay clear.", 0d, 200d).getDouble();
        stellurgyConfig.acidRainDamage = (float) config.get(PLANET, "acidRainDamage", 1d, "Damage dealt to an unprotected player standing under open sky while it rains on a planet whose rain is acidic (acidicRain=true in the planet definition). Set 0 to disable acid-rain damage.", 0d, Float.MAX_VALUE).getDouble();
        stellurgyConfig.acidRainDamageInterval = config.get(PLANET, "acidRainDamageInterval", 20, "Ticks between successive acid-rain damage applications (20 = once per second).", 1, Integer.MAX_VALUE).getInt();
        stellurgyConfig.blackListAllVanillaBiomes = config.getBoolean("blackListVanillaBiomes", PLANET, false, "Prevent vanilla biomes from spawning on planets.");
        stellurgyConfig.maxBiomesPerPlanet = config.get(PLANET, "maxBiomesPerPlanet", 99, "Maximum unique biomes per planet.").getInt();

        //Client
        stellurgyConfig.stationSkyOverride = config.get(CLIENT, "StationSkyOverride", true, "Use Stellurgy's custom skybox on space stations").getBoolean();
        stellurgyConfig.planetSkyOverride = config.get(CLIENT, "PlanetSkyOverride", true, "Use Stellurgy's custom skybox on planets").getBoolean();
        stellurgyConfig.skyOverride = config.get(CLIENT, "overworldSkyOverride", true, "Use Stellurgy's custom skybox in the overworld.").getBoolean();
        stellurgyConfig.skyBodyLabels = config.get(CLIENT, "skyBodyLabels", true,
                "Label each body in a space cell's sky with its name and distance.").getBoolean();
       // stellurgyConfig.overworldsealevelterraforming = config.get(CLIENT, "overworldSealvlTerraforming", true).getBoolean();
        stellurgyConfig.advancedVFX = config.get(CLIENT, "advancedVFX", true, "Advanced visual effects").getBoolean();
        stellurgyConfig.enableNausea = config.get(CLIENT, "EnableAtmosphericNausea", true, "Allows nausea effects in non-standard atmospheres.").getBoolean();
        stellurgyConfig.electricPlantsSpawnLightning = config.get(CLIENT, "electricPlantsSpawnLightning", true, "Should Electric Mushrooms be able to spawn lightning").getBoolean();

        //Performance
        stellurgyConfig.atmosphereHandleBitMask = config.get(PERFORMANCE, "atmosphereCalculationMethod", 3, "BitMask: 0: no threading, radius based; 1: threading, radius based (EXP); 2: no threading volume based; 3: threading volume based (EXP)").getInt();
        stellurgyConfig.oxygenVentSize = config.get(PERFORMANCE, "oxygenVentSize", 32, "Radius of the O2 vent.  if atmosphereCalculationMethod is 2 or 3 then max volume is calculated from this radius.  WARNING: larger numbers can lead to lag").getInt();

        //Movable-ship space subsystem (tier-2 ships). The pool size is the direct perf knob: only this
        //many space "bubble" worlds ever tick at once. GC trims the on-disk store of modified cells.
        //There is NO enableSpaceSubsystem flag: it was removed on 2026-09-18 because space is the
        //mod's subject and not one of its features. An existing config file that still carries the
        //key is harmless - Forge leaves unread keys alone, so nothing has to be migrated.
        stellurgyConfig.spaceCellPoolSize = config.getInt("spaceCellPoolSize", PERFORMANCE, 10, 1, 64, "Number of pre-registered space 'bubble' worlds that can be live (ticking) at once. The direct performance knob for the movable-ship space subsystem.");
        stellurgyConfig.spaceCellGcPolicy = config.getString("spaceCellGcPolicy", PERFORMANCE, "both", "Garbage-collection policy for the on-disk store of modified space cells: age | count | both | never.", new String[]{"age", "count", "both", "never"});
        stellurgyConfig.spaceCellMaxAgeTicks = config.getInt("spaceCellMaxAgeTicks", PERFORMANCE, 1728000, 0, Integer.MAX_VALUE, "Ticks since last visit before an age/both GC deletes a stored space cell (1728000 = 24h at 20 tps).");
        stellurgyConfig.spaceMaxStoredCells = config.getInt("spaceMaxStoredCells", PERFORMANCE, 4096, 0, Integer.MAX_VALUE, "Max modified space cells kept on disk before a count/both GC trims the oldest.");
        stellurgyConfig.spaceHomeSystemCoord = config.getString("spaceHomeSystemCoord", PERFORMANCE, "0,0,0", "Fallback home-system galactic anchor 'sectorX,sectorY,sectorZ' used when a tier-2 ship enters space from a planet that has no placement in the universe registry.");
        stellurgyConfig.spaceTransitOfflineProgress = config.getString("spaceTransitOfflineProgress", PERFORMANCE, "always", "Whether an in-flight tier-2 hyperspace transit keeps advancing while its aboard crew are offline: always | crew-online. 'crew-online' pauses a jump while every aboard crew member is disconnected (gate by ANY crew). Unmanned transits always advance.", new String[]{"always", "crew-online"});

        //Rockets
        stellurgyConfig.rocketRequireFuel = config.get(ROCKET, "rocketsRequireFuel", true, "Require fuel for rockets to fly.").getBoolean();
        stellurgyConfig.canBeFueledByHand = config.get(ROCKET, "canBeFueledByHand", true, "Allow rockets to be fueled by hand.").getBoolean();
        stellurgyConfig.nuclearRocketsRespectArtifactGating = config.get(ROCKET, "nuclearRocketsRespectArtifactGating", true, "Nuclear rocket should respect artifact gating for planets").getBoolean();
        stellurgyConfig.nuclearRocketsRequireArtifactForGatedStations = config.get(ROCKET, "nuclearRocketsRequireArtifactForGatedStations", false, "If true, nuclear rockets that respect artifact gating also require the artifact when targeting a space station inside a gated planetary system. " + "If false, station destinations are exempt to avoid soft-locking players who left the artifact in the station or warp controller." + "This is meant as a Multiplayer / Server strictness-option").getBoolean();
        liquidMonopropellant = config.get(ROCKET, "rocketFuels", new String[]{"rocketfuel;10"}, "List of fluid names for valid monopropellants").getStringList();
        liquidBipropellantFuel = config.get(ROCKET, "rocketBipropellants", new String[]{"hydrogen;10"}, "List of fluid names for valid bipropellant fuels").getStringList();
        liquidBipropellantOxidizer = config.get(ROCKET, "rocketOxidizers", new String[]{"oxygen;10"}, "List of fluid names for valid bipropellant oxidizers").getStringList();
        liquidNuclearWorkingFluid = config.get(ROCKET, "rocketNuclearWorkingFluids", new String[]{"hydrogen;10"}, "List of fluid names for valid nuclear working fluids").getStringList();
        stellurgyConfig.rocketThrustMultiplier = config.get(ROCKET, "thrustMultiplier", 1f, "Multiplier for engine thrust.").getDouble();
        stellurgyConfig.fuelCapacityMultiplier = config.get(ROCKET, "fuelCapacityMultiplier", 1f, "Multiplier for fuel tank capacity.").getDouble();
        stellurgyConfig.nuclearCoreThrustRatio = config.get(ROCKET, "nuclearCoreThrustRatio", 1.0, "Multiplier for nuclear core thrust.").getDouble();
        stellurgyConfig.automaticRetroRockets = config.get(ROCKET, "autoRetroRockets", true, "Setting to false will disable the retrorockets that fire automatically on reentry on both player and automated rockets").getBoolean();
        stellurgyConfig.stationClearanceHeight = config.getInt("stationClearance", ROCKET, 1000, 255, Integer.MAX_VALUE, "Height required to clear a space station. A planet's orbit line is not set here: it is derived from the planet's radius, or stated per planet as <orbitHeight> in the planet file. Burn length here is used by itself when launching from a station to either another station or the same station, or to the planet it is orbiting. It is used in conjunction with the TBI burn when launching to a moon or asteroid");
        stellurgyConfig.transBodyInjection = config.getInt("transBodyInjection", ROCKET, 0, 0, Integer.MAX_VALUE, "How long the burn for trans-body injection is - this is performed soley after entering orbit and is in blocks - WARNING: This property is not taken into account by any machines when determining whether the rocket is fit to fly or not - Rockets that can reach LEO and so are flightworthy may not make TBI and will fall back to the parent planet. When enabled, the burn sequence is [Burn to LEO], [TBI Burn] when launching from a planet to moons or asteroids; and the sequence is [Station clearance burn], [TBI Burn] when launching from a station to a moon or asteroid. This distance varies by object distance");
        stellurgyConfig.asteroidTBIBurnMult = (float) config.get(ROCKET, "asteroidTBIBurnMult", 1.0, "Multiplier for asteroid TBI distance.").getDouble();
        stellurgyConfig.warpTBIBurnMult = (float) config.get(ROCKET, "warpTBIBurnMult", 10.0, "Multiplier for warp TBI distance.").getDouble();
        stellurgyConfig.experimentalSpaceFlight = config.get(ROCKET, "experimentalSpaceFlight", false, "Enable EXPERIMENTAL free flight in space.").getBoolean();
        stellurgyConfig.gravityAffectsFuel = config.get(ROCKET, "gravityAffectsFuels", true, "Make fuel use depend on gravity.").getBoolean();
        stellurgyConfig.launchingDestroysBlocks = config.get(ROCKET, "launchBlockDestruction", false, "Allow launches to damage nearby blocks, plants, glass, soil, turn rock into lava, and more").getBoolean();
        blackListRocketBlocksStr = config.getStringList("rocketBlockBlackList", ROCKET, new String[]{"minecraft:portal", "minecraft:bedrock", "minecraft:snow_layer", "minecraft:water", "minecraft:flowing_water", "minecraft:lava", "minecraft:flowing_lava", "minecraft:fire", "stellurgy:rocketfire"}, "Blocks that cannot be part of rocket. Format: modid:block e.g \"minecraft:chest\"");
        stellurgyConfig.advancedWeightSystem = config.get(ROCKET, "advancedWeightSystem", true, "Tier-1 rockets weigh their blocks in kilograms (weights.json) and launch only above the minimum thrust-to-weight. Off: every block counts as one unit of mass and the launch check is off. What machines hold is scaled by contentMassScale. Tier-2 ships are always weighed").getBoolean();
        double contentMassScale = config.get(ROCKET, "contentMassScale", 1.0, "Multiplier on the mass of what a craft's machines hold (items in inventories, fluids in tanks); block mass and the people aboard are not scaled. 0 makes cargo weightless. Must not be negative. Note: may not see modded inventories that expose no item or fluid capability").getDouble();
        if (!(contentMassScale >= 0.0)) {
            // Refused, loudly: a negative or NaN weight stops a craft's centre of mass being a
            // weighted mean, and the solver integrates whatever that produces.
            logger.error("contentMassScale = " + contentMassScale + " is refused (it must be 0 or more); "
                    + "using 1.0, so cargo weighs what it is");
            contentMassScale = 1.0;
        }
        stellurgyConfig.contentMassScale = contentMassScale;
        stellurgyConfig.fuelMassScale = config.get(ROCKET, "fuelMassScale", 1.0, "Global multiplier applied to the mass of fuel/oxidizer carried by a rocket. Raise to make full tanks weigh more relative to thrust").getDouble();
        stellurgyConfig.minLaunchTWR = config.get(ROCKET, "minLaunchTWR", 1.05, "Minimum thrust-to-weight ratio (thrust / wet weight) a rocket needs before it is allowed to launch. 1.0 means it can barely lift itself; values above 1.0 add a safety margin").getDouble();
        stellurgyConfig.wearThrustPenaltyMax = config.get(ROCKET, "wearThrustPenaltyMax", 0.5, "Fraction of thrust a fully-worn rocket motor loses. 0.5 means a motor at max wear produces half thrust; 0 disables the thrust penalty entirely (condition then only affects the failure roll). Independent of partsWearSystem, which gates only whether wear ACCRUES").getDouble();
        stellurgyConfig.wearWarnProbability = config.get(ROCKET, "wearWarnProbability", 0.05, "Failure probability (0..1) at or above which the pilot is warned before launch that the rocket is worn. Also the threshold that blocks launch when wearCriticalBlocksLaunch is true").getDouble();
        stellurgyConfig.wearCriticalBlocksLaunch = config.get(ROCKET, "wearCriticalBlocksLaunch", false, "If true, a rocket whose failure probability is at/above wearWarnProbability is refused launch (no explosion). If false, the pilot is warned but may still launch and risk the stochastic explosion").getBoolean();
        stellurgyConfig.shipsCollide = config.get(ROCKET, "shipsCollide", true, "If true, two ships whose bounding boxes overlap are both held at rest while they overlap, and a collision event naming both is posted. Crude by design: nothing is conserved, no momentum is transferred and the hulls are compared as boxes rather than blocks. Set false to restore the old behaviour, in which two ships pass through each other").getBoolean();
        stellurgyConfig.serviceStationStandaloneRepairMultiplier = config.get(ROCKET, "serviceStationStandaloneRepairMultiplier", 3.0, "Resource cost multiplier when the service station repairs a worn part WITHOUT a linked PrecisionAssembler (consumes the repair recipe's non-part ingredients times this factor). The assembler-backed path stays at 1x").getDouble();
        stellurgyConfig.repairCostPerStageFraction = config.get(ROCKET, "repairCostPerStageFraction", 1.0, "Share of a block's own crafting recipe charged for a FULL hand repair with the welder, spread evenly over its damage stages (1.0 = repairing a block from its worst stage costs about what crafting it costs). Ingredient counts round up, so no stage is ever free").getDouble();
        stellurgyConfig.repairWelderEnergyPerStage = config.get(ROCKET, "repairWelderEnergyPerStage", 2000, "Forge Energy the repair welder spends per stage of damage removed").getInt();
        stellurgyConfig.repairWelderCapacity = config.get(ROCKET, "repairWelderCapacity", 100000, "Forge Energy the repair welder holds when fully charged").getInt();
        stellurgyConfig.wearTankLeakChanceMax = config.get(ROCKET, "wearTankLeakChanceMax", 0.5, "Chance (0..1) that a fully-worn fuel tank carrying fuel/oxidizer leaks at launch. Scaled by the tank's wear stage. A leak both bleeds fuel and adds to the launch failure (explosion) probability").getDouble();
        stellurgyConfig.wearTankLeakFuelLoss = config.get(ROCKET, "wearTankLeakFuelLoss", 0.25, "Fraction of a fuel type's loaded fuel lost when a worn tank of that type leaks at launch").getDouble();
        stellurgyConfig.wearSeatBlockStageFraction = config.get(ROCKET, "wearSeatBlockStageFraction", 0.7, "Wear fraction (0..1 of max stage) at or above which a worn seat blocks a CREWED launch. Uncrewed/automated rockets ignore seat wear").getDouble();
        stellurgyConfig.shotReflectionSpeedFloor = config.get(WEAPONS, "shotReflectionSpeedFloor", 0.05, "Speed in blocks per tick below which a shot deflected by a shield is ended at the shell instead of continuing. Prevents near-motionless rounds loitering against a shield", 0.0, Double.MAX_VALUE).getDouble();
        stellurgyConfig.shotPenetrationSpeedFloor = config.get(WEAPONS, "shotPenetrationSpeedFloor", 0.05, "Speed in blocks per tick below which a round boring through a hull is treated as having come to rest inside it. Penetration costs a round its speed, and without a floor a spent one creeps forward forever", 0.0, Double.MAX_VALUE).getDouble();
        stellurgyConfig.shotBodyRadiusCap = config.get(WEAPONS, "shotBodyRadiusCap", 2.0, "The widest a shot's body is treated as when it sweeps its way through blocks, in blocks. A body sweeps a cylinder rather than a line and the work one step does grows with the square of its width, so this bounds what an absurd calibre can cost the server. The declared cross-section still prices the shot; only the geometry is capped", 0.0, 8.0).getDouble();
        stellurgyConfig.ricochetIncidenceDegrees = config.get(WEAPONS, "ricochetIncidenceDegrees", 65.0, "How glancing a hit must be before a solid round skips off METAL rather than digging in, in degrees from the surface normal: 0 is square-on, 90 a pure graze. Only metal deflects, so a round never skips off a plank wall. 90 disables ricochet", 0.0, 90.0).getDouble();
        stellurgyConfig.ricochetRestitution = config.get(WEAPONS, "ricochetRestitution", 0.75, "How much of its speed a ricocheting round keeps. Below 1 a bounce costs something, which is what stops a round skipping between two plates forever", 0.0, 1.0).getDouble();
        stellurgyConfig.ablationResistanceFactor = config.get(WEAPONS, "ablationResistanceFactor", 20.0, "How much dearer a block is to boil away than to push through, when nothing has written it its own ablation row. Both are energy per unit volume removed; they are nowhere near the same magnitude, which is why a laser buys precision rather than digging power. 1.0 makes a beam dig exactly like a slug", 0.01, 1000.0).getDouble();
        stellurgyConfig.beamAblationIntensityThreshold = config.get(WEAPONS, "beamAblationIntensityThreshold", 50000.0, "Energy per unit of a beam's cross-section below which it removes nothing and its energy is absorbed as heat instead of being carried onward. The default sits above the affordability line of metal (order 38500 for an iron block), so a small emitter does nothing to a metal hull however long it is held. 0 disables it, and a sub-threshold beam then passes clean through the plate with everything it arrived with", 0.0, Double.MAX_VALUE).getDouble();
        stellurgyConfig.maxShotsPerWorld = config.get(WEAPONS, "maxShotsPerWorld", 256, "How many shots one world may have in flight at once. Further fire is refused until some land; nothing already in flight is ever dropped to make room", 1, Integer.MAX_VALUE).getInt();
        stellurgyConfig.shotVisibilityRadius = config.get(WEAPONS, "shotVisibilityRadius", 256, "How near a player the path of a fired round, or the lit length of a held beam, must pass before that player is told about it and can see it drawn, in blocks. 0 disables weapon-fire replication entirely — the mechanics still work, nothing is drawn", 0, Integer.MAX_VALUE).getInt();
        stellurgyConfig.enableFireControlSensor = config.get(WEAPONS, "enableFireControlSensor", true, "Whether fire-control sensors search for targets. Off, a sensor acquires nothing, publishes nothing and draws no power: batteries are pointed by hand, as they were before sensors existed").getBoolean();
        stellurgyConfig.fireControlSensorRadius = config.get(WEAPONS, "fireControlSensorRadius", 96.0, "How far a fire-control sensor can look, in blocks. Its envelope — a target inside it may still be too poorly resolved to shoot at", 1.0, 1024.0).getDouble();
        stellurgyConfig.fireControlSensorScanIntervalTicks = config.get(WEAPONS, "fireControlSensorScanIntervalTicks", 10, "Ticks between sweeps. The cadence at which a sensor reconsiders which contact to hand its battery, not the rate at which the guns follow it", 1, 200).getInt();
        stellurgyConfig.fireControlSensorMaxTracks = config.get(WEAPONS, "fireControlSensorMaxTracks", 8, "How many contacts one sensor holds at once", 1, 64).getInt();
        stellurgyConfig.fireControlSensorActiveEnergyPerTick = config.get(WEAPONS, "fireControlSensorActiveEnergyPerTick", 40, "FE per tick an actively illuminating sensor draws. Passive listening is free; a sensor that cannot pay falls back to listening", 0, Integer.MAX_VALUE).getInt();
        stellurgyConfig.fireControlSensorActiveLockQuality = config.get(WEAPONS, "fireControlSensorActiveLockQuality", 0.95, "Lock quality an active sensor holds a contact at inside its envelope, 0..1 — what illuminating buys over listening", 0.0, 1.0).getDouble();
        stellurgyConfig.fireControlSensorLockQualityToFire = config.get(WEAPONS, "fireControlSensorLockQualityToFire", 0.25, "How well a contact must be resolved, 0..1, before a gun fires at it. Below it the battery tracks without shooting", 0.0, 1.0).getDouble();
        stellurgyConfig.fireControlSensorAcquireHostilesOnly = config.get(WEAPONS, "fireControlSensorAcquireHostilesOnly", true, "Whether acquisition is limited to hostile mobs and players. Off, a battery engages whatever wanders into range").getBoolean();
        stellurgyConfig.turretDerateDamageFraction = config.get(WEAPONS, "turretDerateDamageFraction", 0.25, "How far gone a turret's own block must be, 0..1, before its traverse slows down. The order of the rungs is the mechanic; where they sit is balance", 0.0, 1.0).getDouble();
        stellurgyConfig.turretJamDamageFraction = config.get(WEAPONS, "turretJamDamageFraction", 0.75, "How far gone a turret's own block must be, 0..1, before its traverse seizes entirely. A seized mount still fires down the bearing it stopped at", 0.0, 1.0).getDouble();
        stellurgyConfig.partsWearSystem = config.get(ROCKET, "partsWearSystem", true, "Whether rocket parts ACCRUE wear: whether a launch advances a seat, tank or motor towards its next stage. It does not gate what a worn part then DOES - a rocket shot up on the pad has to fly like a rocket shot up on the pad whatever this says, because battle damage and a long career put stages on the same axis. Off means a save stops getting worse, not that the damage already on it stops mattering: the launch warning, the critical-wear refusal, the failure roll and the tank leaks all still apply").getBoolean();
        stellurgyConfig.increaseWearIntensityProb = config.get(ROCKET, "increaseWearIntensityProb", 0.025, "Chance for each part to gain wear on launch.").getDouble();

        //Ore configuration
        final boolean masterToggle = stellurgyConfig.generateCopper = config.get(WORLDGEN, "EnableOreGen", true).getBoolean();
        stellurgyConfig.generateCopper = config.get(WORLDGEN, "GenerateCopper", true).getBoolean() && masterToggle;
        stellurgyConfig.copperClumpSize = config.get(WORLDGEN, "CopperPerClump", 6).getInt();
        stellurgyConfig.copperPerChunk = config.get(WORLDGEN, "CopperPerChunk", 10).getInt();
        stellurgyConfig.generateTin = config.get(WORLDGEN, "GenerateTin", true).getBoolean() && masterToggle;
        stellurgyConfig.tinClumpSize = config.get(WORLDGEN, "TinPerClump", 6).getInt();
        stellurgyConfig.tinPerChunk = config.get(WORLDGEN, "TinPerChunk", 10).getInt();
        stellurgyConfig.generateDilithium = config.get(WORLDGEN, "generateDilithium", true).getBoolean() && masterToggle;
        stellurgyConfig.dilithiumClumpSize = config.get(WORLDGEN, "DilithiumPerClump", 16).getInt();
        stellurgyConfig.dilithiumPerChunk = config.get(WORLDGEN, "DilithiumPerChunk", 1).getInt();
        stellurgyConfig.dilithiumPerChunkMoon = config.get(WORLDGEN, "DilithiumPerChunkLuna", 10).getInt();
        stellurgyConfig.generateAluminum = config.get(WORLDGEN, "generateAluminum", true).getBoolean() && masterToggle;
        stellurgyConfig.aluminumClumpSize = config.get(WORLDGEN, "AluminumPerClump", 16).getInt();
        stellurgyConfig.aluminumPerChunk = config.get(WORLDGEN, "AluminumPerChunk", 1).getInt();
        stellurgyConfig.generateRutile = config.get(WORLDGEN, "GenerateRutile", true).getBoolean() && masterToggle;
        stellurgyConfig.rutileClumpSize = config.get(WORLDGEN, "RutilePerClump", 6).getInt();
        stellurgyConfig.rutilePerChunk = config.get(WORLDGEN, "RutilePerChunk", 6).getInt();
        stellurgyConfig.generateIridium = config.get(WORLDGEN, "generateIridium", false).getBoolean() && masterToggle;
        stellurgyConfig.IridiumClumpSize = config.get(WORLDGEN, "IridiumPerClump", 16).getInt();
        stellurgyConfig.IridiumPerChunk = config.get(WORLDGEN, "IridiumPerChunk", 1).getInt();
        //Orbital laser
        stellurgyConfig.laserDrillOresBlackList = config.get(WORLDGEN, "laserDrillOres_blacklist", true, "Treat laserDrillOres as a blacklist. Note: false + empty ore list will crash the game").getBoolean();
        orbitalLaserOres = config.get(WORLDGEN, "laserDrillOres", new String[]{}, "List of ores allowed to be mined by the laser drill if surface drilling is disabled.  Ores can be specified by just the oreName:<size> (oredict) or by modid:block:meta:<size> where size is stacksize and optional").getStringList();
        //Geode
        stellurgyConfig.geodeOresBlackList = config.get(WORLDGEN, "geodeOres_blacklist", false, "Treat geodeOres as a blacklist.").getBoolean();
        stellurgyConfig.generateGeodes = config.get(WORLDGEN, "generateGeodes", true, "Globally enable geode generation. Note: setting this option to false overrides 'generateGeodes' in the planetDefs.xml").getBoolean();
        stellurgyConfig.geodeBaseSize = config.get(WORLDGEN, "geodeBaseSize", 36, "Average geode size.").getInt();
        stellurgyConfig.geodeVariation = config.get(WORLDGEN, "geodeVariation", 24, "Geode size variation.").getInt();
        geodeOres = config.get(WORLDGEN, "geodeOres", new String[]{"oreIron", "oreGold", "oreCopper", "oreTin", "oreRedstone"}, "List of ores allowed in geodes. (oredict names)").getStringList();
        //Other structures
        stellurgyConfig.generateCraters = config.get(WORLDGEN, "generateCraters", true, "Globally enable meteor craters on low-pressure planets.  Note: setting this option to false overrides 'generateCraters' in the planetDefs.xml").getBoolean();
        stellurgyConfig.generateVolcanos = config.get(WORLDGEN, "generateVolcanos", true, "Globally enable volcanoes on very hot planets.  Note: setting this option to false overrides 'generateVolcanos' in the planetDefs.xml").getBoolean();
        stellurgyConfig.generateVanillaStructures = config.getBoolean("generateVanillaStructures", WORLDGEN, false, "Globally enable vanilla structures on planets with breathable air.  Note: setting this to false will override 'generateStructures' in the planetDefs.xml");

        //Load laser dimid blacklists
        for (String s : str) {

            try {
                stellurgyConfig.laserBlackListDims.add(Integer.parseInt(s));
            } catch (NumberFormatException e) {
                logger.warn("Invalid number \"" + s + "\" for laser dimid blacklist");
            }
        }
    }

    public static void loadPostInit() {
        StellurgyConfiguration stellurgyConfig = ownConfig;

        //Register fuels
        logger.info("Start registering liquid rocket fuels");
        for (String str : liquidMonopropellant) {
            String[] splitStr = str.split(";");
            Fluid fluid = FluidRegistry.getFluid(splitStr[0]);
            float multiplier = 1.0f;
            if (splitStr.length > 1) {
                multiplier = Float.parseFloat(splitStr[1]);
            }

            if (fluid != null) {
                logger.info("Registering fluid " + str + " as rocket monopropellant");
                FuelRegistry.instance.registerFuel(FuelType.LIQUID_MONOPROPELLANT, fluid, multiplier);
            } else
                logger.warn("Fluid name" + str + " is not a registered fluid!");
        }
        liquidMonopropellant = null; //clean up
        for (String str : liquidBipropellantFuel) {
            String[] splitStr = str.split(";");
            Fluid fluid = FluidRegistry.getFluid(splitStr[0]);
            float multiplier = 1.0f;
            if (splitStr.length > 1) {
                multiplier = Float.parseFloat(splitStr[1]);
            }

            if (fluid != null) {
                logger.info("Registering fluid " + str + " as rocket bipropellant");
                FuelRegistry.instance.registerFuel(FuelType.LIQUID_BIPROPELLANT, fluid, multiplier);
            } else
                logger.warn("Fluid name" + str + " is not a registered fluid!");
        }
        liquidBipropellantFuel = null; //clean up
        for (String str : liquidBipropellantOxidizer) {
            String[] splitStr = str.split(";");
            Fluid fluid = FluidRegistry.getFluid(splitStr[0]);
            float multiplier = 1.0f;
            if (splitStr.length > 1) {
                multiplier = Float.parseFloat(splitStr[1]);
            }

            if (fluid != null) {
                logger.info("Registering fluid " + str + " as rocket oxidizer");
                FuelRegistry.instance.registerFuel(FuelType.LIQUID_OXIDIZER, fluid, multiplier);
            } else
                logger.warn("Fluid name" + str + " is not a registered fluid!");
        }
        liquidBipropellantOxidizer = null; //clean up
        for (String str : liquidNuclearWorkingFluid) {
            String[] splitStr = str.split(";");
            Fluid fluid = FluidRegistry.getFluid(splitStr[0]);
            float multiplier = 1.0f;
            if (splitStr.length > 1) {
                multiplier = Float.parseFloat(splitStr[1]);
            }

            if (fluid != null) {
                logger.info("Registering fluid " + str + " as rocket nuclear working fluid");
                FuelRegistry.instance.registerFuel(FuelType.NUCLEAR_WORKING_FLUID, fluid, multiplier);
            } else
                logger.warn("Fluid name" + str + " is not a registered fluid!");
        }
        liquidNuclearWorkingFluid = null; //clean up
        logger.info("Finished registering liquid rocket fuels");

        //Register Whitelisted Sealable Blocks

        logger.info("Start registering sealable blocks (sealableBlockWhiteList)");
        for (String str : sealableBlockWhiteList) {
            Block block = Block.getBlockFromName(str);
            if (block == null)
                logger.warn("'" + str + "' is not a valid Block");
            else
                SealableBlockHandler.INSTANCE.addSealableBlock(block);
        }
        logger.info("End registering sealable blocks");
        sealableBlockWhiteList = null;

        logger.info("Start registering unsealable blocks (sealableBlockBlackList)");
        for (String str : sealableBlockBlackList) {
            Block block = Block.getBlockFromName(str);
            if (block == null)
                logger.warn("'" + str + "' is not a valid Block");
            else
                SealableBlockHandler.INSTANCE.addUnsealableBlock(block);
        }
        logger.info("End registering unsealable blocks");
        sealableBlockBlackList = null;

        logger.info("Start registering torch blocks");
        for (String str : breakableTorches) {
            Block block = Block.getBlockFromName(str);
            if (block == null)
                logger.warn("'" + str + "' is not a valid Block");
            else
                stellurgyConfig.torchBlocks.add(block);
        }
        logger.info("End registering torch blocks");
        breakableTorches = null;

        logger.info("Start registering blackhole generator blocks");
        for (String str : blackHoleGeneratorTiming) {
            String[] splitStr = str.split(";");

            String[] blockString = splitStr[0].split(":");

            Item block = Item.REGISTRY.getObject(new ResourceLocation(blockString[0], blockString[1]));
            int metaValue = 0;

            if (blockString.length > 2) {
                try {
                    metaValue = Integer.parseInt(blockString[2]);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid meta value location for black hole generator: " + splitStr[0] + " using " + blockString[2]);
                }
            }

            int time = 0;

            try {
                time = Integer.parseInt(splitStr[1]);
            } catch (NumberFormatException e) {
                logger.warn("Invalid time value for black hole generator: " + str);
            }

            if (block == null)
                logger.warn("'" + splitStr[0] + "' is not a valid Block");
            else
                stellurgyConfig.blackHoleGeneratorBlocks.put(new ItemStack(block, 1, metaValue), time);
        }
        logger.info("End registering blackhole generator blocks");
        breakableTorches = null;


        logger.info("Start registering rocket blacklist blocks");
        for (String str : blackListRocketBlocksStr) {
            Block block = Block.getBlockFromName(str);
            if (block == null)
                logger.warn("'" + str + "' is not a valid Block");
            else
                stellurgyConfig.blackListRocketBlocks.add(block);
        }
        logger.info("End registering rocket blacklist blocks");
        blackListRocketBlocksStr = null;

        logger.info("Start registering entity atmosphere bypass");

        //Add armor stand by default
        stellurgyConfig.bypassEntity.add(EntityArmorStand.class);
        if (Loader.isModLoaded("matteroverdrive")) {
            MatterOvedriveIntegration.addAndroidsToBypassList(stellurgyConfig);
        }


        for (String str : entityList) {
            Class clazz = EntityList.getClass(new ResourceLocation(str));

            //If not using string name maybe it's a class name?
            if (clazz == null) {
                try {
                    clazz = Class.forName(str);
                    if (!Entity.class.isAssignableFrom(clazz))
                        clazz = null;

                } catch (Exception e) {
                    //Fail silently
                }
            }

            if (clazz != null) {
                logger.info("Registering " + clazz.getName() + " for atmosphere bypass");
                stellurgyConfig.bypassEntity.add(clazz);
            } else
                logger.warn("Cannot find " + str + " while registering entity for atmosphere bypass");
        }

        //Free memory
        entityList = null;
        logger.info("End registering entity atmosphere bypass");

        //Register geodeOres
        if (!stellurgyConfig.geodeOresBlackList) {
            stellurgyConfig.standardGeodeOres.addAll(Arrays.asList(geodeOres));
        }

        //Register laserDrill ores
        if (!stellurgyConfig.laserDrillOresBlackList) {
            stellurgyConfig.standardLaserDrillOres.addAll(Arrays.asList(orbitalLaserOres));
        }


        //Do blacklist stuff for ore registration
        for (String oreName : OreDictionary.getOreNames()) {

            if (stellurgyConfig.geodeOresBlackList && oreName.startsWith("ore")) {
                boolean found = false;
                for (String str : geodeOres) {
                    if (oreName.equals(str)) {
                        found = true;
                        break;
                    }
                }
                if (!found)
                    stellurgyConfig.standardGeodeOres.add(oreName);
            }

            if (stellurgyConfig.laserDrillOresBlackList && oreName.startsWith("ore")) {
                boolean found = false;
                for (String str : orbitalLaserOres) {
                    if (oreName.equals(str)) {
                        found = true;
                        break;
                    }
                }
                if (!found)
                    stellurgyConfig.standardLaserDrillOres.add(oreName);
            }
        }
    }

    public void writeConfigToNetwork(PacketBuffer out) {
        Field[] fields = StellurgyConfiguration.class.getDeclaredFields();
        List<Field> fieldList = new ArrayList<>(fields.length);


        // getDeclaredFields returns an unordered list, so we need to sort them
        for (Field field : fields) {
            if (field.isAnnotationPresent(ConfigProperty.class) && field.getAnnotation(ConfigProperty.class).needsSync())
                fieldList.add(field);
        }

        fieldList.sort(Comparator.comparing(Field::getName));

        try {
            for (Field field : fieldList) {
                ConfigProperty props = field.getAnnotation(ConfigProperty.class);
                int hash = field.getName().hashCode();
                out.writeInt(hash);
                try {
                    writeDatum(out, field.getType(), field.get(this), props);
                } catch (IllegalArgumentException | IllegalAccessException | InvalidClassException e) {
                    e.printStackTrace();
                }
            }
        } finally {
            out.writeByte(MAGIC_CODE);
            out.writeLong(MAGIC_CODE_PT2);
        }

    }

    private void writeDatum(PacketBuffer out, Class type, Object value, ConfigProperty property) throws InvalidClassException {

        if (Integer.class.isAssignableFrom(type) || type == int.class)
            out.writeInt((Integer) value);
        else if (Long.class.isAssignableFrom(type) || type == long.class)
            out.writeLong((Long) value);
        else if (Float.class.isAssignableFrom(type) || type == float.class)
            out.writeFloat((Float) value);
        else if (Double.class.isAssignableFrom(type) || type == double.class)
            out.writeDouble((Double) value);
        else if (Boolean.class.isAssignableFrom(type) || type == boolean.class)
            out.writeBoolean((Boolean) value);
        else if (Asteroid.class.isAssignableFrom(type)) {
            Asteroid asteroid = (Asteroid) value;
            out.writeString(asteroid.ID);
            out.writeInt(asteroid.distance);
            out.writeInt(asteroid.mass);
            out.writeInt(asteroid.minLevel);
            out.writeFloat(asteroid.massVariability);
            out.writeFloat(asteroid.richness);                    //factor of the ratio of ore to stone
            out.writeFloat(asteroid.richnessVariability);        //variability of richness
            out.writeFloat(asteroid.probability);                //probability of the asteroid spawning
            out.writeFloat(asteroid.timeMultiplier);
            out.writeItemStack(asteroid.baseStack);

            out.writeInt(asteroid.stackProbabilities.size());
            for (int i = 0; i < asteroid.stackProbabilities.size(); i++) {
                out.writeItemStack(asteroid.itemStacks.get(i));
                out.writeFloat(asteroid.stackProbabilities.get(i));
            }
        } else if (String.class.isAssignableFrom(type)) {
            out.writeString((String) value);
        } else if (List.class.isAssignableFrom(type)) {
            List list = (List) value;
            out.writeShort(list.size());
            for (Object o : list) {
                writeDatum(out, property.internalType(), o, property);
            }
        } else if (Set.class.isAssignableFrom(type)) {
            Set list = (Set) value;
            out.writeShort(list.size());
            for (Object o : list) {
                writeDatum(out, property.internalType(), o, property);
            }
        }
        //TODO: maps and lists with arbitrary types
        else if (Map.class.isAssignableFrom(type)) {
            Map map = (Map) value;

            out.writeInt(map.size());
            for (Object key : map.keySet()) {
                Object mapValue = map.get(key);
                writeDatum(out, property.keyType(), key, property);
                writeDatum(out, property.valueType(), mapValue, property);
            }
        } else {
            throw new InvalidClassException("Cannot transmit class type " + type.getName());
        }

    }

    private Object readDatum(PacketBuffer in, Class type, ConfigProperty property) throws InvalidClassException, InstantiationException, IllegalAccessException {

        if (Integer.class.isAssignableFrom(type) || type == int.class)
            return in.readInt();
        else if (Long.class.isAssignableFrom(type) || type == long.class)
            return in.readLong();
        else if (Float.class.isAssignableFrom(type) || type == float.class)
            return in.readFloat();
        else if (Double.class.isAssignableFrom(type) || type == double.class)
            return in.readDouble();
        else if (boolean.class.isAssignableFrom(type) || type == boolean.class)
            return in.readBoolean();
        else if (String.class.isAssignableFrom(type)) {
            return in.readString(256);
        } else if (Asteroid.class.isAssignableFrom(type)) {
            Asteroid asteroid = new Asteroid();

            asteroid.ID = in.readString(128);
            asteroid.distance = in.readInt();
            asteroid.mass = in.readInt();
            asteroid.minLevel = in.readInt();
            asteroid.massVariability = in.readFloat();
            asteroid.richness = in.readFloat();                    //factor of the ratio of ore to stone
            asteroid.richnessVariability = in.readFloat();        //variability of richness
            asteroid.probability = in.readFloat();                //probability of the asteroid spawning
            asteroid.timeMultiplier = in.readFloat();
            try {
                asteroid.baseStack = in.readItemStack();
            } catch (IOException e) {
                e.printStackTrace();
            }


            int size = in.readInt();
            for (int i = 0; i < size; i++) {
                try {
                    asteroid.itemStacks.add(in.readItemStack());
                    asteroid.stackProbabilities.add(in.readFloat());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return asteroid;
        } else if (List.class.isAssignableFrom(type)) {
            List list = (List) type.newInstance();

            short listsize = in.readShort();
            for (int i = 0; i < listsize; i++) {
                list.add(readDatum(in, property.internalType(), property));
            }

            return list;
        } else if (Set.class.isAssignableFrom(type)) {
            Set set = (Set) type.newInstance();

            short listsize = in.readShort();
            for (int i = 0; i < listsize; i++) {
                set.add(readDatum(in, property.internalType(), property));
            }

            return set;
        }
        //TODO: maps and lists with arbitrary types
        else if (Map.class.isAssignableFrom(type)) {
            Map map = (Map) type.newInstance();
            int mapCount = in.readInt();

            for (int i = 0; i < mapCount; i++) {
                Object key = readDatum(in, property.keyType(), property);
                Object value = readDatum(in, property.valueType(), property);
                map.put(key, value);
            }
            return map;
        } else {
            throw new InvalidClassException("Cannot transmit class type " + type.getName());
        }
    }

    public StellurgyConfiguration readConfigFromNetwork(PacketBuffer in) {
        Field[] fields = StellurgyConfiguration.class.getDeclaredFields();
        List<Field> fieldList = new ArrayList<>(fields.length);


        // getDeclaredFields returns an unordered list, so we need to sort them
        for (Field field : fields) {
            if (field.isAnnotationPresent(ConfigProperty.class) && field.getAnnotation(ConfigProperty.class).needsSync())
                fieldList.add(field);
        }

        fieldList.sort(Comparator.comparing(Field::getName));

        for (Field field : fieldList) {
            ConfigProperty props = field.getAnnotation(ConfigProperty.class);
            int hash = field.getName().hashCode();
            if (hash != in.readInt())
                return this; //Bail

            try {
                Object data = readDatum(in, field.getType(), props);
                field.set(this, data);
            } catch (IllegalArgumentException | IllegalAccessException | InvalidClassException |
                     InstantiationException e) {
                e.printStackTrace();
            }
        }

        while (in.readByte() != MAGIC_CODE && in.readLong() == MAGIC_CODE_PT2) ;

        return this;
    }

    /** Writes this process's own configuration back to its file; a server's copy has no file here. */
    public void save() {
        if (this == ownConfig)
            config.save();
    }

    public void addTorchblock(Block newblock) {
        torchBlocks.add(newblock);
        String[] blocks = new String[torchBlocks.size()];
        int index = 0;
        for (Block block : torchBlocks) {
            blocks[index++] = block.getRegistryName().toString();
        }
        config.get(OXYGEN, "torchBlocks", "").set(blocks);
        save();
    }

    public void addSealedBlock(Block newblock) {
        SealableBlockHandler.INSTANCE.addSealableBlock(newblock);
        List<Block> blockList = SealableBlockHandler.INSTANCE.getOverriddenSealableBlocks();
        String[] blocks = new String[blockList.size()];
        int index = 0;
        for (Block block : blockList) {
            blocks[index++] = block.getRegistryName().toString();
        }
        config.get(OXYGEN, "sealableBlockWhiteList", "").set(blocks);
        save();
    }


    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface ConfigProperty {
        boolean needsSync() default false;

        Class internalType() default Object.class;

        Class keyType() default Object.class;

        Class valueType() default Object.class;
    }
}
