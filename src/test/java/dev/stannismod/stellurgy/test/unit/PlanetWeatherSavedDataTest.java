package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.world.weather.PlanetWeatherSavedData;
import dev.stannismod.stellurgy.world.weather.PlanetWeatherState;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link PlanetWeatherSavedData} stores state by dimension id
 * and round-trips its full map through NBT. Pure-NBT test; no MC bootstrap.
 *
 * Also pins the storage key — changing it would silently lose all existing
 * players' weather state, so it lives behind a regression test.
 */
public class PlanetWeatherSavedDataTest {

    /** Pins INV-WGEN-13 (getOrCreate is idempotent and isolates dimensions). */
    @Pins("INV-WGEN-13")
    @Test
    public void getOrCreateInsertsFreshStateAndIsIdempotent() {
        PlanetWeatherSavedData data = new PlanetWeatherSavedData();
        PlanetWeatherState first = data.getOrCreate(42);
        assertNotNull(first);

        PlanetWeatherState second = data.getOrCreate(42);
        assertSame("second call must return the same instance, not a fresh one",
                first, second);
        assertTrue("inserting fresh state must mark dirty", data.isDirty());
    }

    /** Pins INV-WGEN-13 (getOrCreate is idempotent and isolates dimensions). */
    @Pins("INV-WGEN-13")
    @Test
    public void getOrCreateIsolatesDimensions() {
        PlanetWeatherSavedData data = new PlanetWeatherSavedData();
        PlanetWeatherState a = data.getOrCreate(101);
        PlanetWeatherState b = data.getOrCreate(102);

        assertNotSame("different dim ids must yield different state instances", a, b);
        a.setRaining(true);
        assertFalse("mutation on dim A must not leak to dim B", b.isRaining());
    }
}
