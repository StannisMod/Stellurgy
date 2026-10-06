package dev.stannismod.stellurgy.tile.atmosphere;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.StellurgyItems;
import dev.stannismod.stellurgy.atmosphere.AirState;
import dev.stannismod.stellurgy.libvulpes.tile.IComparatorOverride;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.hatch.TileInventoryHatch;

import javax.annotation.Nonnull;

/**
 * Tier 2: a cartridge that absorbs the carbon dioxide of the room an oxygen vent beside it holds.
 *
 * <p>The CO2 it absorbs is gone — no oxygen comes back, which is the difference from the tier above —
 * and each charge of the cartridge pays for a fixed amount of it, so a cartridge in a room with
 * nothing to absorb lasts forever and one in a crowded room dies fast.</p>
 */
public class TileCO2Scrubber extends TileInventoryHatch implements IComparatorOverride {

    /**
     * CO2 absorbed since the last charge was spent, in the unit of
     * {@code lifeSupportScrubberCo2PerCharge}. Never more than the cartridge still has charges for,
     * so the room never loses gas the cartridge did not pay for.
     */
    private long absorbedUnpaid;

    public TileCO2Scrubber() {
        super(1);
        inventory.setCanInsertSlot(0, true);
        inventory.setCanExtractSlot(0, true);
    }

    @Override
    public String getModularInventoryName() {
        return StellurgyBlocks.blockCO2Scrubber.getLocalizedName();
    }

    @Override
    public int getInventoryStackLimit() {
        return 1;
    }

    public boolean useCharge() {
        ItemStack stack = getStackInSlot(0);
        if (!stack.isEmpty() && stack.getItem() == StellurgyItems.itemCarbonScrubberCartridge) {

            if (stack.getItemDamage() != stack.getMaxDamage()) {
                stack.setItemDamage(stack.getItemDamage() + 1);
                if ((32766 - stack.getItemDamage() + 2184) / 2185 != (32766 - stack.getItemDamage() + 1 + 2184) / 2185)
                    this.markDirty();
                return true;
            }
        }
        return false;
    }

    /**
     * One second of scrubbing a room: draw up to {@code lifeSupportScrubberRate} of its CO2 and pay
     * for it in cartridge charges. Without a charged cartridge nothing is drawn, unless the config
     * says scrubbers need none.
     *
     * @param air    the room's gases
     * @param volume the room's size in blocks, which turns the absolute rate into a partial pressure
     * @return whether any CO2 was absorbed
     */
    boolean absorb(@Nonnull AirState air, int volume) {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        int blocks = Math.max(1, volume);
        long perCharge = Math.max(1L, config.lifeSupportScrubberCo2PerCharge);
        long wanted = Math.max(0L, config.lifeSupportScrubberRate);
        if (config.scrubberRequiresCartrige) {
            wanted = Math.min(wanted, remainingCharges() * perCharge - absorbedUnpaid);
        }
        long drawn = air.drawCarbonDioxide(wanted / blocks);
        if (drawn <= 0L) {
            return false;
        }
        if (config.scrubberRequiresCartrige) {
            absorbedUnpaid += drawn * blocks;
            while (absorbedUnpaid >= perCharge && useCharge()) {
                absorbedUnpaid -= perCharge;
            }
        }
        markDirty();
        return true;
    }

    private long remainingCharges() {
        ItemStack stack = getStackInSlot(0);
        if (stack.isEmpty() || stack.getItem() != StellurgyItems.itemCarbonScrubberCartridge) {
            return 0L;
        }
        return Math.max(0, stack.getMaxDamage() - stack.getItemDamage());
    }

    @Override
    public int getComparatorOverride() {
        ItemStack stack = getStackInSlot(0);
        if (!stack.isEmpty()) {
            return (32766 - stack.getItemDamage() + 2184) / 2185;
        }
        return 0;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setLong("co2Unpaid", absorbedUnpaid);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        absorbedUnpaid = nbt.getLong("co2Unpaid");
    }
}
