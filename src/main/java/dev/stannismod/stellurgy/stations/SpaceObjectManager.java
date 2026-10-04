package dev.stannismod.stellurgy.stations;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.Constants.NBT;
import net.minecraftforge.fml.common.gameevent.TickEvent.PlayerTickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.StellurgyAPI;
import dev.stannismod.stellurgy.api.ISpaceObjectManager;
import dev.stannismod.stellurgy.api.stations.ISpaceObject;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.network.PacketSpaceStationInfo;
import dev.stannismod.stellurgy.network.PacketStationUpdate;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.util.HashedBlockPosition;

import javax.annotation.Nonnull;
import java.util.*;

public class SpaceObjectManager implements ISpaceObjectManager {
    public static final int WARPDIMID = Integer.MIN_VALUE;
    /**
     * The kinds of space object a save can hold, by the name written into it. Effectively final,
     * client/dedicated-server lifetime: filled by {@link #registerSpaceObjectType} from the mod's init
     * and only read after.
     */
    private static final Map<String, Class<?>> TYPES_BY_NAME = new HashMap<>();
    /** Effectively final, process lifetime: filled only by SpaceObjectManager.registerSpaceObjectType. */
    private static final Map<Class<?>, String> NAMES_BY_TYPE = new HashMap<>();
    private int nextId = 1;
    private long nextStationTransitionTick = -1;
    //station ids to object
    private HashMap<Integer, ISpaceObject> stationLocations;
    //Map of planet IDs to station Ids
    private HashMap<Integer, List<ISpaceObject>> spaceStationOrbitMap;
    private HashMap<Integer, Long> temporaryDimensions;                //Stores a list of temporary dimensions to time they vanish
    private HashMap<Integer, Integer> temporaryDimensionPlayerNumber;

    /**
     * The stations one side knows: the running server's, built when it starts and dropped when it
     * stops, or a client connection's, built on connect and dropped with it.
     */
    public SpaceObjectManager() {
        stationLocations = new HashMap<>();
        spaceStationOrbitMap = new HashMap<>();
        temporaryDimensions = new HashMap<>();
    }

    /**
     * The stations as the CALLER's side knows them: the running server's on a server thread, the
     * connection's on the client's. Throws when that side has none.
     */
    public static SpaceObjectManager getSpaceManager() {
        return Stellurgy.proxy.getSpaceObjectManager();
    }

    /**
     * @param id
     * @return {@link SpaceStationObject} object registered to this spaceObject id, or null if doesn't exist
     */
    public ISpaceObject getSpaceStation(int id) {
        return stationLocations.get(id);
    }

    public Collection<ISpaceObject> getSpaceObjects() {
        return stationLocations.values();
    }

    /**
     * @return the next valid space object id and increments the value for the next one
     */
    public int getNextStationId() {
        for (int i = 1; i < Integer.MAX_VALUE; i++)
            if (!stationLocations.containsKey(i))
                return i;
        return Integer.MAX_VALUE;
    }

    /**
     * Registers the spaceobject class with this manager, this must be done or the object cannot be saved!
     *
     * @param str   key with which to register the spaceObject type
     * @param clazz class of space object to register
     */
    public static void registerSpaceObjectType(String str, Class<?> clazz) {
        if (TYPES_BY_NAME.containsKey(str) || NAMES_BY_TYPE.containsKey(clazz)) {
            throw new IllegalStateException("space object type " + str + " / " + clazz.getName()
                    + " is already registered; types are registered once, at init");
        }
        TYPES_BY_NAME.put(str, clazz);
        NAMES_BY_TYPE.put(clazz, str);
    }

