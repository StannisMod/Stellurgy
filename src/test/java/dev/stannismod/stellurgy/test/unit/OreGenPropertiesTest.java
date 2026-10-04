package dev.stannismod.stellurgy.test.unit;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import dev.stannismod.stellurgy.dimension.DimensionProperties.AtmosphereTypes;
import dev.stannismod.stellurgy.dimension.DimensionProperties.Temps;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.util.OreGenProperties;
import dev.stannismod.stellurgy.util.OreGenTable;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 *
 * {@link OreGenProperties} is the per-planet ore registry feeding
 * {@code ChunkProviderPlanet}, looked up through the server's
 * [pressure][temperature] {@link OreGenTable}. Each test builds its own
 * table, so nothing it writes reaches the server's. Verify that
 * {@code setOresForTemperature} / {@code setOresForPressure} truly set every
 * row of the other axis for the given key, and only those.
 */
public class OreGenPropertiesTest {

    /** A constant: an enum constant whose only field is a final int. */
    private static final AtmosphereTypes ATM = AtmosphereTypes.NORMAL;
    /** A constant: an enum constant whose only field is a final int. */
    private static final Temps TEMP = Temps.NORMAL;

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    private OreGenTable table;

    @Before
    public void freshTable() {
        table = new OreGenTable();
    }

    @Test
    public void setOresForTemperatureSetsEveryPressureRow() {
        OreGenProperties props = new OreGenProperties();
        table.setOresForTemperature(TEMP, props);
        for (AtmosphereTypes a : AtmosphereTypes.values()) {
            assertSame("setOresForTemperature failed to fan out to pressure " + a,
                    props, table.getOresForPressure(a, TEMP));
        }
        // …but didn't leak into other temperatures.
        for (Temps other : Temps.values()) {
            if (other == TEMP) continue;
            assertNull("setOresForTemperature leaked into other temperature " + other,
                    table.getOresForPressure(ATM, other));
        }
    }

    @Test
    public void setOresForPressureSetsEveryTemperatureRow() {
        OreGenProperties props = new OreGenProperties();
        table.setOresForPressure(ATM, props);
        for (Temps t : Temps.values()) {
            assertSame("setOresForPressure failed to fan out to temp " + t,
                    props, table.getOresForPressure(ATM, t));
        }
        for (AtmosphereTypes other : AtmosphereTypes.values()) {
            if (other == ATM) continue;
            assertNull("setOresForPressure leaked into other atm " + other,
                    table.getOresForPressure(other, TEMP));
        }
    }
}
