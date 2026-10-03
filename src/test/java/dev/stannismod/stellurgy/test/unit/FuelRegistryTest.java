package dev.stannismod.stellurgy.test.unit;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import org.junit.Test;
import dev.stannismod.stellurgy.api.fuel.FuelRegistry;
import dev.stannismod.stellurgy.api.fuel.FuelRegistry.FuelType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * FuelRegistry.
 *
 * Pure logic — uses raw {@link Fluid} instances (no FluidRegistry / item registry
 * required).
 */
public class FuelRegistryTest {

    /** A constant: {@code ResourceLocation} is immutable: two final strings. */
    private static final ResourceLocation STILL = new ResourceLocation("stellurgy", "test_still");
    /** A constant: {@code ResourceLocation} is immutable: two final strings. */
    private static final ResourceLocation FLOW = new ResourceLocation("stellurgy", "test_flow");

    private static Fluid newFluid(String name) {
        // Fluid(String, ResourceLocation, ResourceLocation) — pure data, no MC bootstrap.
        return new Fluid(name, STILL, FLOW);
    }

    @Test
    public void nullFuelTypeIsNeverFuel() {
        Fluid anyFluid = newFluid("ar.test.any." + System.nanoTime());
        assertFalse(FuelRegistry.instance.isFuel((FuelType) null, anyFluid));
        assertEquals(0f, FuelRegistry.instance.getMultiplier((FuelType) null, anyFluid), 0f);
    }
}
