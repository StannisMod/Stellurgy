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
    /**
     * What a data instrument draws per tick while it works. The basic build's figure kept as the
     * instrument's own: a 4-unit panel's instrument drew half the panel, 2.
     */
    public static final double INSTRUMENT_DRAW_PER_TICK = 2d;
    /**
     * Ticks of work per data point. The basic build's figure kept as the instrument's own: 200/√(0.1·4),
     * the rate a 4-unit panel's satellite collected at.
     */
    public static final double TICKS_PER_POINT = 316d;

    public DataStorage data;
    long lastActionTime, prevLastActionTime;
    World world;

    public SatelliteData() {
        super();
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
    }


    /** One data point per {@link #TICKS_PER_POINT} ticks of work, drawing {@link #INSTRUMENT_DRAW_PER_TICK}. */
    @Override
    public SatelliteAccrual.Payload payload() {
        return new SatelliteAccrual.Payload(true, false, TICKS_PER_POINT, INSTRUMENT_DRAW_PER_TICK);
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
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);

        NBTTagCompound data = new NBTTagCompound();
        this.data.writeToNBT(data);
        nbt.setTag("data", data);

        nbt.setLong("lastActionTime", lastActionTime);
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
