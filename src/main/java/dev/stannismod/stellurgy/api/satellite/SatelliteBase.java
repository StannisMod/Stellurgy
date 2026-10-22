package dev.stannismod.stellurgy.api.satellite;

import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.api.ISatelliteIdItem;
import dev.stannismod.stellurgy.api.SatelliteRegistry;
import dev.stannismod.stellurgy.api.StellurgyItems;
import dev.stannismod.stellurgy.libvulpes.util.UniversalBattery;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;

import javax.annotation.Nonnull;

public abstract class SatelliteBase {

    protected SatelliteProperties satelliteProperties;
    //Will always be of type ItemSatellite
    protected ItemStack satellite;
    //Satellite energy storage
    protected UniversalBattery battery;
    private int dimId = Constants.INVALID_PLANET;
    private boolean isDead;
    /** The orbit it was released onto, or {@code null} while it has no place: not yet released, or a mission. */
    private SatelliteOrbit orbit;
    private final dev.stannismod.stellurgy.satellite.SatelliteLife.Progress life = new dev.stannismod.stellurgy.satellite.SatelliteLife.Progress();

    public SatelliteBase() {
        satelliteProperties = new SatelliteProperties();
        satelliteProperties.setSatelliteType(SatelliteRegistry.getKey(this.getClass()));
        isDead = false;
        satellite = ItemStack.EMPTY;

        //Satellite energy storage
        battery = new UniversalBattery(this.satelliteProperties.getPowerStorage());
    }

    public boolean acceptsItemInConstruction(@Nonnull ItemStack item) {
        int flag = SatelliteRegistry.getSatelliteProperty(item).getPropertyFlag();
        return SatelliteProperties.Property.MAIN.isOfType(flag) || SatelliteProperties.Property.POWER_GEN.isOfType(flag)
                || SatelliteProperties.Property.BATTERY.isOfType(flag) || SatelliteProperties.Property.SHIELD.isOfType(flag);
    }

    /**
     * Gets status info eg "Moving into position" or "Ready" or "68% damaged"
     *
     * @return Human-readable Information about the satellite (supports \n for newline)
     */
    public abstract String getInfo(World world);

    /**
     * Gets the human-readable display name of the satellite
     *
     * @return display name of the satellite
     */
    public abstract String getName();

    /**
     * Actually does something with the satellite.  Normally called when the player rightclicks the master block
     *
     * @param player interacting with the satellite
     * @param world
     * @param pos
     * @return whether the player has successfully interacted with the satellite
     */
    public abstract boolean performAction(EntityPlayer player, World world, BlockPos pos);

    /**
     * What this satellite's primary function looks at, which decides the way its instruments face. A
     * device that acts on the world it orbits — every function other than a survey of space — looks at
     * the body; a type that surveys space says so. Empty for a function that observes nothing.
     */
    public java.util.Optional<InstrumentSubject> instrumentSubject() {
        return java.util.Optional.of(InstrumentSubject.BODY);
    }

    /** The orbit this satellite was released onto; empty while it has no place on one. */
    public java.util.Optional<SatelliteOrbit> getOrbit() {
        return java.util.Optional.ofNullable(orbit);
    }

    /** Put this satellite on {@code orbit}: done once, at release. */
    public void setOrbit(@Nonnull SatelliteOrbit orbit) {
        this.orbit = orbit;
    }

    /**
     * @return the power per tick the satellite produces
     */
    public int getPowerPerTick() {
        return satelliteProperties.getPowerGeneration();
    }

    /**
     * @return an item that can be used to control the satellite, normally a satellite ID chip but can be something else
     */
    @Nonnull
    public ItemStack getControllerItemStack(@Nonnull ItemStack satIdChip, SatelliteProperties properties) {
        ISatelliteIdItem idChipItem = (ISatelliteIdItem) satIdChip.getItem();
        idChipItem.setSatellite(satIdChip, properties);
        return satIdChip;
    }

    /**
     * @param stack stack to check (can be null)
     * @return true if the item stack is a valid controller for the satellite
     */
    public boolean isAcceptableControllerItemStack(@Nonnull ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == StellurgyItems.itemSatelliteIdChip;
    }

    /**
     * Whether this satellite must be ticked: only a type that acts on its world every tick. Power, data and
     * wear are not ticked — they are brought up to date when asked for ({@link #bringUpToNow()}).
     */
    public boolean canTick() {
        return false;
    }

    /** Called every tick for a type that {@link #canTick() acts on its world}; brings its life up to date first. */
    public void tickEntity() {
        bringUpToNow();
    }

    /**
     * Bring this satellite's power, data, wear and journal up to the space clock's present. Server only;
     * call it before reading or spending any of them.
     */
    public void bringUpToNow() {
        dev.stannismod.stellurgy.satellite.SatelliteLife.bringUpTo(this, dev.stannismod.stellurgy.space.SpaceSubsystem.spaceClock());
    }

    /** How far this satellite's life has been brought, and its failure journal. */
    public dev.stannismod.stellurgy.satellite.SatelliteLife.Progress lifeProgress() {
        return life;
    }

    /** What its primary function does with power: by default nothing that collects or reads. */
    public dev.stannismod.stellurgy.satellite.SatelliteAccrual.Payload payload() {
        return new dev.stannismod.stellurgy.satellite.SatelliteAccrual.Payload(false, false, 0d, 0d);
    }

