package dev.stannismod.stellurgy.satellite;

import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.DataStorage;
import dev.stannismod.stellurgy.api.SatelliteRegistry;
import dev.stannismod.stellurgy.api.satellite.IDataHandler;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.api.satellite.SatelliteProperties;
import dev.stannismod.stellurgy.libvulpes.util.ZUtils;
import dev.stannismod.stellurgy.util.IDataInventory;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nonnull;

public abstract class SatelliteData extends SatelliteBase {
    public DataStorage data;
    long lastActionTime, prevLastActionTime;
    int collectionTime;
    int powerConsumption;
    World world;

    public SatelliteData() {
        super();
        //powerConsumption = Math.min(160, getPowerPerTick());
        powerConsumption = getPowerPerTick();
        collectionTime = (int) (200 / Math.sqrt(0.1 * powerConsumption));

    }

    @Override
    public String getInfo(World world) {
        //tiles dont update unless ppl reopen
        bringUpToNow();
        return "Power: " + battery.getUniversalEnergyStored() + "/" + battery.getMaxEnergyStored()
                + "\nGeneration: " + ZUtils.formatNumber((int) Math.round(currentPowerPerTick())) + "/" + getPowerPerTick()
                + "\nData Storage: " + ZUtils.formatNumber(data.getMaxData()) +
                "\nData: " + ZUtils.formatNumber(data.getData());
    }

    /** Decided by the quantity it collects: a survey of distances looks out, every other quantity at the body. */
    @Override
    public java.util.Optional<dev.stannismod.stellurgy.api.satellite.InstrumentSubject> instrumentSubject() {
        return data.getDataType().subject();
    }

    @Override
    public boolean acceptsItemInConstruction(@Nonnull ItemStack item) {
        int flag = SatelliteRegistry.getSatelliteProperty(item).getPropertyFlag();

        return super.acceptsItemInConstruction(item) || SatelliteProperties.Property.DATA.isOfType(flag);
    }

    @Override
    public void setProperties(@Nonnull ItemStack satelliteProperties) {
        super.setProperties(satelliteProperties);
        data.setMaxData(this.satelliteProperties.getMaxDataStorage());
        powerConsumption = getPowerPerTick();
        collectionTime = (int) (200 / Math.sqrt(0.1 * powerConsumption));
    }


    /** Collects one data point per {@link #collectionTime} ticks of full power; what power it lacks, it does not collect. */
    @Override
    public SatelliteAccrual.Payload payload() {
        return new SatelliteAccrual.Payload(true, false, collectionTime);
    }

    @Override
    public long bufferedData() {
        return data.getData();
    }

    @Override
    public void setBufferedData(long points) {
        int now = data.getData();
        if (points < now) {
            data.removeData((int) (now - points), true);
        } else if (points > now) {
            data.addData((int) (points - now), data.getDataType(), true);
        }
    }

    @Override
    public boolean performAction(EntityPlayer player, World world, BlockPos pos) {
        bringUpToNow();
        //Grab the tile to sync with
        TileEntity tile = world.getTileEntity(pos);
        //Remove data from the satellite and add it to the buffer of said tile, provided the tile can accept it
        if (tile instanceof IDataHandler) {
            IDataInventory dataInv = (IDataInventory) tile;

            data.removeData(dataInv.addData(data.getData(), data.getDataType(), EnumFacing.DOWN, true), true);
        }

        return false;
    }

    @Override
    public void setDimensionId(World world) {
        //TODO: send packet on orbit reached
        super.setDimensionId(world);
        this.world = world;

        lastActionTime = world.getTotalWorldTime();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);

        this.data.readFromNBT(nbt.getCompoundTag("data"));
        lastActionTime = nbt.getLong("lastActionTime");
        collectionTime = nbt.getInteger("collectionMultiplier");


        powerConsumption = getPowerPerTick();
        collectionTime = (int) (200 / Math.sqrt(0.1 * powerConsumption));
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);

        NBTTagCompound data = new NBTTagCompound();
        this.data.writeToNBT(data);
        nbt.setTag("data", data);

        nbt.setLong("lastActionTime", lastActionTime);
        nbt.setInteger("collectionMultiplier", collectionTime);
    }

    @Override
    public int numberChangesToSend() {
        return 4;
    }

    @Override
    public void onChangeReceived(int slot, int value) {
        lastActionTime = (lastActionTime & (~(0xffffL << (slot * 16)))) | ((long) value << (slot * 16));
    }

    @Override
    public void sendChanges(Container container, IContainerListener crafter, int variableId, int localId) {
        crafter.sendWindowProperty(container, variableId, (short) ((lastActionTime >>> (localId * 16)) & 0xffff));

        if (localId == 3)
            prevLastActionTime = lastActionTime;
    }

    @Override
    public boolean isUpdateRequired(int localId) {
        return lastActionTime != prevLastActionTime;
    }
}
