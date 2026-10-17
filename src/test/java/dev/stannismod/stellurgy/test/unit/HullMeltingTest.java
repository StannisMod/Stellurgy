package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.subsystem.heat.HullMelting;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The environment's half of the melting rung: how hot the outside alone can drive a block.
 *
 * <p>The incident flux is quoted on the same curve a radiator sheds on, so turning it into a
 * temperature is that curve read backwards - and the only thing worth pinning is that it IS the same
 * curve. A separate model here would let a ship parked in a star melt at one temperature and radiate
 * as if it were at another.</p>
 */
public class HullMeltingTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @RedWitness(member = "HullMelting#equilibriumKelvin", at = "if (incidentFluxPerCell <= 0.0D) { return 0.0D; }",
            replacedBy = "if (incidentFluxPerCell <= 0.0D) { return 1.0D; }",
            fails = "an unlit surface is not driven anywhere by the environment expected:<0.0> but was:<1.0>", on = "2026-10-17")
    @Test
    public void nothingArrivingIsNoTemperatureAtAll() {
        assertEquals("an unlit surface is not driven anywhere by the environment", 0.0D,
                HullMelting.equilibriumKelvin(0), 0.0D);
    }
}
