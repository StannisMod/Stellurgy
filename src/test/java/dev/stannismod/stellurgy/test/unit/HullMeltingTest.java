package dev.stannismod.stellurgy.test.unit;

import org.junit.BeforeClass;
import org.junit.Test;

import dev.stannismod.stellurgy.subsystem.heat.HullMelting;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;

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

    /**
     * <p>red-witnessed: with {@code HullMelting#equilibriumKelvin} at {@code return 0.0D;} answering 1 K for no incident flux: "an unlit
     * surface is not driven anywhere by the environment expected:&lt;0.0&gt; but was:&lt;1.0&gt;",
     * 2026-09-30.</p>
     */
    @Test
    public void nothingArrivingIsNoTemperatureAtAll() {
        assertEquals("an unlit surface is not driven anywhere by the environment", 0.0D,
                HullMelting.equilibriumKelvin(0), 0.0D);
    }
}
