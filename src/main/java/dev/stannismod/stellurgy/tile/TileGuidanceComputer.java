package dev.stannismod.stellurgy.tile;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.util.Constants.NBT;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.api.SatelliteRegistry;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.api.stations.ISpaceObject;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.item.ItemAsteroidChip;
import dev.stannismod.stellurgy.item.ItemPlanetIdentificationChip;
import dev.stannismod.stellurgy.item.ItemSatelliteIdentificationChip;
import dev.stannismod.stellurgy.item.ItemStationChip;
import dev.stannismod.stellurgy.item.ItemStationChip.LandingLocation;
import dev.stannismod.stellurgy.network.PacketBackToRocketGui;
import dev.stannismod.stellurgy.stations.SpaceObjectManager;
import dev.stannismod.stellurgy.stations.SpaceStationObject;
import dev.stannismod.stellurgy.util.PlanetaryTravelHelper;
import dev.stannismod.stellurgy.util.RocketGuiNavigation;
import dev.stannismod.stellurgy.util.StationLandingLocation;
import dev.stannismod.stellurgy.libvulpes.api.LibVulpesItems;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IButtonInventory;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IModularInventory;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;
import dev.stannismod.stellurgy.libvulpes.items.ItemLinker;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.hatch.TileInventoryHatch;
import dev.stannismod.stellurgy.libvulpes.util.HashedBlockPosition;
import dev.stannismod.stellurgy.libvulpes.util.Vector3F;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TileGuidanceComputer extends TileInventoryHatch implements IModularInventory, IButtonInventory {

    private int destinationId;
    private Vector3F<Float> landingPos;
    private Map<Integer, HashedBlockPosition> landingLoc;

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
        return modules;
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
                return new Vector3F<>(landingBlock.getX() + 0.5f, (float) StellurgyConfiguration.getCurrentConfig().orbit, landingBlock.getZ() + 0.5f);
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
        int totalBurn = (currentDimensionID == StellurgyConfiguration.getCurrentConfig().spaceDimId) ? StellurgyConfiguration.getCurrentConfig().stationClearanceHeight : StellurgyConfiguration.getCurrentConfig().orbit;
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
