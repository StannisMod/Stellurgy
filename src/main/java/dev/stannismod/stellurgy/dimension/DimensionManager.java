package dev.stannismod.stellurgy.dimension;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Loader;
import org.apache.commons.io.FileUtils;
import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.dimension.IDimensionProperties;
import dev.stannismod.stellurgy.api.dimension.solar.IGalaxy;
import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.api.stations.ISpaceObject;
import dev.stannismod.stellurgy.dimension.DimensionProperties.AtmosphereTypes;
import dev.stannismod.stellurgy.network.PacketDimInfo;
import dev.stannismod.stellurgy.network.PacketSatellitesUpdate;
import dev.stannismod.stellurgy.stations.SpaceObjectManager;
import dev.stannismod.stellurgy.util.Asteroid;
import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;
import dev.stannismod.stellurgy.util.PlanetaryTravelHelper;
import dev.stannismod.stellurgy.util.XMLPlanetLoader;
import dev.stannismod.stellurgy.util.XMLPlanetLoader.DimensionPropertyCoupling;
import dev.stannismod.stellurgy.world.provider.WorldProviderAsteroid;
import dev.stannismod.stellurgy.world.provider.WorldProviderPlanet;
import dev.stannismod.stellurgy.world.provider.WorldProviderSpace;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.util.*;
import java.util.Map.Entry;
import java.util.zip.GZIPOutputStream;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static dev.stannismod.stellurgy.Stellurgy.logger;
import dev.stannismod.stellurgy.api.*;


/**
 * Every static field of this type is effectively final, process lifetime: built once at class initialisation.
 */
public class DimensionManager implements IGalaxy {

    public static final String workingPath = "advRocketry";
    public static final String tempFile = "/temp.dat";
    public static final String worldXML = "/planetDefs.xml";
    public static final DimensionType PlanetDimensionType = DimensionType.register("planet", "planet", 2, WorldProviderPlanet.class, false);
    public static final DimensionType spaceDimensionType = DimensionType.register("space", "space", 3, WorldProviderSpace.class, false);
    public static final DimensionType AsteroidDimensionType = DimensionType.register("asteroid", "asteroid", 4, WorldProviderAsteroid.class, false);
    public static final int GASGIANT_DIMID_OFFSET = 0x100; //Offset by 256
    /**
     * Lowest dimension id a planet may take, and the cursor the planet loader advances past the
     * dimensions it has just claimed.
     *
     * <p>Seeded from the configured {@code minDimension} at construction and moved during a load —
     * which is why it is state and not a constant.</p>
     */
    private int dimOffset;
    /** Progression the SAVE records: whether this world's players have reached the moon, and warp. */
    private boolean hasReachedMoon;
    private boolean hasReachedWarp;
    /** The properties answered for dim 0 and for any id this manager does not know. */
    private final DimensionProperties overworldProperties;
    /** The properties of open space: answered for the station dimension outside any station. */
    private final DimensionProperties defaultSpaceDimensionProperties;
    private long nextSatelliteId;
    public Set<Integer> knownPlanets;
    /**
     * Planets every player of this save knows from the start: the overworld, plus each body the
     * planet file marks {@code <isKnown>}. Filled by the planet load; never forgotten by a beacon.
     */
    private final Set<Integer> initiallyKnownPlanets = new HashSet<>();
    /** Asteroid kinds an observatory can find and a mining mission can work, by id. */
    private final Map<String, Asteroid> asteroidTypes = new HashMap<>();
    /**
     * The dimension Stellurgy made the Moon in, or {@link Constants#INVALID_PLANET} before the planet
     * load has chosen one.
     */
    private int moonId = Constants.INVALID_PLANET;
    /** The planet types this save's worlds are typed from: its planet file's, or the code-shipped set. */
    private dev.stannismod.stellurgy.universe.PlanetTypes planetTypes = dev.stannismod.stellurgy.universe.PlanetTypes.stock();
    /**
     * The planet file's authored galactic anchors, read while dimensions load — before the universe
     * registry is reachable, since worlds are not loaded yet — and drained into it once by
     * {@code UniverseRegistry.populate}. {@link #stagedAnchorsReset}: the file was re-read on request.
     */
    private Map<Integer, dev.stannismod.stellurgy.universe.GalacticAnchor> stagedAnchors = new HashMap<>();
    private boolean stagedAnchorsReset;
    /**
     * The pack's {@code <galaxyGen>} configuration for this server — the shipped one when it states
     * none. Kept for the whole session, not drained: the upgrade command stamps with it, and the
     * planet file is written back with it.
     */
    private dev.stannismod.stellurgy.universe.GalaxyGenConfig packGalaxyConfig;
    /** The layout problems this galaxy's derivation has already reported. */
    private final dev.stannismod.stellurgy.universe.ReportOnce reports =
            new dev.stannismod.stellurgy.universe.ReportOnce();
    private Random random;
    private boolean hasBeenInitialized = false;
    private HashMap<Integer, DimensionProperties> dimensionList;
    private HashMap<Integer, StellarBody> starList;

    /**
     * One galaxy as one side knows it: the running server's, built when that server starts and dropped
     * when it stops, or a client connection's, built when it connects and dropped with it. Nothing
     * here is cleared for reuse — a new server or a new connection gets a new manager.
     *
     * @param dimOffset the lowest dimension id a planet may take
     */
    public DimensionManager(int dimOffset) {
        this.dimOffset = dimOffset;
        dimensionList = new HashMap<>();
        starList = new HashMap<>();

        overworldProperties = new DimensionProperties(0);
        seedEarthDefaults(overworldProperties);

        defaultSpaceDimensionProperties = newOpenSpaceProperties();

        initiallyKnownPlanets.add(0);
        random = new Random(System.currentTimeMillis());
        knownPlanets = new HashSet<>();
    }

    /**
     * A new, unshared set of the properties of open space — what a station starts from. New each
     * call: a station used to start from a {@code clone()} of the manager's default, and that clone is
     * shallow, so every station shared its satellite maps with the default and with each other.
     */
    public static DimensionProperties newOpenSpaceProperties() {
        DimensionProperties space = new DimensionProperties(SpaceObjectManager.WARPDIMID, false);
        space.realizeAtmosphere(false, 0);
        space.setAverageTemp(0);
        space.gravitationalMultiplier = 0.1f;
        space.orbitalDist = AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU;
        space.skyColor = new float[]{0f, 0f, 0f};
        space.setName("Space");
        space.fogColor = new float[]{0f, 0f, 0f};
        return space;
    }

    /** The properties answered for dim 0 and for any id this manager does not know. */
    public DimensionProperties getOverworldProperties() {
        return overworldProperties;
    }

    /** The properties of open space, outside any station. */
    public DimensionProperties getDefaultSpaceProperties() {
        return defaultSpaceDimensionProperties;
    }