    /**
     * Attempts to get a registered SpaceObject
     *
     * @param id string identifier of the spaceobject
     * @return a new instance of the spaceobject or null if not registered
     */
    public static ISpaceObject getNewSpaceObjectFromIdentifier(String id) {
        Class<?> clazz = TYPES_BY_NAME.get(id);

        try {
            return (ISpaceObject) clazz.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String getIdentifierFromClass(Class<? extends ISpaceObject> clazz) {
        return NAMES_BY_TYPE.get(clazz);
    }

    /**
     * Gets the object at the location of passed Block x and z
     *
     * @return Space object occupying the block coords of null if none
     */
    public ISpaceObject getSpaceStationFromBlockCoords(@Nonnull BlockPos pos) {

        int stationSize = StellurgyConfiguration.getCurrentConfig().stationSize;
        // Stations spawn at 2*stationSize*grid + stationSize/2 (see registerSpaceObject),
        // i.e. the grid point plus a half-cell offset. Subtract that same offset before
        // reverse-mapping so every position on a station's habitable footprint — including
        // the +X/+Z block-reach sliver just past the confinement wall — maps back to the
        // owning grid cell instead of the empty neighbour (which returned null -> off-station
        // NPE/zero-power on tiles a player legitimately built at the perimeter).
        int x = Math.round((pos.getX() - stationSize / 2) / (2f * stationSize));
        int z = Math.round((pos.getZ() - stationSize / 2) / (2f * stationSize));
        int radius = Math.max(Math.abs(x), Math.abs(z));

        // Centre grid cell (0,0) is spiral index 0. The general formula below uses
        // (2*radius-1)^2, which at radius 0 evaluates to (-1)^2 = 1 — colliding with
        // grid (-1,-1) on index 1 — so special-case the centre. (Station id 0 is never
        // allocated: getNextStationId starts at 1, so this resolves to no station.)
        if (radius == 0)
            return getSpaceStation(0);

        int index = (int) Math.pow((2 * radius - 1), 2) + x + radius;

        if (Math.abs(z) != radius) {
            index = (int) Math.pow((2 * radius - 1), 2) + z + radius + (4 * radius + 2) - 1;

            if (x > 0)
                index += 2 * radius - 1;
        } else if (z > 0)
            index += 2 * radius + 1;

        return getSpaceStation(index);
    }

    /**
     * Registers a space object with this manager, the class must have been registered prior to this with registerSpaceObjectType!
     *
     * @param spaceObject
     * @param dimId
     * @param stationId
     */
    public void registerSpaceObject(@Nonnull ISpaceObject spaceObject, int dimId, int stationId) {
        spaceObject.setId(stationId);
        stationLocations.put(stationId, spaceObject);


        /*Calculate the location of a space station along a square spiral
         * here the top and bottom(including the corner locations) are filled first then the left and right last
         *
         * Example shown below:
         *9 A B C D
         *  1 2 3
         *  7 0 8
         *  4 5 6
         *E F.....
         */

        int radius = (int) Math.floor(Math.ceil(Math.sqrt(stationId + 1)) / 2);
        int ringIndex = (int) (stationId - Math.pow((radius * 2) - 1, 2));
        int x, z;

        if (ringIndex < (radius * 2 + 1) * 2) {
            x = ringIndex % (radius * 2 + 1) - radius;
            if (ringIndex < (radius * 2 + 1))
                z = -radius;
            else
                z = radius;
        } else {
            int newIndex = ringIndex - (radius * 2 + 1) * 2;
            z = newIndex % ((radius - 1) * 2 + 1) - (radius - 1);
            if (newIndex < ((radius - 1) * 2 + 1))
                x = -radius;
            else
                x = radius;
        }

        if (!spaceObject.hasCustomSpawnLocation())
            spaceObject.setSpawnLocation(2 * StellurgyConfiguration.getCurrentConfig().stationSize * x + StellurgyConfiguration.getCurrentConfig().stationSize / 2, 128, 2 * StellurgyConfiguration.getCurrentConfig().stationSize * z + StellurgyConfiguration.getCurrentConfig().stationSize / 2);

        spaceObject.setOrbitingBody(dimId);
        moveStationToBody(spaceObject, dimId, false);
    }

    /**
     * Registers a dimension that is set to expire at a given an expiration time
     *
     * @param spaceObject spaceObject to register
     * @param dimId       dimid to orbit around
     * @param expireTime  time at which to expire the dimension
     */
    public void registerTemporarySpaceObject(@Nonnull ISpaceObject spaceObject, int dimId, long expireTime) {
        int nextDimId = getNextStationId();
        temporaryDimensions.put(nextDimId, expireTime);
        temporaryDimensionPlayerNumber.put(dimId, 0);
        registerSpaceObject(spaceObject, nextDimId);
    }

    /**
     * Registers a space station and updates clients
     *
     * @param spaceObject
     * @param dimId       dimension to place it in orbit around, Constants.INVALID_PLANET for undefined
     */
    public void registerSpaceObject(@Nonnull ISpaceObject spaceObject, int dimId) {
        registerSpaceObject(spaceObject, dimId, getNextStationId());
        PacketHandler.sendToAll(new PacketSpaceStationInfo(spaceObject.getId(), spaceObject));
    }

    /**
     * A client's half of a station's removal: drops it from what this connection knows and sends
     * nothing — the removal itself is the server's.
     */
    public void forgetSpaceObject(int id) {
        ISpaceObject station = stationLocations.remove(id);
        if (station != null) {
            List<ISpaceObject> orbit = spaceStationOrbitMap.get(station.getOrbitingPlanetId());
            if (orbit != null) {
                orbit.remove(station);
            }
        }
    }

    public void unregisterSpaceObject(int id) {
        temporaryDimensions.remove(id);
        temporaryDimensionPlayerNumber.remove(id);
        spaceStationOrbitMap.remove(id);
        stationLocations.remove(id);
        PacketHandler.sendToAll(new PacketSpaceStationInfo(id, null));
    }

    /**
     * registers a dimension with the given station ID
     * Used on client to create stations on packet recieve from server
     * FOR INTERNAL USE ONLY
     *
     * @param spaceObject
     * @param dimId       dimension to place it in orbit around, Constants.INVALID_PLANET for undefined
     */
    @SideOnly(Side.CLIENT)
    public void registerSpaceObjectClient(@Nonnull ISpaceObject spaceObject, int dimId, int stationId) {
        registerSpaceObject(spaceObject, dimId, stationId);
    }

    /**
     * @param planetId id of the planet to get stations around
     * @return list of spaceObjects around the planet
     */
    public List<ISpaceObject> getSpaceStationsOrbitingPlanet(int planetId) {
        return spaceStationOrbitMap.get(planetId);
    }

    /**
     * Teleports a player who falls out of the world in space back to the station's spawn point, and
     * bounces a player off the walls between station cells. Run for every player tick on both sides;
     * only the server's half asks a manager.
     * TODO: prevent inf loop if nowhere to fall!
     */
    static void confinePlayerInSpace(@Nonnull PlayerTickEvent event) {
        if (event.player.world.provider.getDimension() == StellurgyConfiguration.getCurrentConfig().spaceDimId) {

            if (event.player.posY < 0 && !event.player.world.isRemote) {
                ISpaceObject spaceObject = getSpaceManager().getSpaceStationFromBlockCoords(event.player.getPosition());
                if (spaceObject != null) {

                    HashedBlockPosition loc = spaceObject.getSpawnLocation();

                    event.player.fallDistance = 0;
                    event.player.motionY = 0;
                    event.player.setPositionAndUpdate(loc.x, loc.y + 2, loc.z);
                    event.player.sendMessage(new TextComponentString("You wake up finding yourself back on the station"));
                }
            }

            int result = Math.abs(2 * (((int) event.player.posZ + StellurgyConfiguration.getCurrentConfig().stationSize / 2) % (2 * StellurgyConfiguration.getCurrentConfig().stationSize)) / StellurgyConfiguration.getCurrentConfig().stationSize);
            if (result == 0 || result == 3) {
                event.player.motionZ = -event.player.motionZ;
                if (result == 0) {
                    event.player.setPosition(event.player.posX, event.player.posY, event.player.posZ + (event.player.posZ < 0 ? Math.abs(event.player.posZ % 16) : (16 - event.player.posZ % 16)));
                } else
                    event.player.setPosition(event.player.posX, event.player.posY, event.player.posZ - (event.player.posZ < 0 ? 16 - Math.abs(event.player.posZ % 16) : (event.player.posZ % 16)));

            }

            //double posX = event.player.posX < 0 ? -event.player.posX - Configuration.stationSize : event.player.posX;

            result = Math.abs(2 * (((int) event.player.posX + StellurgyConfiguration.getCurrentConfig().stationSize / 2) % (2 * StellurgyConfiguration.getCurrentConfig().stationSize)) / StellurgyConfiguration.getCurrentConfig().stationSize);

            if (event.player.posX < -StellurgyConfiguration.getCurrentConfig().stationSize / 2.)
                if (result == 3)
                    result = 0;
                else if (result == 0)
                    result = 3;

            if (result == 0 || result == 3) {
                event.player.motionX = -event.player.motionX;
                if (result == 0) {
                    event.player.setPosition(event.player.posX + (event.player.posX < 0 ? Math.abs(event.player.posX % 16) : (16 - event.player.posX % 16)), event.player.posY, event.player.posZ);
                } else
                    event.player.setPosition(event.player.posX - (event.player.posX < 0 ? 16 - Math.abs(event.player.posX % 16) : (event.player.posX % 16)), event.player.posY, event.player.posZ);

            }
        }
    }

    /** Lands every station whose warp transit is due. Server only, once per server tick. */
    void tickTransitions() {
        if (DimensionManager.getWorld(StellurgyConfiguration.getCurrentConfig().spaceDimId) == null)
            return;

        long worldTime = DimensionManager.getWorld(StellurgyConfiguration.getCurrentConfig().spaceDimId).getTotalWorldTime();
        //Assuming server
        //If no dim undergoing transition then nextTransitionTick = -1
        if ((nextStationTransitionTick != -1 && worldTime >= nextStationTransitionTick && spaceStationOrbitMap.get(WARPDIMID) != null) || (nextStationTransitionTick == -1 && spaceStationOrbitMap.get(WARPDIMID) != null && !spaceStationOrbitMap.get(WARPDIMID).isEmpty())) {
            long newNextTransitionTick = -1;
            // Iterate a snapshot: moveStationToBody mutates the WARPDIMID orbit list
            // (removes the arriving station), so a live for-each over it throws
            // ConcurrentModificationException when two stations arrive on the same tick.
            for (ISpaceObject spaceObject : new ArrayList<>(spaceStationOrbitMap.get(WARPDIMID))) {
                if (spaceObject.getTransitionTime() <= Stellurgy.proxy.getWorldTimeUniversal(0)) {
                    moveStationToBody(spaceObject, spaceObject.getDestOrbitingBody());
                    spaceStationOrbitMap.get(WARPDIMID).remove(spaceObject);
                } else if (newNextTransitionTick == -1 || spaceObject.getTransitionTime() < newNextTransitionTick)
                    newNextTransitionTick = spaceObject.getTransitionTime();
            }

            nextStationTransitionTick = newNextTransitionTick;
        }

    }

	/*@SubscribeEvent
	public void onPlayerTransition(PlayerEvent.PlayerChangedDimensionEvent event) {
		
		if(event.toDim == Configuration.spaceDimId && getSpaceStationFromBlockCoords((int)event.player.posX, (int)event.player.posZ) != null &&
				temporaryDimensions.containsKey(getSpaceStationFromBlockCoords((int)event.player.posX, (int)event.player.posZ))) {
			int stationId = getSpaceStationFromBlockCoords((int)event.player.posX, (int)event.player.posZ).getId();
			
			temporaryDimensionPlayerNumber.put(stationId, temporaryDimensionPlayerNumber.get(stationId)+1);
		}
		if(event.fromDim != Configuration.spaceDimId) 
			return;
		
		ISpaceObject spaceObj = getSpaceStationFromBlockCoords((int)event.player.posX, (int)event.player.posZ);
		Long expireTime = spaceObj.getExpireTime();
		int numplayers;
		temporaryDimensionPlayerNumber.put(spaceObj.getId(), (numplayers = temporaryDimensionPlayerNumber.get(spaceObj.getId())-1));
		
		if(expireTime == null)
			return;
		
		long worldTime = DimensionManager.getWorld(event.toDim).getTotalWorldTime();

		if(expireTime >= worldTime && numplayers == 0) {
			//expired and delete
			unregisterSpaceObject(spaceObj.getId());
		}

	}*/

    public void moveStationToBody(@Nonnull ISpaceObject station, int dimId) {
        moveStationToBody(station, dimId, true);
    }

    /**
     * Changes the orbiting body of the space object
     *
     * @param station
     * @param dimId
     */
    public void moveStationToBody(@Nonnull ISpaceObject station, int dimId, boolean update) {
        //Remove station from the planet it's in orbit around before moving it!
        if (spaceStationOrbitMap.get(station.getOrbitingPlanetId()) != null) {
            spaceStationOrbitMap.get(station.getOrbitingPlanetId()).remove(station);
        }

        spaceStationOrbitMap.computeIfAbsent(dimId, k -> new LinkedList<>());

        if (!spaceStationOrbitMap.get(dimId).contains(station))
            spaceStationOrbitMap.get(dimId).add(station);
        station.setOrbitingBody(dimId);

        if (update) {
            //if(FMLCommonHandler.instance().getSide().isServer()) {
            PacketHandler.sendToAll(new PacketStationUpdate(station, PacketStationUpdate.Type.ORBIT_UPDATE));
            //}
            Stellurgy.proxy.fireFogBurst(station);
        }
    }

    /**
     * Changes the orbiting body of the space object
     *
     * @param station
     * @param dimId
     * @param timeDelta time in ticks to fully make the jump
     */
    public void moveStationToBody(@Nonnull ISpaceObject station, int dimId, int timeDelta) {
        //Remove station from the planet it's in orbit around before moving it!
        if (station.getOrbitingPlanetId() != WARPDIMID && spaceStationOrbitMap.get(station.getOrbitingPlanetId()) != null) {
            spaceStationOrbitMap.get(station.getOrbitingPlanetId()).remove(station);
        }

        spaceStationOrbitMap.computeIfAbsent(WARPDIMID, k -> new LinkedList<>());

        if (!spaceStationOrbitMap.get(WARPDIMID).contains(station))
            spaceStationOrbitMap.get(WARPDIMID).add(station);
        station.setOrbitingBody(WARPDIMID);

        //if(FMLCommonHandler.instance().getSide().isServer()) {
        PacketHandler.sendToAll(new PacketStationUpdate(station, PacketStationUpdate.Type.ORBIT_UPDATE));
        //}
        Stellurgy.proxy.fireFogBurst(station);


        ((DimensionProperties) station.getProperties()).realizeAtmosphere(false, 0);
        nextStationTransitionTick = (int) (StellurgyConfiguration.getCurrentConfig().travelTimeMultiplier * timeDelta) + Stellurgy.proxy.getWorldTimeUniversal(0);
        station.beginTransition(nextStationTransitionTick);

    }

    public void writeToNBT(NBTTagCompound nbt) {
        Iterator<ISpaceObject> iterator = stationLocations.values().iterator();
        NBTTagList nbtList = new NBTTagList();

        while (iterator.hasNext()) {
            ISpaceObject spaceObject = iterator.next();
            NBTTagCompound nbtTag = new NBTTagCompound();
            spaceObject.writeToNbt(nbtTag);

            nbtTag.setString("type", NAMES_BY_TYPE.get(spaceObject.getClass()));
            if (temporaryDimensions.containsKey(spaceObject.getId())) {
                nbtTag.setLong("expireTime", temporaryDimensions.get(spaceObject.getId()));
                nbtTag.setInteger("numPlayers", temporaryDimensionPlayerNumber.get(spaceObject.getId()));
            }

            nbtList.appendTag(nbtTag);
        }


        nbt.setTag("spaceContents", nbtList);
        nbt.setInteger("nextInt", nextId);
        nbt.setLong("nextStationTransitionTick", nextStationTransitionTick);
    }

    public void readFromNBT(NBTTagCompound nbt) {
        NBTTagList list = nbt.getTagList("spaceContents", NBT.TAG_COMPOUND);
        nextId = nbt.getInteger("nextInt");
        nextStationTransitionTick = nbt.getLong("nextStationTransitionTick");

        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            try {
                ISpaceObject spaceObject = (ISpaceObject) TYPES_BY_NAME.get(tag.getString("type")).newInstance();
                spaceObject.readFromNbt(tag);


                if (tag.hasKey("expireTime")) {
                    long expireTime = tag.getLong("expireTime");
                    int numPlayers = tag.getInteger("numPlayers");
                    if (DimensionManager.getWorld(StellurgyConfiguration.getCurrentConfig().spaceDimId).getTotalWorldTime() >= expireTime && numPlayers == 0)
                        continue;
                    temporaryDimensions.put(spaceObject.getId(), expireTime);
                    temporaryDimensionPlayerNumber.put(spaceObject.getId(), numPlayers);
                }

                registerSpaceObject(spaceObject, spaceObject.getOrbitingPlanetId(), spaceObject.getId());

            } catch (Exception e) {
                System.out.println(tag.getString("type"));
                e.printStackTrace();
            }
        }
    }
}