    /** Its dosimeter's readings; {@code null} for a satellite that carries none. */
    public dev.stannismod.stellurgy.satellite.DoseReadings doseReadings() {
        return null;
    }

    /** Data points held in its buffer; zero for a type with none. */
    public long bufferedData() {
        return 0L;
    }

    /** Set the data points held in its buffer; ignored by a type with none. */
    public void setBufferedData(long points) {
    }

    public int storedEnergy() {
        return battery.getUniversalEnergyStored();
    }

    public int energyCapacity() {
        return battery.getMaxEnergyStored();
    }

    public void setStoredEnergy(int energy) {
        battery.setEnergyStored(energy);
    }

    /**
     * The power its panels give in full light now, per tick: what they were built for, faded by the dose
     * they have taken. Lower than {@link #getPowerPerTick()} once the field has worn them.
     */
    public double currentPowerPerTick() {
        dev.stannismod.stellurgy.satellite.SatelliteAccrual.Rules rules = dev.stannismod.stellurgy.satellite.SatelliteLife.rules();
        double sum = 0d;
        for (dev.stannismod.stellurgy.satellite.SatelliteAccrual.Part part : dev.stannismod.stellurgy.satellite.SatelliteLife.partsOf(this)) {
            sum += part.powerPerTick(rules);
        }
        return sum;
    }

    /**
     * @return the long id of the satellite, used to get a satellite from the main list
     */
    public long getId() {
        return satelliteProperties.getId();
    }

    public void setDead() {
        isDead = true;
    }

    public boolean isDead() {
        return isDead;
    }

    /**
     * @return the satellite properties of the satellite
     */
    public SatelliteProperties getProperties() {
        return satelliteProperties;
    }

    /**
     * @param stack satelliteProperties to assign to this satellite
     */
    public void setProperties(@Nonnull ItemStack stack) {
        this.satelliteProperties = SatelliteRegistry.getSatelliteProperties(stack);
        this.battery.setMaxEnergyStored(satelliteProperties.getPowerStorage());
        this.satellite = stack;
    }

    @Nonnull
    public ItemStack getItemStackFromSatellite() {
        return satellite;
    }

    /**
     * @return dimensionID of the satellite, Constants.INVALID_PLANET if none
     */
    public int getDimensionId() {
        return dimId;
    }

    /**
     * Does not currently support dimension change
     *
     * @param world World of which to assign to the satellite
     */
    public void setDimensionId(World world) {
        int newId = world.provider.getDimension();
        if (dimId != Constants.INVALID_PLANET) {
            //TODO: handle dim change
        }
        dimId = newId;
    }

    public void setDimensionId(int world) {
        if (dimId != Constants.INVALID_PLANET) {
            //TODO: handle dim change
        }
        dimId = world;
    }

    /**
     * @param nbt NBT data to store
     */
    public void writeToNBT(NBTTagCompound nbt) {
        nbt.setString("dataType", SatelliteRegistry.getKey(this.getClass()));

        NBTTagCompound properties = new NBTTagCompound();
        satelliteProperties.writeToNBT(properties);
        nbt.setTag("properties", properties);
        nbt.setInteger("dimId", dimId);
        battery.writeToNBT(nbt);

        NBTTagCompound itemNBT = new NBTTagCompound();
        //Transition
        if (!satellite.isEmpty())
            satellite.writeToNBT(itemNBT);
        nbt.setTag("item", itemNBT);

        if (orbit != null) {
            NBTTagCompound orbitNBT = new NBTTagCompound();
            orbit.writeToNBT(orbitNBT);
            nbt.setTag("orbit", orbitNBT);
        }
        NBTTagCompound lifeNBT = new NBTTagCompound();
        life.writeToNBT(lifeNBT);
        nbt.setTag("life", lifeNBT);
    }

    public void readFromNBT(NBTTagCompound nbt) {
        satelliteProperties.readFromNBT(nbt.getCompoundTag("properties"));
        dimId = nbt.getInteger("dimId");
        satellite = new ItemStack(nbt.getCompoundTag("item"));
        battery.readFromNBT(nbt);
        orbit = nbt.hasKey("orbit") ? SatelliteOrbit.readFromNBT(nbt.getCompoundTag("orbit")) : null;
        life.readFromNBT(nbt.getCompoundTag("life"));
        if (satelliteProperties.getPowerStorage() == 0) {
            satelliteProperties.setPowerStorage(720);
            battery = new UniversalBattery(720);
        }
    }

    public void writeDataToNetwork(ByteBuf out, byte packetId) {

    }

    public void readDataToNetwork(byte packetId, ByteBuf in) {

    }

    public void useNetworkData(EntityPlayer player, Side client, byte packetId,
                               NBTTagCompound nbt) {

    }

    //Server Syncing stuff
    //Used if the satellite needs to sync in a modularGUI

    public int numberChangesToSend() {
        return 0;
    }

    public void onChangeReceived(int slot, int value) {

    }

    public boolean isUpdateRequired(int localId) {
        return false;
    }

    public void sendChanges(Container container, IContainerListener crafter, int variableId, int localId) {

    }


}
