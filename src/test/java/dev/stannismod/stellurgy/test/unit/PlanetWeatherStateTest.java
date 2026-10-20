package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.world.weather.PlanetWeatherState;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

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
    public void perDimTimeNbtRoundTrip() {
        // A state whose per-dim time was set comes back from a save with its clock owned.
        PlanetWeatherState source = new PlanetWeatherState();
        source.setWorldTime(123_456L);
        source.setWorldTotalTime(789_012L);

        NBTTagCompound tag = new NBTTagCompound();
        source.writeToNBT(tag);

        PlanetWeatherState round = new PlanetWeatherState();
        round.readFromNBT(tag);

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