    /** Planets known from the start in this save; mutable by the planet load only. */
    public Set<Integer> getInitiallyKnownPlanets() {
        return initiallyKnownPlanets;
    }

    /** Asteroid kinds by id: the server's from its asteroid file, a client's as the server sent them. */
    public Map<String, Asteroid> getAsteroidTypes() {
        return asteroidTypes;
    }

    /** The planet types this save's worlds are typed from. */
    public dev.stannismod.stellurgy.universe.PlanetTypes getPlanetTypes() {
        return planetTypes;
    }

    /** The layout problems this galaxy's derivation has already reported; the deriving code is handed it. */
    public dev.stannismod.stellurgy.universe.ReportOnce reports() {
        return reports;
    }

    /** The pack's {@code <galaxyGen>} configuration for this server — the shipped one when it states none. */
    public dev.stannismod.stellurgy.universe.GalaxyGenConfig getPackGalaxyConfig() {
        return packGalaxyConfig;
    }

    /** The authored anchors the planet load staged, handed over once: the next call answers empty. */
    public Map<Integer, dev.stannismod.stellurgy.universe.GalacticAnchor> drainStagedAnchors() {
        Map<Integer, dev.stannismod.stellurgy.universe.GalacticAnchor> drained = stagedAnchors;
        stagedAnchors = new HashMap<>();
        return drained;
    }

    /** Whether the staged anchors come from a planet file re-read on request; cleared by reading it. */
    public boolean drainStagedAnchorsReset() {
        boolean reset = stagedAnchorsReset;
        stagedAnchorsReset = false;
        return reset;
    }

    /** The Moon's dimension, or {@link Constants#INVALID_PLANET} when none was made. */
    public int getMoonId() {
        return moonId;
    }

    /**
     * Give the loaded OVERWORLD the unit bulk when its saved state holds none, and say so.
     *
     * <p>A save written while {@link #overworldProperties} was blank (see {@link #seedEarthDefaults})
     * carries a dim-0 body with no mass and no radius, because the NBT writer emits bulk only for a
     * body that has it. The planet FILE no longer reaches here: a body there that states no bulk is
     * refused at load, the overworld included, so this answers only the saved state.</p>
     *
     * <p>It is a REPAIR of the one body whose bulk is a definition rather than a measurement — Earth
     * masses and Earth radii are the units the whole catalogue is stated in — and it is announced,
     * because a body silently gaining a radius is indistinguishable from one that always had it.</p>
     */
    private static void repairOverworldBulk(DimensionProperties properties) {
        if (properties == null || properties.getId() != 0 || properties.hasBulkProperties()) {
            return;
        }
        properties.setBulk(1d, 1d);
        logger.warn("The overworld's saved state holds no mass and no radius; applying the unit"
                + " bulk (1 Earth mass, 1 Earth radius) it is DEFINED as. A body with no radius draws"
                + " at the marker size at every range and carries the flat 512-block proximity shell"
                + " instead of an atmosphere. Written by a version that blanked the overworld's"
                + " defaults on world teardown.");
    }

    /**
     * Earth's catalogue entry, STATED onto {@code earth} — the home world's shipped properties.
     *
     * <p>The overworld's defaults are not the GENERIC defaults of a planet (gravity 1, 100 K, no mass,
     * no radius). When this object was a process-wide static reset at every world teardown, every
     * world opened after a return to the title screen got a nameless 100-kelvin body of no size — and
     * because the planet file writes bulk only when a body HAS it, that world's
     * {@code planetDefs.xml} then recorded an Earth with no radius permanently.</p>
     *
     * <p>What a missing radius costs, measured 2026-08-23 from a live flight: the sky renderer draws
     * the body at the marker size at every range (so Earth is invisible from orbit, behind the Moon)
     * and the descent shell falls back to the flat 512-block proximity sphere meant for belts —
     * 1/50 of this world.</p>
     */
    private static void seedEarthDefaults(DimensionProperties earth) {
        StellarBody sol = new StellarBody();
        sol.setTemperature(100);
        sol.setId(0);
        sol.setName("Sol");

        //Temperature in Kelvin, 286 is 13 Degrees C
        earth.setAverageTemp(286);
        earth.gravitationalMultiplier = 1f;
        // Earth's bulk, and it is a DEFINITION rather than a choice: the Earth mass and the Earth
        // radius are the units the whole catalogue is stated in, so this body is 1.0 of each.
        // Without it nothing ever states one — the only writers of bulk are the procedural realizer
        // and an admin command, and the overworld passes through neither — so getRadius() stays
        // BULK_UNSET.
        // The gravity above is STATED, so it is marked authored and setBulk leaves it alone; here the
        // derived value happens to agree, and that agreement is not what the mark is for.
        earth.setGravityAuthored(true);
        earth.setBulk(1d, 1d);
        // After the bulk and the temperature, because the air is decided by both.
        earth.realizeAtmosphere(true, 100);
        // ONE AU, stated as one: the field is a count of 100 km units, so a literal 100 would
        // put Earth 10 000 km from the Sun.
        earth.orbitalDist = AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU;
        earth.skyColor = new float[]{1f, 1f, 1f};
        earth.setName("Earth");
        earth.isNativeDimension = false;
        // The star is the throwaway Sol above rather than the registered one on purpose: this runs
        // from the constructor, before the star registry holds a Sol to hand back.
        earth.setStar(sol);
    }

    /**
     * The galaxy as the CALLER's side knows it: the running server's on a server thread, the
     * connection's on the client's. Throws when that side has none (no server running, not
     * connected) rather than answering with an empty galaxy nobody could tell from a real one.
     */
    public static DimensionManager getInstance() {
        return Stellurgy.proxy.getDimensionManager();
    }

    public static DimensionProperties getEffectiveDimId(int dimId, BlockPos pos) {

        if (dimId == StellurgyConfiguration.getCurrentConfig().spaceDimId) {
            ISpaceObject spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(pos);
            if (spaceObject != null) return (DimensionProperties) spaceObject.getProperties().getParentProperties();
            else return getInstance().defaultSpaceDimensionProperties;
        } else return getInstance().getDimensionProperties(dimId);
    }

    public static DimensionProperties getEffectiveDimId(World world, BlockPos pos) {
        return getEffectiveDimId(world.provider.getDimension(), pos);
    }

    public static DimensionProperties getEffectiveDimId_byID(int dimId, BlockPos pos) {
        return getEffectiveDimId(dimId, pos);
    }

    /**
     * @return an Integer array of dimensions registered with this DimensionManager
     */
    public Integer[] getRegisteredDimensions() {
        Integer[] ret = new Integer[dimensionList.size()];
        return dimensionList.keySet().toArray(ret);
    }

    /**
     * @return List of dimensions registered with this manager that are currently loaded on the server/integrated server
     */
    public Integer[] getLoadedDimensions() {
        return getRegisteredDimensions();
    }

