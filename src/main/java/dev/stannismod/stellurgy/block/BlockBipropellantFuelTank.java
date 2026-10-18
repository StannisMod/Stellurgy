package dev.stannismod.stellurgy.block;

import dev.stannismod.stellurgy.api.IFuelTank;
import dev.stannismod.stellurgy.api.fuel.FuelRegistry;
import net.minecraft.block.material.Material;

public class BlockBipropellantFuelTank extends BlockFuelTank implements IFuelTank {
    public BlockBipropellantFuelTank(Material mat) {
        super(mat);
    }

    /** The fuel half of a bipropellant pair. */
    @Override
    public FuelRegistry.FuelType role() {
        return FuelRegistry.FuelType.LIQUID_BIPROPELLANT;
    }
}
