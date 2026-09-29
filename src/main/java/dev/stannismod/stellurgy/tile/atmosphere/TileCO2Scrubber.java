package dev.stannismod.stellurgy.tile.atmosphere;

import net.minecraft.item.ItemStack;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.api.StellurgyItems;
import dev.stannismod.stellurgy.libvulpes.tile.IComparatorOverride;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.hatch.TileInventoryHatch;

public class TileCO2Scrubber extends TileInventoryHatch implements IComparatorOverride {
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

    @Override
    public int getComparatorOverride() {
        ItemStack stack = getStackInSlot(0);
        if (!stack.isEmpty()) {
            return (32766 - stack.getItemDamage() + 2184) / 2185;
        }
        return 0;
    }
}
