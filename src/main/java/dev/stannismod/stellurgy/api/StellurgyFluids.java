package dev.stannismod.stellurgy.api;

import net.minecraftforge.fluids.Fluid;
import dev.stannismod.stellurgy.util.FluidGasGiantGas;

import java.util.HashSet;
import java.util.Set;

/**
 * Stores Stellurgy Fluids
 */
public class StellurgyFluids {
    /** Effectively final, process lifetime: written only by Stellurgy.registerBlocks. */
    public static Fluid fluidOxygen;
    /** Effectively final, process lifetime: written only by Stellurgy.registerBlocks. */
    public static Fluid fluidHydrogen;
    /** Effectively final, process lifetime: written only by Stellurgy.registerBlocks. */
    public static Fluid fluidRocketFuel;
    /** Effectively final, process lifetime: written only by Stellurgy.registerBlocks. */
    public static Fluid fluidNitrogen;
    /** Effectively final, process lifetime: written only by Stellurgy.registerBlocks. */
    public static Fluid fluidEnrichedLava;
    /** Effectively final, process lifetime: filled only by StellurgyFluids.registerGasGiantGas. */
    private static Set<FluidGasGiantGas> gasses = new HashSet<>();

    // Registers a gas that can be spawned on a gas giant
    public static void registerGasGiantGas(Fluid gas, int minGravity, int maxGravity, double chance) {
        gasses.add(new FluidGasGiantGas(gas, minGravity, maxGravity, chance));
    }

    public static Set<FluidGasGiantGas> getGasGiantGasses() {
        return gasses;
    }

    public static boolean isGasGiantGasRegistered(String name) {
        for (FluidGasGiantGas gas : getGasGiantGasses()) {
            if (name.equals(gas.getFluid().getName()))
                return true;
        }
        return false;
    }

    public static boolean isGasGiantGasRegistered(Fluid gasToCheck) {
        for (FluidGasGiantGas gas : getGasGiantGasses()) {
            if (gas.getFluid() == gasToCheck)
                return true;
        }
        return false;
    }
}
