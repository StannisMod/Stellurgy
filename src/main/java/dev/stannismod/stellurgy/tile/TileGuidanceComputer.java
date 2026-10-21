package dev.stannismod.stellurgy.tile;

import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.api.SatelliteRegistry;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.api.stations.ISpaceObject;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.item.ItemAsteroidChip;
import dev.stannismod.stellurgy.item.ItemPlanetIdentificationChip;
import dev.stannismod.stellurgy.item.ItemSatelliteIdentificationChip;
import dev.stannismod.stellurgy.item.ItemStationChip;
import dev.stannismod.stellurgy.item.ItemStationChip.LandingLocation;
import dev.stannismod.stellurgy.libvulpes.api.LibVulpesItems;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IButtonInventory;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IGuiCallback;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IModularInventory;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleNumericTextbox;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleText;
import dev.stannismod.stellurgy.libvulpes.items.ItemLinker;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.network.PacketMachine;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.hatch.TileInventoryHatch;
import dev.stannismod.stellurgy.libvulpes.util.HashedBlockPosition;
import dev.stannismod.stellurgy.libvulpes.util.INetworkMachine;
import dev.stannismod.stellurgy.libvulpes.util.Vector3F;
import dev.stannismod.stellurgy.network.PacketBackToRocketGui;
import dev.stannismod.stellurgy.satellite.SatellitePlacement;
import dev.stannismod.stellurgy.space.SpaceSubsystem;
import dev.stannismod.stellurgy.universe.SystemBody;
import dev.stannismod.stellurgy.universe.UniverseRegistry;
import io.netty.buffer.ByteBuf;
import dev.stannismod.stellurgy.stations.SpaceObjectManager;
import dev.stannismod.stellurgy.stations.SpaceStationObject;
import dev.stannismod.stellurgy.util.PlanetaryTravelHelper;
import dev.stannismod.stellurgy.util.RocketGuiNavigation;
import dev.stannismod.stellurgy.util.StationLandingLocation;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.util.Constants.NBT;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

