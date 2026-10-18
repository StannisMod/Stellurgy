package dev.stannismod.stellurgy.block;

import dev.stannismod.stellurgy.api.IFuelTank;
import dev.stannismod.stellurgy.api.fuel.FuelRegistry;
import net.minecraft.block.material.Material;

public class BlockNuclearFuelTank extends BlockFuelTank implements IFuelTank {

    public BlockNuclearFuelTank(Material mat) {
        super(mat);
    }

    /** A nuclear nozzle's working fluid. */
    @Override
    public FuelRegistry.FuelType role() {
        return FuelRegistry.FuelType.NUCLEAR_WORKING_FLUID;
    }
}
