package dev.stannismod.stellurgy.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;
import dev.stannismod.stellurgy.world.weather.PlanetWeatherState;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Pure-Java unit tests for the per-dimension weather state
 * model. Deliberately stays in the {@code unit} layer: no Minecraft bootstrap,
 * no registries — only {@link NBTTagCompound} which is a plain in-memory data
 * carrier.
 */
public class PlanetWeatherStateTest {

    @Test
    public void planetWeatherStateNbtRoundTrip() {
        PlanetWeatherState source = new PlanetWeatherState();
        source.setCleanWeatherTime(1234);
        source.setRainTime(5678);
        source.setThunderTime(9012);
        source.setRaining(true);
        source.setThundering(true);

        NBTTagCompound tag = new NBTTagCompound();
        source.writeToNBT(tag);

        PlanetWeatherState round = new PlanetWeatherState();
        round.readFromNBT(tag);

        assertEquals(1234, round.getCleanWeatherTime());
        assertEquals(5678, round.getRainTime());
        assertEquals(9012, round.getThunderTime());
        assertEquals(true, round.isRaining());
        assertEquals(true, round.isThundering());
    }

    @Test
    public void planetWeatherStateNbtRoundTripPreservesClearWeather() {
        // Distinct from raining round-trip — guards against the trivial impl
        // bug where false booleans are serialised as missing keys.
        PlanetWeatherState source = new PlanetWeatherState();
        source.setRaining(false);
        source.setThundering(false);
        source.setCleanWeatherTime(20000);

        NBTTagCompound tag = new NBTTagCompound();
        source.writeToNBT(tag);

        PlanetWeatherState round = new PlanetWeatherState();
        round.readFromNBT(tag);

        assertFalse(round.isRaining());
        assertFalse(round.isThundering());
        assertEquals(20000, round.getCleanWeatherTime());
    }

    @Test
    public void perDimTimeNbtRoundTrip() {
        // per-dim worldTime/worldTotalTime persist independently.
        PlanetWeatherState source = new PlanetWeatherState();
        source.setWorldTime(123_456L);
        source.setWorldTotalTime(789_012L);

        NBTTagCompound tag = new NBTTagCompound();
        source.writeToNBT(tag);

        PlanetWeatherState round = new PlanetWeatherState();
        round.readFromNBT(tag);

        assertEquals(123_456L, round.getWorldTime());
        assertEquals(789_012L, round.getWorldTotalTime());
        assertEquals("time flagged initialised after load", true, round.isTimeInitialized());
    }

    @Test
    public void uninitialisedTimeIsNotPersistedAndSeedingApplies() {
        // A fresh state has no time keys, so seedTimeIfNeeded must take effect;
        // once seeded it is sticky (a second seed is ignored).
        PlanetWeatherState fresh = new PlanetWeatherState();
        assertFalse("fresh state has no initialised time", fresh.isTimeInitialized());

        NBTTagCompound tag = new NBTTagCompound();
        fresh.writeToNBT(tag);
        assertFalse("uninitialised time must not be written to NBT", tag.hasKey("worldTime"));

        fresh.seedTimeIfNeeded(1000L, 2000L);
        assertEquals(1000L, fresh.getWorldTime());
        assertEquals(2000L, fresh.getWorldTotalTime());

        // Second seed is a no-op (clock already owned).
        fresh.seedTimeIfNeeded(9999L, 9999L);
        assertEquals("seed is sticky", 1000L, fresh.getWorldTime());
    }
}
