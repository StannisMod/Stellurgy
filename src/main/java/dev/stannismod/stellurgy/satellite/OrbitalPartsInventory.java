package dev.stannismod.stellurgy.satellite;

import dev.stannismod.stellurgy.api.SatelliteRegistry;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.api.satellite.SatelliteProperties;
import dev.stannismod.stellurgy.item.ItemSatellite;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.util.EmbeddedInventory;
import dev.stannismod.stellurgy.network.PacketSatellite;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

import javax.annotation.Nonnull;

/**
 * A satellite's parts as an inventory, for a repair in orbit: the seven slots of the builder's panel,
 * read from and written back to the satellite's own item. The primary function (slot 0) may be taken out
 * and replaced only by a primary function of the satellite's own type — another type would turn it into
 * another satellite with another controller — so a primary function the dose has stopped can be renewed.
 * Every change re-sums the satellite's properties the way assembly does, and the satellite keeps its id.
 */
public final class OrbitalPartsInventory implements IInventory {

    private static final int SIZE = 7;
    private static final int PRIMARY_SLOT = 0;

    private final SatelliteBase satellite;
    private final boolean server;

    /** @throws IllegalArgumentException when the satellite was not built on a chassis: it has no parts to show */
    public OrbitalPartsInventory(SatelliteBase satellite, boolean server) {
        if (!(satellite.getItemStackFromSatellite().getItem() instanceof ItemSatellite)) {
            throw new IllegalArgumentException("satellite " + satellite.getId() + " has no chassis");
        }
        this.satellite = satellite;
        this.server = server;
    }

    private ItemStack chassis() {
        return satellite.getItemStackFromSatellite();
    }

    private EmbeddedInventory parts() {
        return ((ItemSatellite) chassis().getItem()).readInvFromNBT(chassis());
    }

    /** The parts about to change: the time already passed is taken with the parts it passed with. */
    private EmbeddedInventory partsBeforeChange() {
        if (server) {
            satellite.bringUpToNow();
        }
        return parts();
    }

    private void store(EmbeddedInventory parts) {
        ItemStack chassis = chassis();
        ItemSatellite item = (ItemSatellite) chassis.getItem();
        item.writeInvToNBT(chassis, parts);
        if (!server) {
            return;
        }
        SatelliteProperties properties = SatelliteAssembly.propertiesOf(parts, satellite.getProperties().getSatelliteType());
        properties.setId(satellite.getId());
        item.setSatellite(chassis, properties);
        satellite.setProperties(chassis);
        PacketHandler.sendToAll(new PacketSatellite(satellite));
    }

    @Override
    public int getSizeInventory() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        return parts().isEmpty();
    }

    @Override
    @Nonnull
    public ItemStack getStackInSlot(int index) {
        return parts().getStackInSlot(index);
    }

    /** Whether {@code stack} is a primary function of this satellite's own type. */
    private boolean sameTypePrimary(ItemStack stack) {
        SatelliteProperties part = SatelliteRegistry.getSatelliteProperty(stack);
        return part != null && SatelliteProperties.Property.MAIN.isOfType(part.getPropertyFlag())
                && part.getSatelliteType().equals(satellite.getProperties().getSatelliteType());
    }

    @Override
    @Nonnull
    public ItemStack decrStackSize(int index, int count) {
        EmbeddedInventory parts = partsBeforeChange();
        ItemStack taken = parts.decrStackSize(index, count);
        store(parts);
        return taken;
    }

    @Override
    @Nonnull
    public ItemStack removeStackFromSlot(int index) {
        EmbeddedInventory parts = partsBeforeChange();
        ItemStack taken = parts.removeStackFromSlot(index);
        store(parts);
        return taken;
    }

    @Override
    public void setInventorySlotContents(int index, @Nonnull ItemStack stack) {
        if (index == PRIMARY_SLOT && server && !stack.isEmpty() && !sameTypePrimary(stack)) {
            return;
        }
        EmbeddedInventory parts = partsBeforeChange();
        parts.setInventorySlotContents(index, stack);
        store(parts);
    }

    @Override
    public int getInventoryStackLimit() {
        return 1;
    }

    @Override
    public void markDirty() {
    }

    @Override
    public boolean isUsableByPlayer(@Nonnull EntityPlayer player) {
        return true;
    }

    @Override
    public void openInventory(@Nonnull EntityPlayer player) {
    }

    @Override
    public void closeInventory(@Nonnull EntityPlayer player) {
    }

    @Override
    public boolean isItemValidForSlot(int index, @Nonnull ItemStack stack) {
        return (index != PRIMARY_SLOT || sameTypePrimary(stack)) && parts().isItemValidForSlot(index, stack);
    }

    @Override
    public int getField(int id) {
        return 0;
    }

    @Override
    public void setField(int id, int value) {
    }

    @Override
    public int getFieldCount() {
        return 0;
    }

    @Override
    public void clear() {
    }

    @Override
    @Nonnull
    public String getName() {
        return satellite.getName();
    }

    @Override
    public boolean hasCustomName() {
        return false;
    }

    @Override
    @Nonnull
    public ITextComponent getDisplayName() {
        return new TextComponentString(getName());
    }
}
