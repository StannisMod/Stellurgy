package dev.stannismod.stellurgy.tile.multiblock;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.commons.lang3.ArrayUtils;
import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.inventory.modules.ModulePlanetSelector;
import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;
import dev.stannismod.stellurgy.util.ITilePlanetSystemSelectable;
import dev.stannismod.stellurgy.libvulpes.inventory.TextureResources;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IModularInventory;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IProgressBar;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ISelectionNotify;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.network.PacketMachine;
import dev.stannismod.stellurgy.libvulpes.tile.TilePointer;
import dev.stannismod.stellurgy.libvulpes.util.INetworkMachine;

import java.util.LinkedList;
import java.util.List;
import dev.stannismod.stellurgy.libvulpes.util.MachineReach;

public class TilePlanetSelector extends TilePointer implements ISelectionNotify, IModularInventory, IProgressBar, INetworkMachine {

    public static final int certaintyDataValue = 5000;
    /**
     * The distance gauge's reading per AU, in hundredths of the bar. It is the raw unit over 16 as it
     * was drawn while a distance unit was a hundredth of an AU, so Earth reads 6; read raw after the
     * unit became a length of 100 km, Earth read 93 498 and the gauge meant nothing.
     */
    private static final double DISTANCE_GAUGE_PER_AU = 100d / 16;
    /**
     * The gauge's reading per moon-view unit, for a moon: the raw unit over 16 as it was drawn while
     * Luna stood at 150, so Luna reads 9 as she did then (see
     * {@link AstronomicalBodyHelper#MOON_VIEW_UNITS_AT_LUNA}). Read through the planet law, a moon
     * reads 0.
     */
    private static final double DISTANCE_GAUGE_PER_MOON_VIEW_UNIT = 1d / 16;
    /**
     * What a planet selector's distance gauge reads for a body, in hundredths of the bar — a planet's
     * distance from its star, a moon's from its planet. The rocket's own planet selector draws the
     * same gauge, so both read it here.
     */
    public static int distanceGauge(DimensionProperties body) {
        if (body.isMoon()) {
            return (int) (AstronomicalBodyHelper.moonViewUnits(body.orbitalDist) * DISTANCE_GAUGE_PER_MOON_VIEW_UNIT);
        }
        return (int) (body.orbitalDist / (double) AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU
                * DISTANCE_GAUGE_PER_AU);
    }
    protected ModulePlanetSelector container;
    DimensionProperties dimCache;

    int[] cachedProgressValues;

    public TilePlanetSelector() {
        cachedProgressValues = new int[]{-1, -1, -1};
    }

    @Override
    public void onSelectionConfirmed(Object sender) {

        //Container Cannot be null at this time
        TileEntity tile = getMasterBlock();
        if (tile instanceof ITilePlanetSystemSelectable) {
            ((ITilePlanetSystemSelectable) tile).setSelectedPlanetId(container.getSelectedSystem());
        }
        onSelected(sender);
    }

    @Override
    public void onSelected(Object sender) {

        selectSystem(container.getSelectedSystem());

        PacketHandler.sendToServer(new PacketMachine(this, (byte) 0));
    }

    private void selectSystem(int id) {
        if (id == Constants.INVALID_PLANET)
            dimCache = null;
        else
            dimCache = DimensionManager.getInstance().getDimensionProperties(container.getSelectedSystem());
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {

        List<ModuleBase> modules = new LinkedList<>();

        DimensionProperties props = DimensionManager.getEffectiveDimId(player.world, player.getPosition());
        container = new ModulePlanetSelector((props != null ? props.getStarId() : 0), TextureResources.starryBG, this, true);
        container.setOffset(1000, 1000);
        modules.add(container);

        //Transfer discovery values
        if (!world.isRemote) {
            markDirty();
        }

        return modules;
    }

    @Override
    public String getModularInventoryName() {
        return "";
    }

    @Override
    public boolean canBeUsedBy(EntityPlayer player) {
        return MachineReach.reaches(player, this);
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return canBeUsedBy(entity);
    }

    @Override
    public float getNormallizedProgress(int id) {
        return 0;
    }

    @Override
    public void setProgress(int id, int progress) {
        cachedProgressValues[id] = progress;
    }

    @Override
    public int getProgress(int id) {

        if (!world.isRemote) {
            return 25; /*
			if(getMasterBlock() != null) {

				ItemStack stack = ((ITilePlanetSystemSelectable)getMasterBlock()).getChipWithId(container.getSelectedSystem());

				if(!stack.isEmpty()) {

					DataType data;
					if(id == 0)
						data = DataType.ATMOSPHEREDENSITY;
					else if(id == 1)
						data = DataType.DISTANCE;
					else //if(id == 2)
						data = DataType.MASS;


					int dataAmt = ((ItemPlanetIdentificationChip)stack.getItem()).getData(stack, data);

					if(dataAmt != 0)
						return (int)(certaintyDataValue/(float)dataAmt);
				}
			}*/
        } else {
            return cachedProgressValues[id];
        }

        //return 400;
    }

    @Override
    public int getTotalProgress(int id) {
        if (dimCache == null)
            return 50;
        if (id == 0)
            return dimCache.getAtmosphereDensity() / 16;
        else if (id == 1)
            return distanceGauge(dimCache);
        else //if(id == 2)
            return (int) (dimCache.gravitationalMultiplier * 50);
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        NBTTagCompound comp = new NBTTagCompound();

        writeToNBTHelper(comp);
        writeAdditionalNBT(comp);
        return new SPacketUpdateTileEntity(pos, 0, comp);
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {

        super.onDataPacket(net, pkt);
        readAdditionalNBT(pkt.getNbtCompound());
    }

    public void writeAdditionalNBT(NBTTagCompound nbt) {
        if (getMasterBlock() != null) {
            List<Integer> list = ((ITilePlanetSystemSelectable) getMasterBlock()).getVisiblePlanets();

            Integer[] intList = new Integer[list.size()];

            nbt.setIntArray("visiblePlanets", ArrayUtils.toPrimitive(list.toArray(intList)));
        }

    }

    public void readAdditionalNBT(NBTTagCompound nbt) {
        if (container != null) {
            int[] intArray = nbt.getIntArray("visiblePlanets");
            for (int id : intArray)
                container.setPlanetAsKnown(id);
        }
    }

    @Override
    public void setTotalProgress(int id, int progress) {

    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        if (id == 0)
            out.writeInt(container.getSelectedSystem());
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId,
                                    NBTTagCompound nbt) {
        if (packetId == 0)
            nbt.setInteger("id", in.readInt());
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id,
                               NBTTagCompound nbt) {
        if (id == 0) {
            int dimId = nbt.getInteger("id");
            container.setSelectedSystem(dimId);
            selectSystem(dimId);

            //Update known planets
            markDirty();
        }
    }

    @Override
    public void onSystemFocusChanged(Object sender) {
        PacketHandler.sendToServer(new PacketMachine(this, (byte) 0));
    }
}
