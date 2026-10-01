package dev.stannismod.stellurgy.test.unit;

import net.minecraft.init.Blocks;
import net.minecraft.block.state.IBlockState;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.dimension.DimensionProperties.AtmosphereTypes;
import dev.stannismod.stellurgy.dimension.DimensionProperties.Temps;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.util.OreGenProperties;
import dev.stannismod.stellurgy.util.OreGenProperties.OreEntry;
import dev.stannismod.stellurgy.util.OreGenTable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 *
 * {@link OreGenProperties} is the per-planet ore registry feeding
 * {@code ChunkProviderPlanet}, looked up through the server's
 * [pressure][temperature] {@link OreGenTable}. Each test builds its own
 * table, so nothing it writes reaches the server's. Verify that:
 *
 *   - the {@code setOresForPressureAndTemp} key matches the
 *     {@code getOresForPressure} lookup polarity (a swapped lookup
 *     silently routes ores to the wrong planet type);
 *   - {@code setOresForTemperature} truly sets all pressure rows for the
 *     given temperature, not just one;
 *   - {@code addEntry} appends to the internal list in order (the planet
 *     gen consumer iterates in insertion order).
 */
public class OreGenPropertiesTest {

    private static final AtmosphereTypes ATM = AtmosphereTypes.NORMAL;
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
    public void freshLookupReturnsNull() {
        assertNull("freshly cleared map cell should report null",
                table.getOresForPressure(ATM, TEMP));
    }

    @Test
    public void setAndGetMatchOnSameKey() {
        OreGenProperties props = new OreGenProperties();
        table.setOresForPressureAndTemp(ATM, TEMP, props);
        assertSame("getOresForPressure must return the OreGenProperties just set",
                props, table.getOresForPressure(ATM, TEMP));
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

    @Test
    public void addEntryAppendsInOrder() {
        OreGenProperties props = new OreGenProperties();
        IBlockState s1 = Blocks.IRON_ORE.getDefaultState();
        IBlockState s2 = Blocks.GOLD_ORE.getDefaultState();
        IBlockState s3 = Blocks.DIAMOND_ORE.getDefaultState();
        props.addEntry(s1, 0, 64, 8, 16);
        props.addEntry(s2, 0, 32, 6, 8);
        props.addEntry(s3, 0, 16, 4, 4);
        assertEquals(3, props.getOreEntries().size());
        assertSame(s1, props.getOreEntries().get(0).getBlockState());
        assertSame(s2, props.getOreEntries().get(1).getBlockState());
        assertSame(s3, props.getOreEntries().get(2).getBlockState());
    }

    @Test
    public void oreEntryPreservesAllConstructorArgs() {
        OreGenProperties props = new OreGenProperties();
        IBlockState state = Blocks.REDSTONE_ORE.getDefaultState();
        props.addEntry(state, 12, 48, 7, 9);
        OreEntry e = props.getOreEntries().get(0);
        assertNotNull(e);
        assertEquals(12, e.getMinHeight());
        assertEquals(48, e.getMaxHeight());
        assertEquals(7, e.getClumpSize());
        assertEquals(9, e.getChancePerChunk());
        assertSame(state, e.getBlockState());
    }

    @Test
    public void cellsAreIndependent() {
        // setting one (atm,temp) cell must NOT affect another. A static-state
        // class with off-by-one indexing would silently smear ores across
        // multiple cells; this is a regression net for that.
        OreGenProperties hot = new OreGenProperties();
        OreGenProperties cold = new OreGenProperties();
        table.setOresForPressureAndTemp(ATM, Temps.HOT, hot);
        table.setOresForPressureAndTemp(ATM, Temps.COLD, cold);
        assertSame(hot, table.getOresForPressure(ATM, Temps.HOT));
        assertSame(cold, table.getOresForPressure(ATM, Temps.COLD));
        // Temps.NORMAL was never set on this test's fresh table.
        assertNull(table.getOresForPressure(ATM, Temps.NORMAL));
    }

    @Test
    public void dimensionPropertiesEnumsAreNotEmpty() {
        // Sanity: the [pressure][temperature] table sizes itself by
        // these enum counts. Pin >0 so an accidental enum gutting blows up
        // here rather than at chunk-gen time.
        assertTrue("AtmosphereTypes enum must have at least 1 value",
                DimensionProperties.AtmosphereTypes.values().length > 0);
        assertTrue("Temps enum must have at least 1 value",
                DimensionProperties.Temps.values().length > 0);
    }
}