    //TODO: fix naming system

    /**
     * Increments the nextAvalible satellite ID and returns one
     *
     * @return next avalible id for satellites
     */
    public long getNextSatelliteId() {
        return nextSatelliteId++;
    }

    /**
     * @param satId long id of the satellite
     * @return a reference to the satellite object with the supplied ID
     */
    public SatelliteBase getSatellite(long satId) {

        //Hack to allow monitoring stations to properly reload after a server restart
        //Because there should never be a tile in the world where no planets have been generated load file first
        //Worst thing that can happen is there is no file and it gets genned later and the monitor does not reconnect
        if (!hasBeenInitialized && FMLCommonHandler.instance().getSide().isServer()) {
            loadDimensions(workingPath);
        }

        SatelliteBase satellite = overworldProperties.getSatellite(satId);

        if (satellite != null) return satellite;

        for (int i : this.getLoadedDimensions()) {
            if ((satellite = this.getDimensionProperties(i).getSatellite(satId)) != null)
                return satellite;
        }
        return null;
    }

    /**
     * @param dimId id to register the planet with
     * @return the name for the next planet
     */
    private String getNextName(int starId, int dimId) {
        return getStar(starId).getName() + " " + dimId;
    }

    /**
     * Called every tick to tick satellites
     */
    public void tickDimensions() {
        //Tick satellites
        for (int i : this.getLoadedDimensions()) {
            DimensionProperties prop = this.getDimensionProperties(i);
            prop.tick();

            //THIS CODE NEEDS TO BE MADE MORE EFFICIENT FOR MINING ROCKETS!!!!!
            if (net.minecraftforge.common.DimensionManager.getWorld(0).getTotalWorldTime() % 100 == 0)
                PacketHandler.sendToAll(new PacketSatellitesUpdate(i, prop));


        }
    }

    public void tickDimensionsClient() {
        //Tick satellites
        for (int i : this.getLoadedDimensions()) {
            this.getDimensionProperties(i).updateOrbit();
        }
    }

    /**
     * Sets the properies supplied for the supplied dimensionID, if the dimension does not exist, it is added to the list but not registered with minecraft
     *
     * @param dimId      id to set the properties of
     * @param properties to set for that dimension
     */
    public void setDimProperties(int dimId, DimensionProperties properties) {
        dimensionList.put(dimId, properties);
    }

    /**
     * Iterates though the list of existing dimIds, and returns the closest free id greater than two
     *
     * @return next free id
     */
    public int getNextFreeDim(int startingValue) {
        for (int i = Math.max(startingValue, 2); i < 10000; i++) {
            if (!net.minecraftforge.common.DimensionManager.isDimensionRegistered(i) && !dimensionList.containsKey(i))
                return i;
        }
        return Constants.INVALID_PLANET;
    }

