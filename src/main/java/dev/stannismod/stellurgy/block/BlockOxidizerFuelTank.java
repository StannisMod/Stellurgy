package dev.stannismod.stellurgy.block;

import dev.stannismod.stellurgy.api.IFuelTank;
import dev.stannismod.stellurgy.api.fuel.FuelRegistry;
import net.minecraft.block.material.Material;

public class BlockOxidizerFuelTank extends BlockFuelTank implements IFuelTank {

    public BlockOxidizerFuelTank(Material mat) {
        super(mat);
    }

    /** The oxidizer half of a bipropellant pair. */
    @Override
    public FuelRegistry.FuelType role() {
        return FuelRegistry.FuelType.LIQUID_OXIDIZER;
    }
}