public class TileGuidanceComputer extends TileInventoryHatch implements IModularInventory, IButtonInventory,
        INetworkMachine, IGuiCallback {

    private static final byte PACKET_RELEASE_ALTITUDE = 20;
    private static final byte PACKET_RELEASE_INCLINATION = 21;
    private static final byte PACKET_RELEASE_STATUS = 22;
    private static final double METRES_PER_KM = 1000d;

    private int destinationId;
    private Vector3F<Float> landingPos;
    private Map<Integer, HashedBlockPosition> landingLoc;
    /** Whether a release altitude was set; unset, a satellite goes to its body's orbit line. */
    private boolean releaseAltitudeStated;
    /** The altitude, metres above the surface, a satellite released by this rocket is put at, when stated. */
    private double releaseAltitudeM;
    /** The inclination, degrees, of the orbit a satellite released by this rocket is put on; 0 is equatorial. */
    private double releaseInclinationDeg;
    /** What the server last said about the release orbit, for the open screen: the orbit line and the bounds, km. */
    private ReleaseStatus releaseStatus = ReleaseStatus.unknown();
    private ModuleNumericTextbox altitudeBox;
    private ModuleNumericTextbox inclinationBox;
    private ModuleText releaseStatusText;

    public TileGuidanceComputer() {
        super(1);
        inventory.setCanInsertSlot(0, true);
        inventory.setCanExtractSlot(0, true);
        landingPos = new Vector3F<>(0f, 0f, 0f);
        destinationId = Constants.INVALID_PLANET;
        landingLoc = new HashMap<>();
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> modules = super.getModules(ID, player);
        RocketGuiNavigation.addBackButtonIfApplicable(modules, player, this);

        if (world.isRemote) {
            modules.add(new ModuleText(30, 18, LibVulpes.proxy.getLocalizedString("msg.guidanceComputer.releaseAltitude"), 0x2a2a2a));
            altitudeBox = new ModuleNumericTextbox(this, 120, 16, 48, 12, 9);
            altitudeBox.setText(releaseAltitudeStated ? String.valueOf(Math.round(releaseAltitudeM / METRES_PER_KM)) : "");
            modules.add(altitudeBox);
            modules.add(new ModuleText(30, 34, LibVulpes.proxy.getLocalizedString("msg.guidanceComputer.releaseInclination"), 0x2a2a2a));
            inclinationBox = new ModuleNumericTextbox(this, 120, 32, 48, 12, 4);
            inclinationBox.setText(String.valueOf(Math.round(releaseInclinationDeg)));
            modules.add(inclinationBox);
            releaseStatusText = new ModuleText(8, 52, releaseStatusLine(), 0x2a2a2a);
            modules.add(releaseStatusText);
        } else {
            sendReleaseStatus(player);
        }
        return modules;
    }

    /**
     * The altitude, metres above the surface, a satellite this rocket releases is put at, when one was
     * set; empty means its body's orbit line.
     */
    public OptionalDouble statedReleaseAltitudeM() {
        return releaseAltitudeStated ? OptionalDouble.of(releaseAltitudeM) : OptionalDouble.empty();
    }

    /** The inclination of the orbit a satellite this rocket releases is put on, radians. */
    public double releaseInclinationRad() {
        return Math.toRadians(releaseInclinationDeg);
    }

    /** Set the release altitude, metres above the surface; empty returns it to the body's orbit line. */
    public void setReleaseAltitudeM(OptionalDouble altitudeM) {
        releaseAltitudeStated = altitudeM.isPresent();
        releaseAltitudeM = altitudeM.orElse(0d);
        markDirty();
    }

    /** Set the release inclination, degrees. */
    public void setReleaseInclinationDeg(double inclinationDeg) {
        releaseInclinationDeg = inclinationDeg;
        markDirty();
    }

    /**
     * What a release from here would get, as the server can state it: the altitudes the destination body
     * admits and its orbit line. Only the server knows the universe, so the open screen is told.
     */
    private ReleaseStatus computeReleaseStatus() {
        UniverseRegistry reg = UniverseRegistry.get(world);
        long tick = SpaceSubsystem.spaceClock();
        DimensionProperties props = DimensionManager.getEffectiveDimId_byID(
                getDestinationDimId(world.provider.getDimension(), pos), pos);
        if (props == null) {
            return ReleaseStatus.noOrbit();
        }
        Optional<SystemBody> body = SatellitePlacement.bodyOf(reg, props.getId());
        if (!body.isPresent()) {
            return ReleaseStatus.noOrbit();
        }
        SatellitePlacement.Answer<SatellitePlacement.AltitudeBounds> bounds =
                SatellitePlacement.boundsAbout(reg, body.get(), props, tick);
        if (!bounds.isPresent()) {
            return ReleaseStatus.noOrbit();
        }
        SatellitePlacement.AltitudeBounds b = bounds.get();
        return new ReleaseStatus(true, b.lowestM / METRES_PER_KM, b.highestM / METRES_PER_KM,
                b.orbitLineM / METRES_PER_KM);
    }

    private void sendReleaseStatus(EntityPlayer player) {
        releaseStatus = computeReleaseStatus();
        PacketHandler.sendToPlayer(new PacketMachine(this, PACKET_RELEASE_STATUS), player);
    }

    private String releaseStatusLine() {
        if (!releaseStatus.known) {
            return "";
        }
        if (!releaseStatus.orbitPossible) {
            return LibVulpes.proxy.getLocalizedString("msg.guidanceComputer.releaseNoOrbit");
        }
        double altitudeKm = releaseAltitudeStated ? releaseAltitudeM / METRES_PER_KM : releaseStatus.orbitLineKm;
        boolean reachable = altitudeKm >= releaseStatus.lowestKm && altitudeKm <= releaseStatus.highestKm;
        return String.format(Locale.ROOT, "%s %d–%d km",
                LibVulpes.proxy.getLocalizedString(reachable ? "msg.guidanceComputer.releaseReachable"
                        : "msg.guidanceComputer.releaseUnreachable"),
                Math.round(releaseStatus.lowestKm), Math.round(releaseStatus.highestKm));
    }

    @Override
    public void onModuleUpdated(ModuleBase module) {
        if (module == altitudeBox) {
            String text = altitudeBox.getText();
            setReleaseAltitudeM(parseNumber(text).isPresent()
                    ? OptionalDouble.of(parseNumber(text).getAsDouble() * METRES_PER_KM) : OptionalDouble.empty());
            PacketHandler.sendToServer(new PacketMachine(this, PACKET_RELEASE_ALTITUDE));
        } else if (module == inclinationBox) {
            setReleaseInclinationDeg(parseNumber(inclinationBox.getText()).orElse(0d));
            PacketHandler.sendToServer(new PacketMachine(this, PACKET_RELEASE_INCLINATION));
        }
        if (releaseStatusText != null) {
            releaseStatusText.setText(releaseStatusLine());
        }
    }

    /** A typed number, or empty for a field left blank or holding only a sign. */
    private static OptionalDouble parseNumber(String text) {
        try {
            return text.isEmpty() || "-".equals(text) ? OptionalDouble.empty() : OptionalDouble.of(Long.parseLong(text));
        } catch (NumberFormatException tooLong) {
            return OptionalDouble.empty();
        }
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        if (id == PACKET_RELEASE_ALTITUDE) {
            out.writeBoolean(releaseAltitudeStated);
            out.writeDouble(releaseAltitudeM);
        } else if (id == PACKET_RELEASE_INCLINATION) {
            out.writeDouble(releaseInclinationDeg);
        } else if (id == PACKET_RELEASE_STATUS) {
            out.writeBoolean(releaseAltitudeStated);
            out.writeDouble(releaseAltitudeM);
            out.writeDouble(releaseInclinationDeg);
            out.writeBoolean(releaseStatus.orbitPossible);
            out.writeDouble(releaseStatus.lowestKm);
            out.writeDouble(releaseStatus.highestKm);
            out.writeDouble(releaseStatus.orbitLineKm);
        }
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId, NBTTagCompound nbt) {
        if (packetId == PACKET_RELEASE_ALTITUDE) {
            nbt.setBoolean("stated", in.readBoolean());
            nbt.setDouble("altitude", in.readDouble());
        } else if (packetId == PACKET_RELEASE_INCLINATION) {
            nbt.setDouble("inclination", in.readDouble());
        } else if (packetId == PACKET_RELEASE_STATUS) {
            nbt.setBoolean("stated", in.readBoolean());
            nbt.setDouble("altitude", in.readDouble());
            nbt.setDouble("inclination", in.readDouble());
            nbt.setBoolean("possible", in.readBoolean());
            nbt.setDouble("lowest", in.readDouble());
            nbt.setDouble("highest", in.readDouble());
            nbt.setDouble("orbitLine", in.readDouble());
        }
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt) {
        if (side.isServer() && id == PACKET_RELEASE_ALTITUDE) {
            setReleaseAltitudeM(nbt.getBoolean("stated") ? OptionalDouble.of(nbt.getDouble("altitude")) : OptionalDouble.empty());
            sendReleaseStatus(player);
        } else if (side.isServer() && id == PACKET_RELEASE_INCLINATION) {
            setReleaseInclinationDeg(nbt.getDouble("inclination"));
        } else if (side.isClient() && id == PACKET_RELEASE_STATUS) {
            releaseAltitudeStated = nbt.getBoolean("stated");
            releaseAltitudeM = nbt.getDouble("altitude");
            releaseInclinationDeg = nbt.getDouble("inclination");
            releaseStatus = new ReleaseStatus(nbt.getBoolean("possible"), nbt.getDouble("lowest"),
                    nbt.getDouble("highest"), nbt.getDouble("orbitLine"));
            if (releaseStatusText != null) {
                releaseStatusText.setText(releaseStatusLine());
            }
        }
    }

    /** What the server said about a release orbit from here, in km; {@link #unknown()} until it has said. */
    private static final class ReleaseStatus {
        static ReleaseStatus unknown() {
            return new ReleaseStatus(false, false, 0d, 0d, 0d);
        }

        static ReleaseStatus noOrbit() {
            return new ReleaseStatus(true, false, 0d, 0d, 0d);
        }

        final boolean known;
        final boolean orbitPossible;
        final double lowestKm;
        final double highestKm;
        final double orbitLineKm;

        ReleaseStatus(boolean orbitPossible, double lowestKm, double highestKm, double orbitLineKm) {
            this(true, orbitPossible, lowestKm, highestKm, orbitLineKm);
        }

        private ReleaseStatus(boolean known, boolean orbitPossible, double lowestKm, double highestKm,
                              double orbitLineKm) {
            this.known = known;
            this.orbitPossible = orbitPossible;
            this.lowestKm = lowestKm;
            this.highestKm = highestKm;
            this.orbitLineKm = orbitLineKm;
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void onInventoryButtonPressed(int buttonId) {
        if (buttonId == RocketGuiNavigation.BUTTON_BACK_TO_ROCKET) {
            PacketHandler.sendToServer(new PacketBackToRocketGui(
                    this.world.provider.getDimension(),
                    this.pos
            ));
        }
    }

    @Override
    public int getInventoryStackLimit() {
        return 1;
    }

    @Override
    public boolean isItemValidForSlot(int slot, @Nonnull ItemStack itemStack) {
        Item item = itemStack.getItem();

        return slot == 0 &&
                (item instanceof ItemPlanetIdentificationChip ||
                        item instanceof ItemStationChip ||
                        item instanceof ItemAsteroidChip ||
                        item instanceof ItemSatelliteIdentificationChip ||
                        item == LibVulpesItems.itemLinker);
    }

    public void setLandingLocation(int stationId, StationLandingLocation loc) {
        if (loc == null)
            landingLoc.remove(stationId);
        else
            landingLoc.put(stationId, loc.getPos());
    }

    public StationLandingLocation getLandingLocation(int stationId) {

        //Due to the fact that stations are not guaranteed to be loaded on startup, we get a real reference now
        ISpaceObject spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStation(stationId);
        if (spaceObject == null) {
            landingLoc.remove(stationId);
            return null;
        }

        HashedBlockPosition myLoc = landingLoc.get(stationId);

        if (myLoc == null)
            return null;

        return ((SpaceStationObject) spaceObject).getPadAtLocation(myLoc);
    }

    public long getTargetSatellite() {
        ItemStack stack = getStackInSlot(0);
        if (!stack.isEmpty() && stack.getItem() instanceof ItemSatelliteIdentificationChip) {
            return SatelliteRegistry.getSatelliteId(stack);
        }
        return -1;
    }

    /**
     * Gets the dimension to travel to if applicable
     *
     * @return The dimension to travel to or Constants.INVALID_PLANET if not valid
     */
    public int getDestinationDimId(int currentDimension, BlockPos pos) {
        ItemStack stack = getStackInSlot(0);

        if (!stack.isEmpty()) {
            Item itemType = stack.getItem();
            if (itemType instanceof ItemPlanetIdentificationChip) {
                ItemPlanetIdentificationChip item = (ItemPlanetIdentificationChip) itemType;

                return item.getDimensionId(stack);
            } else if (itemType instanceof ItemStationChip) {
                if (StellurgyConfiguration.getCurrentConfig().spaceDimId == currentDimension) {
                    ISpaceObject spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(pos);
                    if (spaceObject != null) {
                        if (ItemStationChip.getUUID(stack) == spaceObject.getId())
                            return spaceObject.getOrbitingPlanetId();
                    } else
                        return Constants.INVALID_PLANET;
                }
                return StellurgyConfiguration.getCurrentConfig().spaceDimId;
            } else if (itemType instanceof ItemAsteroidChip) {
                destinationId = currentDimension;

                //Caution Side-Effect: Updates landingPos.
                landingPos = new Vector3F<>((float) pos.getX(), (float) pos.getY(), (float) pos.getZ());
                return currentDimension;
            } else if (itemType instanceof ItemSatelliteIdentificationChip) {
                long satelliteId = getTargetSatellite();
                if (satelliteId != -1) {
                    SatelliteBase sat = DimensionManager.getInstance().getSatellite(satelliteId);

                    if (sat != null)
                        return sat.getDimensionId();
                }
            } else if (stack.getItem() == LibVulpesItems.itemLinker && ItemLinker.getDimId(stack) != Constants.INVALID_PLANET) {
                //Use the destination the Linker in the Guidance computer directs.
                return ItemLinker.getDimId(stack);
            }

        }

        //Almost always the Override setting (Linker in Docking Pad)
        return destinationId;
    }

    /**
     * returns the location the rocket should land
     *
     * @return
     */
    public Vector3F<Float> getLandingLocation(int landingDimension, boolean commit) {
        //Caution Side-Effect dependency: May require a call to getDestinationDimId to populate correct coordinates.
        ItemStack stack = getStackInSlot(0);
        //TODO: replace all nulls with current coordinates of the ship.
        //Make the if tree match the destination if tree:
        if (!stack.isEmpty()) {
            Item itemType = stack.getItem();
            if (itemType instanceof ItemPlanetIdentificationChip) {
                //This could be the location of the rocket.
                return null;
            } else if (itemType instanceof ItemStationChip) {
                ItemStationChip chip = (ItemStationChip) stack.getItem();
                if (landingDimension == StellurgyConfiguration.getCurrentConfig().spaceDimId) {
                    //TODO: handle Exception
                    ISpaceObject spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStation(ItemStationChip.getUUID(stack));
                    return getStationLocation(spaceObject, commit);
                } else {
                    LandingLocation loc = chip.getTakeoffCoords(stack, landingDimension);
                    if (loc != null) {
                        return loc.location;
                    }
                    return null;
                }
            } else if (itemType instanceof ItemAsteroidChip) {
                //Caution Side-Effect dependency: landingPos from getDim.
                return landingPos;
            } else if (itemType instanceof ItemSatelliteIdentificationChip) {
                //You can't actually go to the satellites.
                return null;
            } else if (stack.getItem() == LibVulpesItems.itemLinker && ItemLinker.getDimId(stack) != Constants.INVALID_PLANET) {
                //Use the destination the Linker in the Guidance computer directs.
                BlockPos landingBlock = ItemLinker.getMasterCoords(stack);
                // The rocket's own arrival rule, which announces a world with no line.
                float arrivalY = DimensionManager.getInstance().transferLineOf(ItemLinker.getDimId(stack))
                        .orElse(dev.stannismod.stellurgy.space.TerrainHeightFinder.MAX_BUILD_Y);
                return new Vector3F<>(landingBlock.getX() + 0.5f, arrivalY, landingBlock.getZ() + 0.5f);
            }

        } else if (destinationId != Constants.INVALID_PLANET) {
            //Use the override coordinates from a Linker in a Docking Pad.
            return landingPos;
        }

        //We got nothing.
        return null;
    }

    private Vector3F<Float> getStationLocation(ISpaceObject spaceObject, boolean commit) {
        HashedBlockPosition vec = null;
        if (spaceObject instanceof SpaceStationObject) {
            if (landingLoc.get(spaceObject.getId()) != null) {
                vec = landingLoc.get(spaceObject.getId());

                if (commit)
                    ((SpaceStationObject) spaceObject).getPadAtLocation(landingLoc.get(spaceObject.getId())).setOccupied(true);
            } else
                vec = spaceObject.getNextLandingPad(commit);
        }

        if (spaceObject == null)
            return null;

        if (vec == null)
            vec = spaceObject.getSpawnLocation();

        return new Vector3F<>((float) vec.x, (float) vec.y, (float) vec.z);
    }

    public void overrideLandingStation(ISpaceObject spaceObject) {
        setFallbackDestination(StellurgyConfiguration.getCurrentConfig().spaceDimId, getStationLocation(spaceObject, true));
    }

    public String getDestinationName(int landingDimension) {
        ItemStack stack = getStackInSlot(0);
        if (!stack.isEmpty() && stack.getItem() instanceof ItemStationChip) {
            ItemStationChip chip = (ItemStationChip) stack.getItem();
            if (landingDimension != StellurgyConfiguration.getCurrentConfig().spaceDimId) {
                LandingLocation loc = chip.getTakeoffCoords(stack, landingDimension);
                if (loc != null) {
                    return loc.name;
                }
            }
        }
        return "";
    }

    public void setFallbackDestination(int dimID, Vector3F<Float> coords) {
        this.destinationId = dimID;
        this.landingPos = coords;
    }

    public int getLaunchSequence(int currentDimensionID, BlockPos currentPosition) {
        // A launch from a world with no line is refused before this is asked (EntityRocket#launch).
        int totalBurn = DimensionManager.getInstance().transferLineOf(currentDimensionID).orElseThrow(
                () -> new IllegalStateException("no orbit line in dim " + currentDimensionID
                        + ": a launch from it should have been refused"));
        int destinationDimensionID = getDestinationDimId(currentDimensionID, currentPosition);

        totalBurn += (currentDimensionID == StellurgyConfiguration.getCurrentConfig().spaceDimId) ? getTransBodyInjection(currentDimensionID, destinationDimensionID, currentPosition) : getTransBodyInjection(currentDimensionID, destinationDimensionID);
        return totalBurn;
    }

    public int getTransBodyInjection(int currentDimensionID, int destinationDimensionID) {
        ISpaceObject destinationSpaceStation = SpaceObjectManager.getSpaceManager().getSpaceStation(ItemStationChip.getUUID(getStackInSlot(0)));
        destinationDimensionID = ((destinationDimensionID == StellurgyConfiguration.getCurrentConfig().spaceDimId) && (destinationSpaceStation != null)) ? destinationSpaceStation.getOrbitingPlanetId() : destinationDimensionID;

        if (destinationDimensionID == Constants.INVALID_PLANET) {
            return 0;
        }
        return (PlanetaryTravelHelper.isTravelWithinOrbit(currentDimensionID, destinationDimensionID) && !isAsteroidMission()) ? 0 : PlanetaryTravelHelper.getTransbodyInjectionBurn(currentDimensionID, destinationDimensionID, isAsteroidMission());
    }

    public int getTransBodyInjection(int currentDimensionID, int destinationDimensionID, BlockPos currentPosition) {
        ISpaceObject currentSpaceStation = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(currentPosition);
        ISpaceObject destinationSpaceStation = SpaceObjectManager.getSpaceManager().getSpaceStation(ItemStationChip.getUUID(getStackInSlot(0)));
        destinationDimensionID = ((destinationDimensionID == StellurgyConfiguration.getCurrentConfig().spaceDimId) && (destinationSpaceStation != null)) ? destinationSpaceStation.getOrbitingPlanetId() : destinationDimensionID;

        // No origin station at this position (e.g. launching from the empty grid cell
        // past the station perimeter wall) means there is no orbiting body to inject
        // from — degrade to no trans-body burn instead of dereferencing a null station.
        if (destinationDimensionID == Constants.INVALID_PLANET || currentSpaceStation == null) {
            return 0;
        }
        return (PlanetaryTravelHelper.isTravelWithinOrbit(currentSpaceStation.getOrbitingPlanetId(), destinationDimensionID) && !isAsteroidMission()) ? 0 : PlanetaryTravelHelper.getTransbodyInjectionBurn(currentSpaceStation.getOrbitingPlanetId(), destinationDimensionID, isAsteroidMission());
    }

    public boolean isAsteroidMission() {
        return getStackInSlot(0).getItem() instanceof ItemAsteroidChip;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setInteger("destDimId", destinationId);

        nbt.setFloat("landingx", landingPos.x);
        nbt.setFloat("landingy", landingPos.y);
        nbt.setFloat("landingz", landingPos.z);

        NBTTagList stationList = new NBTTagList();

        for (int locationID : landingLoc.keySet()) {
            NBTTagCompound tag = new NBTTagCompound();
            HashedBlockPosition loc = landingLoc.get(locationID);

            tag.setIntArray("pos", new int[]{loc.x, loc.y, loc.z});
            tag.setInteger("id", locationID);
            stationList.appendTag(tag);
        }
        nbt.setTag("stationMapping", stationList);
        if (releaseAltitudeStated) {
            nbt.setDouble("releaseAltitude", releaseAltitudeM);
        }
        nbt.setDouble("releaseInclination", releaseInclinationDeg);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        destinationId = nbt.getInteger("destDimId");

        landingPos.x = nbt.getFloat("landingx");
        landingPos.y = nbt.getFloat("landingy");
        landingPos.z = nbt.getFloat("landingz");

        NBTTagList stationList = nbt.getTagList("stationMapping", NBT.TAG_COMPOUND);

        for (int i = 0; i < stationList.tagCount(); i++) {
            NBTTagCompound tag = stationList.getCompoundTagAt(i);
            int[] pos;
            pos = tag.getIntArray("pos");
            int id = tag.getInteger("id");
            landingLoc.put(id, new HashedBlockPosition(pos[0], pos[1], pos[2]));
        }
        releaseAltitudeStated = nbt.hasKey("releaseAltitude");
        releaseAltitudeM = nbt.getDouble("releaseAltitude");
        releaseInclinationDeg = nbt.getDouble("releaseInclination");
    }

    @Override
    public void setInventorySlotContents(int slot, @Nonnull ItemStack stack) {
        super.setInventorySlotContents(slot, stack);

        //If the item in the slot is modified then reset dimid
        if (!stack.isEmpty())
            destinationId = Constants.INVALID_PLANET;
    }

    public void setReturnPosition(Vector3F<Float> pos, int dimId) {
        ItemStack stack = getStackInSlot(0);

        if (!stack.isEmpty() && stack.getItem() instanceof ItemStationChip) {
            ItemStationChip item = (ItemStationChip) stack.getItem();
            item.setTakeoffCoords(stack, pos, dimId, 0);
        }
    }

    @Override
    public String getModularInventoryName() {
        return StellurgyBlocks.blockGuidanceComputer.getLocalizedName();
    }
}
