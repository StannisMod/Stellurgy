package dev.stannismod.stellurgy.tile;

import dev.stannismod.stellurgy.api.StellurgyItems;
import dev.stannismod.stellurgy.item.ItemNuclearFuelElement;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The fuel a nuclear core holds: one element (C10 STAT-35). The core is powered while that element
 * has burn-up left; the energy it produces advances the burn-up, and a burned-out element is swapped
 * for spent fuel, which stays until the player or a hopper takes it out.
 *
 * <p>One slot: a NERVA-class 1.1 GW core burns one element in about nineteen hours of full power,
 * so a holder for more would be a size with nothing behind it.</p>
 */
public class TileNuclearCore extends TileEntity {

    private final ItemStackHandler fuel = new ItemStackHandler(1) {
        @Override
        public boolean isItemValid(int slot, @Nonnull ItemStack stack) {
            return stack.getItem() instanceof ItemNuclearFuelElement;
        }

        @Nonnull
        @Override
        public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
            // This Forge's ItemStackHandler does not consult isItemValid itself, so a hopper could
            // otherwise feed the core anything.
            return isItemValid(slot, stack) ? super.insertItem(slot, stack, simulate) : stack;
        }

        @Override
        protected void onContentsChanged(int slot) {
            markDirty();
        }
    };

    /** Whether the core holds fuel with burn-up left, and so delivers power. */
    public boolean isFuelled() {
        return ItemNuclearFuelElement.hasBurnupLeft(fuel.getStackInSlot(0));
    }

    /**
     * Spends {@code joules} of the element's burn-up; an element that reaches its capacity becomes
     * spent fuel. Answers the energy the element could not give.
     */
    public double produce(double joules) {
        ItemStack element = fuel.getStackInSlot(0);
        if (!ItemNuclearFuelElement.hasBurnupLeft(element) || !(joules > 0)) {
            return Math.max(0, joules);
        }
        double after = ItemNuclearFuelElement.burnup(element) + joules;
        if (after < ItemNuclearFuelElement.CAPACITY_JOULES) {
            ItemNuclearFuelElement.setBurnup(element, after);
            markDirty();
            return 0;
        }
        fuel.setStackInSlot(0, new ItemStack(StellurgyItems.itemSpentNuclearFuel));
        return after - ItemNuclearFuelElement.CAPACITY_JOULES;
    }

    /** The slot, for the block's hand interaction. */
    public ItemStackHandler fuel() {
        return fuel;
    }

    @Override
    public boolean hasCapability(@Nonnull Capability<?> capability, @Nullable EnumFacing facing) {
        return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY || super.hasCapability(capability, facing);
    }

    @Nullable
    @Override
    public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(fuel);
        }
        return super.getCapability(capability, facing);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setTag("fuel", fuel.serializeNBT());
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        fuel.deserializeNBT(nbt.getCompoundTag("fuel"));
    }
}