    public int getNextFreeStarId() {
        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            if (!starList.containsKey(i)) return i;
        }
        return -1;
    }

    /**
     * @param dimId dimension id to check
     * @return true if it can be traveled to, in general if it has a surface
     */
    public boolean canTravelTo(int dimId) {
        return net.minecraftforge.common.DimensionManager.isDimensionRegistered(dimId) && dimId != Constants.INVALID_PLANET && getDimensionProperties(dimId).hasSurface();
    }

    /**
     * Attempts to register a dimension with {@link DimensionProperties}, if the dimension has not yet been registered, sends a packet containing the dimension information to all connected clients
     *
     * @param properties {@link DimensionProperties} to register
     * @return false if the dimension has not been registered, true if it is being newly registered
     */
    public boolean registerDim(@Nonnull DimensionProperties properties, boolean registerWithForge) {
        boolean bool = registerDimNoUpdate(properties, registerWithForge);

        if (bool) PacketHandler.sendToAll(new PacketDimInfo(properties.getId(), properties));
        return bool;
    }

    /**
     * Attempts to register a dimension without sending an update to the client
     *
     * @param properties        {@link DimensionProperties} to register
     * @param registerWithForge if true also registers the dimension with forge
     * @return true if the dimension has NOT been registered before, false if the dimension IS registered exist already
     */
    public boolean registerDimNoUpdate(@Nonnull DimensionProperties properties, boolean registerWithForge) {
        int dimId = properties.getId();

        if (dimensionList.containsKey(dimId)) return false;

        //Avoid registering gas giants as dimensions
        if (registerWithForge && properties.hasSurface() && !net.minecraftforge.common.DimensionManager.isDimensionRegistered(dimId)) {

            if (properties.isAsteroid())
                net.minecraftforge.common.DimensionManager.registerDimension(dimId, AsteroidDimensionType);
            else net.minecraftforge.common.DimensionManager.registerDimension(dimId, PlanetDimensionType);
        }
        dimensionList.put(dimId, properties);

        return true;
    }

    /**
     * Unregisters all dimensions associated with this DimensionManager from both Minecraft and this DimnensionManager
     */
    public void unregisterAllDimensions() {
        for (Entry<Integer, DimensionProperties> dimSet : dimensionList.entrySet()) {
            if (dimSet.getValue().isNativeDimension && dimSet.getValue().hasSurface() && net.minecraftforge.common.DimensionManager.isDimensionRegistered(dimSet.getKey())) {
                net.minecraftforge.common.DimensionManager.unregisterDimension(dimSet.getKey());
            }
        }
        dimensionList.clear();
        starList.clear();
    }

    /**
     * Deletes and unregisters the dimensions, as well as all child dimensions, from the game
     *
     * @param dimId the dimensionId to delete
     */
    /**
     * A client's half of a deletion: drops the body, and the moons the server deletes with it, from
     * what this connection knows. It touches no world, no Forge registration and no file — those are
     * the server's, and in single player the integrated server has already removed them.
     */
    public void forgetDimension(int dimId) {
        DimensionProperties properties = dimensionList.remove(dimId);
        if (properties == null) {
            return;
        }
        unlinkFromSystem(properties);
        for (Integer child : new ArrayList<>(properties.getChildPlanets())) {
            forgetDimension(child);
        }
    }

    private static void unlinkFromSystem(DimensionProperties properties) {
        if (properties.getStar() != null) properties.getStar().removePlanet(properties);
        if (properties.isMoon()) {
            properties.getParentProperties().removeChild(properties.getId());
        }
    }

    public void deleteDimension(int dimId) {

        if (net.minecraftforge.common.DimensionManager.getWorld(dimId) != null) {
            Stellurgy.logger.warn("Cannot delete dimension " + dimId + " it is still loaded");
            return;
        }

        DimensionProperties properties = dimensionList.get(dimId);

        //Can happen in some rare cases
        if (properties == null) return;

        unlinkFromSystem(properties);

        if (properties.hasChildren()) {

            Iterator<Integer> iterator = properties.getChildPlanets().iterator();
            while (iterator.hasNext()) {
                Integer child = iterator.next();
                iterator.remove(); //Avoid CME
                deleteDimension(child);

                PacketHandler.sendToAll(new PacketDimInfo(child, null));
            }
        }

        //TODO: check for world loaded
        // If not native to Stellurgy let the mod it's registered to handle it
        if (properties.isNativeDimension) {
            if (net.minecraftforge.common.DimensionManager.isDimensionRegistered(dimId)) {
                if (net.minecraftforge.common.DimensionManager.getWorld(dimId) != null)
                    net.minecraftforge.common.DimensionManager.unloadWorld(dimId);

                net.minecraftforge.common.DimensionManager.unregisterDimension(dimId);
            }
        }
        dimensionList.remove(dimId);

        // A dimension id goes straight back into circulation (getNextFreeDim hands a deleted one
        // back), so the durable cell name recorded against it has to go with the world it named.
        // Leaving it behind means the next body given this id silently inherits a cell in whatever
        // system the deleted one belonged to — and since the two bodies then have different anchors,
        // no per-system audit ever compares them.
        dev.stannismod.stellurgy.universe.UniverseRegistry.forgetNameOnServer(dimId);

        //Delete World Folder
        File file = new File(getCurrentSaveRootDirectory(), workingPath + "/DIM" + dimId);

        try {
            FileUtils.deleteDirectory(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean isInitialized() {

        return hasBeenInitialized;
    }

    /** The lowest dimension id a planet may take right now. */
    public int getDimOffset() {
        return dimOffset;
    }

    /** Seed the cursor: from the configured minimum, or back to where a load found it. */
    public void setDimOffset(int dimOffset) {
        this.dimOffset = dimOffset;
    }

    /** Move the cursor past dimensions a planet load has just claimed. */
    public void advanceDimOffset(int claimed) {
        this.dimOffset += claimed;
    }

    /** Whether this save's players have reached the moon. */
    public boolean hasReachedMoon() {
        return hasReachedMoon;
    }

    /** Whether this save's players have reached warp. */
    public boolean hasReachedWarp() {
        return hasReachedWarp;
    }

    /** Record moon progression for this save. */
    public void setReachedMoon(boolean reached) {
        hasReachedMoon = reached;
    }

    /** Record warp progression for this save. */
    public void setReachedWarp(boolean reached) {
        hasReachedWarp = reached;
    }

    /**
     * @param dimId id of the dimention of which to get the properties
     * @return DimensionProperties representing the dimId given
     */
    public DimensionProperties getDimensionProperties(int dimId) {

        //If we're trying to get star properties for orbit and such, this is a proxy
        if (dimId >= Constants.STAR_ID_OFFSET) {
            StellarBody star = getStar(dimId - Constants.STAR_ID_OFFSET);
            if (star == null) return overworldProperties;

            DimensionProperties newprops = new DimensionProperties(dimId);
            newprops.setName(star.getName());
            return newprops;
        }

        DimensionProperties properties = dimensionList.get(dimId);
        if (dimId == StellurgyConfiguration.getCurrentConfig().spaceDimId || dimId == Integer.MIN_VALUE) {
            return defaultSpaceDimensionProperties;
        }
        return properties == null ? overworldProperties : properties;
    }

    /**
     * The properties of the body registered as {@code dimId}, or {@code null} when there is no such
     * body.
     *
     * <p>{@link #getDimensionProperties(int)} answers an unknown id with the OVERWORLD, which is the
     * right lenience for rendering and for the many callers that only need something to read a colour
     * off. It is the wrong answer for anything that has to know whether a body EXISTS: a saved jump
     * target naming a dimension a pack update removed would resolve, silently, to Earth, and the ship
     * would fly to a destination the pilot never chose. Callers that must be able to say "gone" ask
     * this one.</p>
     */
    public DimensionProperties getDimensionPropertiesOrNull(int dimId) {
        return dimensionList.get(dimId);
    }

    /**
     * @param id star id for which to get the object
     * @return the {@link StellarBody} object
     */
    public StellarBody getStar(int id) {
        return starList.get(id);
    }

    /**
     * @return the ids of the SYSTEMS — one per star that is nobody's companion
     *
     * <p>Companions are addressable through {@link #getStar(int)} but are not systems: they are drawn,
     * saved, synced and placed as part of the primary they orbit. A consumer that walked every
     * registered star instead would draw a binary twice on the map, write it twice to XML and give
     * its companion a galactic address of its own.</p>
     */
    public Set<Integer> getStarIds() {
        Set<Integer> ids = new HashSet<>();
        for (Entry<Integer, StellarBody> e : starList.entrySet()) {
            if (e.getValue() != null && e.getValue().getParentStar() == null) {
                ids.add(e.getKey());
            }
        }
        return ids;
    }

    /** The SYSTEMS — see {@link #getStarIds()}. */
    public Collection<StellarBody> getStars() {
        List<StellarBody> primaries = new ArrayList<>();
        for (StellarBody star : starList.values()) {
            if (star != null && star.getParentStar() == null) {
                primaries.add(star);
            }
        }
        return primaries;
    }

    /**
     * Adds a star to the handler, together with every companion under it.
     *
     * <p>A companion is a star like any other and gets an id of its own here, because the id space is
     * this registry's to hand out and a companion that is not in {@code starList} cannot be resolved
     * by {@link #getStar(int)} — which is how a planet finds the star it orbits. Without that, a
     * companion could be described but never orbited: the hierarchy existed in storage and nowhere
     * else.</p>
     *
     * <p>An id already in use by a DIFFERENT star is replaced rather than honoured; a companion that
     * already holds its own id (a reload, a re-registration) keeps it, so ids survive a save.</p>
     *
     * @param star star to add
     */
    public void addStar(StellarBody star) {
        if (star == null) {
            return;
        }
        starList.put(star.getId(), star);
        addCompanionsOf(star);
    }

    private void addCompanionsOf(StellarBody primary) {
        for (StellarBody companion : primary.getSubStars()) {
            if (companion == null) {
                continue;
            }
            StellarBody holder = starList.get(companion.getId());
            if (holder != null && holder != companion) {
                companion.setId(getNextFreeStarId());
            }
            starList.put(companion.getId(), companion);
            addCompanionsOf(companion);
        }
    }

    /**
     * Removes the star from the handler
     *
     * @param id id of the star to remove
     */
    public void removeStar(int id) {
        //TODO: actually remove subPlanets et
        starList.remove(id);
    }

    /**
     * Saves all dimension data, satellites, and space stations to disk, SHOULD NOT BE CALLED OUTSIDE OF WORLDSAVEEVENT
     *
     * @param filePath file path to which to save the data
     */
    public void saveDimensions(String filePath) throws Exception {

        if (starList.isEmpty() || dimensionList.isEmpty()) {
            throw new Exception("Missing Stars");
        }

        NBTTagCompound nbt = new NBTTagCompound();
        NBTTagCompound dimListnbt = new NBTTagCompound();


        //Save SolarSystems first
        NBTTagCompound solarSystem = new NBTTagCompound();
        for (Entry<Integer, StellarBody> stars : starList.entrySet()) {
            NBTTagCompound solarNBT = new NBTTagCompound();
            stars.getValue().writeToNBT(solarNBT);
            solarSystem.setTag(stars.getKey().toString(), solarNBT);
        }

        nbt.setTag("starSystems", solarSystem);

        //Save satelliteId
        nbt.setLong("nextSatelliteId", nextSatelliteId);

        //Save Overworld
        for (Entry<Integer, DimensionProperties> dimSet : dimensionList.entrySet()) {

            NBTTagCompound dimNbt = new NBTTagCompound();
            dimSet.getValue().writeToNBT(dimNbt);
            dimListnbt.setTag(dimSet.getKey().toString(), dimNbt);
        }

        nbt.setTag("dimList", dimListnbt);


        //Stats
        NBTTagCompound stats = new NBTTagCompound();
        stats.setBoolean("hasReachedMoon", hasReachedMoon);
        stats.setBoolean("hasReachedWarp", hasReachedWarp);
        nbt.setTag("stat", stats);

        NBTTagCompound nbtTag = new NBTTagCompound();
        SpaceObjectManager.getSpaceManager().writeToNBT(nbtTag);
        nbt.setTag("spaceObjects", nbtTag);

        String xmlOutput = XMLPlanetLoader.writeXML(this, packGalaxyConfig);

        try {
            File planetXMLOutput = new File(net.minecraftforge.common.DimensionManager.getCurrentSaveRootDirectory(), filePath + worldXML);

            // ensure directory exists
            File xmlDir = planetXMLOutput.getParentFile();
            if (xmlDir != null) xmlDir.mkdirs();

            // temp file MUST be in same directory for atomic move to work reliably
            File tmpFileXml = new File(xmlDir, planetXMLOutput.getName() + ".tmp");

            if (tmpFileXml.exists()) tmpFileXml.delete();
            try (FileOutputStream bufOutStream = new FileOutputStream(tmpFileXml)) {
                bufOutStream.write(xmlOutput.getBytes(StandardCharsets.UTF_8));
                bufOutStream.flush();
                bufOutStream.getFD().sync();
            }

            // commit: atomic swap if supported, fallback to non-atomic move if not supported
            try {
                Files.move(tmpFileXml.toPath(), planetXMLOutput.toPath(), REPLACE_EXISTING, ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpFileXml.toPath(), planetXMLOutput.toPath(), REPLACE_EXISTING);
            }
            // best-effort cleanup if something went wrong mid-commit
            if (tmpFileXml.exists()) tmpFileXml.delete();

            File file = new File(getCurrentSaveRootDirectory(), filePath + tempFile);

            // ensure directory exists
            File dataDir = file.getParentFile();
            if (dataDir != null) dataDir.mkdirs();

            // temp file must be in same directory as target for atomic move to be useful
            File tmpFile = new File(dataDir, file.getName() + ".tmp");
            if (tmpFile.exists()) tmpFile.delete();

            try (FileOutputStream tmpFileOut = new FileOutputStream(tmpFile);
                 BufferedOutputStream bufferedOut = new BufferedOutputStream(tmpFileOut);
                 GZIPOutputStream gzipOut = new GZIPOutputStream(bufferedOut);
                 DataOutputStream outStream = new DataOutputStream(gzipOut)) {

                CompressedStreamTools.write(nbt, outStream);

                outStream.flush();       // push DataOutputStream into gzip
                gzipOut.finish();        // write gzip footer
                bufferedOut.flush();     // push compressed bytes to file stream
                tmpFileOut.getFD().sync(); // sync complete gzip file
            }

            try {
                Files.move(tmpFile.toPath(), file.toPath(), REPLACE_EXISTING, ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                try {
                    Files.move(tmpFile.toPath(), file.toPath(), REPLACE_EXISTING);
                } catch (Exception e2) {
                    Stellurgy.logger.error("Cannot save advanced rocketry planet file, you may be able to find backups in " + getCurrentSaveRootDirectory());
                    if (tmpFile.exists()) tmpFile.delete();
                    e2.printStackTrace();
                }
            } catch (Exception e) {
                Stellurgy.logger.error("Cannot save advanced rocketry planet file, you may be able to find backups in " + getCurrentSaveRootDirectory());
                if (tmpFile.exists()) tmpFile.delete();
                e.printStackTrace();
            }


        } catch (IOException e) {
            Stellurgy.logger.error("Cannot save advanced rocketry planet files, you may be able to find backups in " + getCurrentSaveRootDirectory());
            e.printStackTrace();
        }
    }

    /**
     * @param dimId integer id of the dimension
     * @return true if the dimension exists and is registered
     */
    public boolean isDimensionCreated(int dimId) {
        return dimensionList.containsKey(dimId) || dimId == StellurgyConfiguration.getCurrentConfig().spaceDimId;
    }

    /**
     * Raw membership in the GLOBAL known-planet set (seeded from {@code initiallyKnownPlanets} / the
     * {@code <isKnown>} XML flag, plus runtime discovery such as beacons and warp-controller finds). This is
     * the universe layer's from-start visibility source of truth; unlike
     * {@link dev.stannismod.stellurgy.inventory.IPlanetDefiner#isPlanetKnown} it applies NO
     * {@code planetsMustBeDiscovered} gate and no per-station discovery list.
     */
    public boolean isPlanetKnown(int dimId) {
        return knownPlanets != null && knownPlanets.contains(dimId);
    }

    @Nullable
    private File getCurrentSaveRootDirectory() {
        File dir = net.minecraftforge.common.DimensionManager.getCurrentSaveRootDirectory();
        if (dir == null) {
            if (FMLCommonHandler.instance().getMinecraftServerInstance() == null) return null;

            // Server about to start, but worlds haven't loaded yet
            return new File(FMLCommonHandler.instance().getSavesDirectory(), FMLCommonHandler.instance().getMinecraftServerInstance().getFolderName());
        }
        return dir;
    }

    public void createAndLoadDimensions(boolean resetFromXml) {
        //Load planet files
        //Note: loading this modifies dimOffset
        int savedDimOffset = this.dimOffset;
        DimensionPropertyCoupling dimCouplingList = null;
        XMLPlanetLoader loader = null;
        boolean loadedFromXML = false;
        File file;

        //Check advRocketry folder first
        File localFile;
        localFile = file = new File(getCurrentSaveRootDirectory() + "/" + DimensionManager.workingPath + "/planetDefs.xml");
        logger.info("Checking for config at " + file.getAbsolutePath());

        if (!file.exists() || resetFromXml) { //Hi, I'm if check #42, I am true if the config is not in the world/advRocketry folder
            String newFilePath = "./config/" + dev.stannismod.stellurgy.api.StellurgyConfiguration.configFolder + "/planetDefs.xml";
            if (!file.exists()) logger.info("File not found.  Now checking for config at " + newFilePath);

            file = new File(newFilePath);

            //Copy file to local dir
            if (file.exists()) {
                logger.info("Advanced Planet Config file Found!  Copying to world specific directory");
                try {
                    File dir = new File(localFile.getAbsolutePath().substring(0, localFile.getAbsolutePath().length() - localFile.getName().length()));

                    //File cannot exist due to if check #42
                    if ((dir.exists() || dir.mkdirs())) {
                        Files.copy(file.toPath(), localFile.toPath(), REPLACE_EXISTING);
                        logger.info("Copy success!");
                    } else {
                        logger.warn("Unable to create directory " + dir.getAbsolutePath());
                    }
                } catch (IOException e) {
                    logger.warn("Unable to write file " + localFile.getAbsolutePath());
                }
            }
        }

        if (file.exists()) {
            logger.info("Advanced Planet Config file Found!  Loading from file.");
            loader = new XMLPlanetLoader();

            // A fatal/structural failure propagates so Forge produces a normal crash
            // report (diagnosable) instead of the old silent FMLCommonHandler.exitJava.
            // Recoverable per-planet config mistakes are skipped inside readAllPlanets.
            dimCouplingList = loader.loadPlanetsOrThrow(file, this);
            this.dimOffset += dimCouplingList.dims.size();
        }
        //End load planet files

        //Register hard coded dimensions
        Map<Integer, IDimensionProperties> loadedPlanets = loadDimensions(dev.stannismod.stellurgy.dimension.DimensionManager.workingPath);
        // Bodies of the file whose id another body already holds. Identity, not equality: the question
        // is "this object", and two refused bodies may carry the same id.
        java.util.Set<DimensionProperties> refusedBodies =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (loadedPlanets.isEmpty()) {
            int numRandomGeneratedPlanets = 9;
            int numRandomGeneratedGasGiants = 1;

            if (dimCouplingList != null) {
                logger.info("Loading initial planet config!");

                for (StellarBody star : dimCouplingList.stars) {
                    this.addStar(star);
                }

                for (DimensionProperties properties : dimCouplingList.dims) {
                    if (!this.registerDimNoUpdate(properties, properties.isNativeDimension)) {
                        // Refused: the id is held. Binding the body to its star anyway would put it in
                        // the star's id-keyed map OVER the holder, so the registry and the star would
                        // each name a different body under one id.
                        DimensionProperties holder = dimensionList.get(properties.getId());
                        logger.warn("planetDefs.xml: body '" + properties.getName() + "' is not loaded:"
                                + " dimension " + properties.getId() + " is already held by '"
                                + (holder == null ? "?" : holder.getName()) + "'. Give it a DIMID no"
                                + " other body states, or none at all.");
                        refusedBodies.add(properties);
                        continue;
                    }
                    properties.setStar(properties.getStarId(), this);
                }

                for (StellarBody star : dimCouplingList.stars) {
                    // The pack's body count is CARRIED, not consumed. It used to be spent here by a
                    // second world-making model seeded on the wall clock, which registered its worlds
                    // as Forge dimensions up front and made two saves of one seed differ. The count
                    // now bounds the ONE model's derived retinue for this system, and the worlds are
                    // realized on arrival like everywhere else.
                    star.setMaxRetinueBodies(loader.getMaxNumPlanets(star)
                            + loader.getMaxNumGasGiants(star));
                }

                loadedFromXML = true;
            }

            if (!loadedFromXML) {
                //Make Sol
                StellarBody sol = new StellarBody();
                sol.setTemperature(100);
                sol.setId(0);
                sol.setName("Sol");

                this.addStar(sol);

                //Add the overworld
                this.registerDimNoUpdate(overworldProperties, false);
                // BIND, not only list: Earth was seeded with a placeholder Sol (see seedEarthDefaults),
                // and addPlanet alone would list Earth under this Sol while Earth kept pointing at the
                // placeholder — every identity check ("same system") and every edit to star 0 then
                // passed Earth by. setStar lists it too.
                overworldProperties.setStar(sol);

                if (moonId == Constants.INVALID_PLANET)
                    moonId = this.getNextFreeDim(savedDimOffset);


                //Register the moon
                if (moonId != Constants.INVALID_PLANET) {
                    DimensionProperties dimensionProperties = new DimensionProperties(moonId);
                    dimensionProperties.realizeAtmosphere(false, 0);
                    dimensionProperties.setAverageTemp(20);
                    // TIDALLY LOCKED TO ITS PARENT, expressed the way this codebase expresses it:
                    // `getParentPlanetThetaFromMoon` moves the parent across a moon's sky by
                    // (orbitalPeriod / rotationalPeriod − 1), so a rotation equal to the orbit holds
                    // Earth still — which is what standing on the Moon looks like.
                    //
                    // NOT `setTidallyLocked(true)`: that flag says a world keeps one face to its
                    // STAR and removes the day/night cycle altogether. The Moon keeps one face to
                    // EARTH and still has a day and a night, so the flag would describe a different
                    // body. The period below is its own orbit — 27.32 days, 655 680 ticks.
                    dimensionProperties.rotationalPeriod = (int) Math.round(
                            dev.stannismod.stellurgy.util.AstronomicalBodyHelper.DAYS_PER_LUNAR_MONTH
                                    * 24000d);
                    dimensionProperties.gravitationalMultiplier = .166f; //Actual moon value
                    // The Moon's measured bulk, in the same units: 7.342e22 kg is 0.0123 Earth
                    // masses and 1 737.4 km is 0.2727 Earth radii. The gravity above is the stated
                    // one and stays stated — deriving it from these would give 0.1654 and silently
                    // move a shipped number for no reason. What was missing is the RADIUS: without
                    // it this body draws at the marker size and carries the flat 512-block shell.
                    dimensionProperties.setGravityAuthored(true);
                    dimensionProperties.setBulk(0.0123d, 0.2727d);
                    dimensionProperties.setName("Luna");
                    // 384 400 km, the Moon's real distance. An earlier 150 meant 7 500 km — 51 times
                    // too small, close enough to Earth's own 6 378 km radius that the two bodies'
                    // neighbourhoods overlapped, which is why "which body is this craft's frame" had
                    // no answer worth giving.
                    dimensionProperties.orbitalDist =
                            dev.stannismod.stellurgy.util.AstronomicalBodyHelper.MOON_REFERENCE_UNITS;
                    dimensionProperties.addBiome(StellurgyBiomes.moonBiome);
                    dimensionProperties.addBiome(StellurgyBiomes.moonBiomeDark);

                    dimensionProperties.setParentPlanet(overworldProperties);
                    dimensionProperties.setStar(this.getStar(0));
                    dimensionProperties.isNativeDimension = !Loader.isModLoaded("GalacticraftCore");
                    dimensionProperties.initDefaultAttributes();

                    this.registerDimNoUpdate(dimensionProperties, !Loader.isModLoaded("GalacticraftCore"));
                }

                this.getStar(0)
                        .setMaxRetinueBodies(numRandomGeneratedPlanets + numRandomGeneratedGasGiants);

                StellarBody star = new StellarBody();
                star.setTemperature(10);
                star.setPosX(300);
                star.setPosZ(-200);
                star.setId(this.getNextFreeStarId());
                star.setName("Wolf 12");
                this.addStar(star);
                star.setMaxRetinueBodies(5);

                star = new StellarBody();
                star.setTemperature(170);
                star.setPosX(-200);
                star.setPosZ(80);
                star.setId(this.getNextFreeStarId());
                star.setName("Epsilon ire");
                this.addStar(star);
                star.setMaxRetinueBodies(7);

                star = new StellarBody();
                star.setTemperature(200);
                star.setPosX(-150);
                star.setPosZ(250);
                star.setId(this.getNextFreeStarId());
                star.setName("Proxima Centaurs");
                this.addStar(star);
                star.setMaxRetinueBodies(3);

                star = new StellarBody();
                star.setTemperature(70);
                star.setPosX(-150);
                star.setPosZ(-250);
                star.setId(this.getNextFreeStarId());
                star.setName("Magnis Vulpes");
                this.addStar(star);
                star.setMaxRetinueBodies(2);


                star = new StellarBody();
                star.setTemperature(200);
                star.setPosX(50);
                star.setPosZ(-250);
                star.setId(this.getNextFreeStarId());
                star.setName("Ma-Roo");
                this.addStar(star);
                star.setMaxRetinueBodies(6);

                star = new StellarBody();
                star.setTemperature(120);
                star.setPosX(75);
                star.setPosZ(200);
                star.setId(this.getNextFreeStarId());
                star.setName("Alykitt");
                this.addStar(star);
                star.setMaxRetinueBodies(4);

            }
        }
        // The save's previous version string is written on every save and read by nobody. A
        // commented-out legacy-upgrade call used to be the reason it was kept in a field; 0.1.0
        // does not load pre-0.1.0 saves at all, so that call has no version to migrate from and the
        // field is gone. The stamp itself stays: a save that says which build wrote it is worth
        // having whether or not this code ever reads it back.

        //Attempt to load ore config from adv planet XML
        if (dimCouplingList != null) {
            //Register new stars
            for (StellarBody star : dimCouplingList.stars) {
                if (this.getStar(star.getId()) == null)
                    this.addStar(star);

                this.getStar(star.getId()).setName(star.getName());
                this.getStar(star.getId()).setPosX(star.getPosX());
                this.getStar(star.getId()).setPosZ(star.getPosZ());
                this.getStar(star.getId()).setSize(star.getSize());
                this.getStar(star.getId()).setTemperature(star.getTemperature());
                this.getStar(star.getId()).subStars = star.subStars;
                this.getStar(star.getId()).setBlackHole(star.isBlackHole());
            }

            for (DimensionProperties properties : dimCouplingList.dims) {
                // A body refused above is not loaded at all: nothing below may register it, nor pour
                // its ore table into the body that holds its id.
                if (refusedBodies.contains(properties)) {
                    continue;
                }

                //Register dimensions loaded by other mods if not already loaded
                if (!properties.isNativeDimension && properties.getStar() != null && !this.isDimensionCreated(properties.getId())) {
                    for (StellarBody star : dimCouplingList.stars) {
                        for (StellarBody loadedStar : this.getStars()) {
                            if (star.getId() == properties.getStarId() && star.getName().equals(loadedStar.getName())) {
                                this.registerDimNoUpdate(properties, false);
                                properties.setStar(loadedStar);
                            }
                        }
                    }
                }


                if (loadedPlanets.containsKey(properties.getId())) {
                    DimensionProperties loadedDim = (DimensionProperties) loadedPlanets.get(properties.getId());
                    if (loadedDim != null) {
                        properties.copyData(loadedDim);
                    }
                }
                if (properties.isNativeDimension)
                    this.registerDim(properties, properties.isNativeDimension);
                //TODO: add properties fromXML


                if (properties.oreProperties != null) {
                    DimensionProperties loadedProps = this.getDimensionProperties(properties.getId());

                    if (loadedProps != null) loadedProps.oreProperties = properties.oreProperties;
                }
            }

            //Don't load random planets twice on initial load
            //TODO: rework the logic, low priority because low time cost and one time run per world
            // C130: loadedFromXML is only set on the fresh-world (loadedPlanets
            // empty) branch, so on a reload where a numPlanets>0 XML is present
            // (resetFromXml, or a re-copied config) this loop re-ran and accreted
            // duplicate random planets every load. Gate on the true first-run
            // discriminator: only generate randoms when no persisted dims exist.
            // Carry each system's body count into the universe layer instead of spending it on a
            // second world-making model here — see the sibling site above.
            //
            // NOT gated on the first run. The gate above exists because this loop USED to generate
            // random planets, and re-running that accreted duplicates every load; carrying a count is
            // idempotent and has no such hazard. Left under the gate it meant a star's retinue size
            // was known only in the session that created the world — every reload started it at zero,
            // `withDerivedRetinue` then returned the authored list untouched, and a system that had
            // shown its whole retinue came back holding only what was explicitly written down.
            for (StellarBody star : dimCouplingList.stars) {
                StellarBody registered = this.getStar(star.getId());
                int retinue = loader.getMaxNumPlanets(star) + loader.getMaxNumGasGiants(star);
                if (registered != null) {
                    registered.setMaxRetinueBodies(retinue);
                }
                star.setMaxRetinueBodies(retinue);
            }

            // Buffer authored galactic anchor coords for the Layer-1 universe registry. Worlds are not
            // loaded yet (this runs at serverAboutToStart), so they are drained once worlds are up.
            stagedAnchors = dimCouplingList.anchorCoords == null
                    ? new java.util.HashMap<Integer, dev.stannismod.stellurgy.universe.GalacticAnchor>()
                    : new java.util.HashMap<>(dimCouplingList.anchorCoords);
            stagedAnchorsReset = resetFromXml;
        }

        // Hand the pack's <galaxyGen> knobs to the universe layer. The generator built from them is
        // installed for real at populate(), because WHICH world model interprets these knobs is a
        // property of the SAVE (its schema stamp) and the save is not reachable here — worlds are not
        // loaded yet. The pack states the parameters; the world states the version.
        //
        // Stage the pack's <galaxyGen> for populate() to pair with the save's schema stamp. NO
        // generator is installed here: this runs at serverAboutToStart, and the save's model is not
        // resolved until populate() at serverStarting, so anything installed in that window would be
        // the CURRENT model rather than the one this world is owed. A provisional install used to sit
        // here, justified by a comment saying nothing derives before populate replaces it - and if
        // that is true it did nothing, while if it is false it answered an old save with the newest
        // model. Where the save carries no stamp, reconcileSchema adopts the current schema at the
        // one install point, loudly and with a stamp written; that is the same outcome without the
        // window.
        // No planetDefs.xml is a pack that states nothing, and that is the shipped configuration — not
        // an empty galaxy, which a pack asks for only with <galaxyGen procedural="false"/>.
        packGalaxyConfig = (dimCouplingList != null) ? dimCouplingList.galaxyGenConfig
                : dev.stannismod.stellurgy.universe.GalaxyGenConfig.defaults();
        // C129: registration authority on load was planetDefs.xml only (the loop
        // above), while per-dim persisted state lives in temp.dat (loadedPlanets).
        // A dim present in temp.dat but absent from a hand-edited / restored /
        // reset XML was therefore never registered and became unreachable (its
        // DIM<n> save data orphaned). Reconcile: register any persisted dim the
        // XML pass did not. Idempotent via isDimensionCreated, so normal reloads
        // (XML and temp.dat in sync) and fresh worlds (loadedPlanets empty) are
        // no-ops; also heals the missing-XML case where the loop above is skipped.
        for (Map.Entry<Integer, IDimensionProperties> entry : loadedPlanets.entrySet()) {
            DimensionProperties props = (DimensionProperties) entry.getValue();
            if (props == null || this.isDimensionCreated(entry.getKey()))
                continue;
            this.registerDimNoUpdate(props, props.isNativeDimension);
            props.setStar(props.getStarId(), this);
        }

        // The save's planet-type table: its file's <planetType> section, or the code-shipped set when
        // the file states none.
        planetTypes = dev.stannismod.stellurgy.universe.PlanetTypes.authored(
                dimCouplingList == null ? null : dimCouplingList.planetTypes);

        // make sure to set dim offset back to original to make things consistant
        this.dimOffset = savedDimOffset;

        this.knownPlanets.addAll(initiallyKnownPlanets);


        // Whatever saved path dim 0 arrived by — temp.dat or the shipped defaults; the planet file
        // refuses a sizeless body itself — it is the overworld and it has a size. Here rather than in
        // one of the loops above because only the LAST writer decides what the world runs with.
        repairOverworldBulk(dimensionList.get(0));

        // Run all sanity checks now
        //Try to fix invalid objects
        for (ISpaceObject spaceObject : SpaceObjectManager.getSpaceManager().getSpaceObjects()) {
            int orbitingId = spaceObject.getOrbitingPlanetId();
            if (!isDimensionCreated(orbitingId) && orbitingId != 0 && orbitingId != SpaceObjectManager.WARPDIMID && orbitingId < Constants.STAR_ID_OFFSET) {
                Stellurgy.logger.warn("Dimension ID " + spaceObject.getOrbitingPlanetId() + " is not registered and a space station is orbiting it, moving to dimid 0");
                SpaceObjectManager.getSpaceManager().moveStationToBody(spaceObject, 0);
                spaceObject.setDestOrbitingBody(0);
                spaceObject.setOrbitingBody(0);
            }
        }
    }

    /**
     * Loads all information to rebuild the galaxy and solar systems from disk into the current instance of DimensionManager
     *
     * @param filePath file path from which to load the information
     */
    public Map<Integer, IDimensionProperties> loadDimensions(String filePath) {
        hasBeenInitialized = true;
        Map<Integer, IDimensionProperties> loadedDimProps = new HashMap<>();

        FileInputStream inStream;
        NBTTagCompound nbt;
        try {
            File file = new File(getCurrentSaveRootDirectory(), filePath + tempFile);

            if (!file.exists()) {
                new File(file.getAbsolutePath().substring(0, file.getAbsolutePath().length() - file.getName().length())).mkdirs();


                file.createNewFile();
                return loadedDimProps;
            }

            inStream = new FileInputStream(file);
            nbt = CompressedStreamTools.readCompressed(inStream);
            inStream.close();
        } catch (EOFException e) {
            //Silence you fool!
            //Patch to fix JEI printing when trying to load planets too early
            return loadedDimProps;
        } catch (IOException e) {
            e.printStackTrace();
            return loadedDimProps;
        }//TODO: try not to obliterate planets in the future


        //Load SolarSystems first
        NBTTagCompound solarSystem = nbt.getCompoundTag("starSystems");

        if (solarSystem.hasNoTags()) return loadedDimProps;

        NBTTagCompound stats = nbt.getCompoundTag("stat");
        hasReachedMoon = stats.getBoolean("hasReachedMoon");
        hasReachedWarp = stats.getBoolean("hasReachedWarp");

        for (String key : solarSystem.getKeySet()) {

            NBTTagCompound solarNBT = solarSystem.getCompoundTag(key);
            StellarBody star = new StellarBody();
            star.readFromNBT(solarNBT);
            starList.put(star.getId(), star);
        }

        nbt.setTag("starSystems", solarSystem);

        nextSatelliteId = nbt.getLong("nextSatelliteId");

        NBTTagCompound dimListNbt = nbt.getCompoundTag("dimList");

        for (String key : dimListNbt.getKeySet()) {
            DimensionProperties properties = DimensionProperties.createFromNBT(Integer.parseInt(key), dimListNbt.getCompoundTag(key));

            int keyInt = Integer.parseInt(key);
				/*if(!net.minecraftforge.common.DimensionManager.isDimensionRegistered(keyInt) && properties.isNativeDimension && !properties.isGasGiant()) {
					if(properties.isAsteroid())
						net.minecraftforge.common.DimensionManager.registerDimension(keyInt, AsteroidDimensionType);
					else
						net.minecraftforge.common.DimensionManager.registerDimension(keyInt, PlanetDimensionType);
				}*/

            loadedDimProps.put(keyInt, properties);
            //TODO: print unable to register world
        }


        //Check for tag in case old version of Adv rocketry is in use
        if (nbt.hasKey("spaceObjects")) {
            NBTTagCompound nbtTag = nbt.getCompoundTag("spaceObjects");
            SpaceObjectManager.getSpaceManager().readFromNBT(nbtTag);
        }

        nbt.setString("prevVersion", Stellurgy.instance.version);

        return loadedDimProps;
    }

    /**
     * @param destinationDimId
     * @param dimension
     * @return true if the two dimensions are in the same planet/moon system
     */
    public boolean areDimensionsInSamePlanetMoonSystem(int destinationDimId, int dimension) {
        return PlanetaryTravelHelper.isTravelAnywhereInPlanetarySystem(destinationDimId, dimension);
    }
}
