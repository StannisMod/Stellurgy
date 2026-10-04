package dev.stannismod.stellurgy.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;
import dev.stannismod.stellurgy.api.SatelliteRegistry;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.api.satellite.SatelliteProperties;
import dev.stannismod.stellurgy.api.satellite.SatelliteProperties.Property;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Satellite domain logic — SatelliteProperties NBT round-trip + property flags.
 */
public class SatellitePropertiesTest {

    @Test
    public void satellitePropertiesNbtRoundTrip() {
        SatelliteProperties original = new SatelliteProperties(160, 5000, "ar:test_sat", 1024, 5.5f);
        original.setId(0xDEADBEEFL);

        NBTTagCompound nbt = new NBTTagCompound();
        original.writeToNBT(nbt);

        SatelliteProperties restored = new SatelliteProperties();
        restored.readFromNBT(nbt);

        assertEquals(original.getPowerGeneration(), restored.getPowerGeneration());
        assertEquals(original.getPowerStorage(), restored.getPowerStorage());
        assertEquals(original.getMaxDataStorage(), restored.getMaxDataStorage());
        assertEquals(original.getSatelliteType(), restored.getSatelliteType());
        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getWeight(), restored.getWeight(), 1e-6);
    }

    @Test
    public void satelliteIdChipStoresAndReadsId() {
        SatelliteProperties props = new SatelliteProperties();
        assertEquals(-1, props.getId());

        boolean assigned = props.setId(42L);
        assertTrue("first setId on a fresh property must succeed", assigned);
        assertEquals(42L, props.getId());

        // setId is one-shot: subsequent assignments are rejected.
        boolean reassigned = props.setId(99L);
        assertFalse("setId must reject when an ID is already present", reassigned);
        assertEquals(42L, props.getId());
    }

    @Test
    public void propertyFlagsReflectConfiguredFields() {
        // No type, no power, no data -> only zero-valued fields.
        SatelliteProperties empty = new SatelliteProperties();
        int emptyFlag = empty.getPropertyFlag();
        assertFalse(Property.MAIN.isOfType(emptyFlag));
        assertFalse(Property.POWER_GEN.isOfType(emptyFlag));
        assertFalse(Property.BATTERY.isOfType(emptyFlag));
        assertFalse(Property.DATA.isOfType(emptyFlag));

        SatelliteProperties full = new SatelliteProperties(50, 1000, "ar:full", 256, 1.0f);
        int flag = full.getPropertyFlag();
        assertTrue(Property.MAIN.isOfType(flag));
        assertTrue(Property.POWER_GEN.isOfType(flag));
        assertTrue(Property.BATTERY.isOfType(flag));
        assertTrue(Property.DATA.isOfType(flag));
    }

    @Test
    public void propertyFlagsAreDistinctBits() {
        // The flag enum uses 1 << ordinal() — verify they don't collide.
        int seen = 0;
        for (Property p : Property.values()) {
            int flag = p.getFlag();
            assertTrue("flag must be a single non-zero bit: " + p, flag > 0 && (flag & (flag - 1)) == 0);
            assertTrue("flags must be distinct: " + p, (seen & flag) == 0);
            seen |= flag;
        }
    }

    // ---- SatelliteRegistry contract -----------------------------------

    /**
     * Unknown / never-registered satellite type ids must NOT throw — the
     * factory returns null silently so a corrupted save can be reported by the
     * caller, not blow up the world load.
     */
    @Test
    public void unknownSatelliteTypeFailsClearly() {
        SatelliteBase instance = SatelliteRegistry.getNewSatellite(
                "ar.test.never_registered_" + System.nanoTime());
        assertNull("unknown satellite type id must return null, not throw",
                instance);

        // createFromNBT's unknown-type handling (returns null → caller drops it,
        // the C002/C155 fix) is verified in SatelliteRegistryFallbackTest and the
        // server/client e2e; kept out of this pure-unit class.
    }

    /**
     * Power-state fields (generation + storage) round-trip via NBT.
     *
     * Distinct from the catch-all {@link #satellitePropertiesNbtRoundTrip}
     * because production power packets carry ONLY the power state (no name /
     * weight / id), and the read path must accept that minimal payload.
     */
    @Test
    public void satellitePowerStateRoundTrip() {
        // Full state — generation + storage at max.
        SatelliteProperties charged = new SatelliteProperties(200, 50_000,
                "ar:power_test", 0, 0f);
        charged.setId(0xC0FFEEL);

        NBTTagCompound nbt = new NBTTagCompound();
        charged.writeToNBT(nbt);

        SatelliteProperties restored = new SatelliteProperties();
        restored.readFromNBT(nbt);

        assertEquals(200, restored.getPowerGeneration());
        assertEquals(50_000, restored.getPowerStorage());
        assertEquals(0xC0FFEEL, restored.getId());

        // Discharged: zero state must also round-trip (sentinel-safe).
        SatelliteProperties dead = new SatelliteProperties(0, 0, "ar:power_test", 0, 0f);
        NBTTagCompound nbtDead = new NBTTagCompound();
        dead.writeToNBT(nbtDead);

        SatelliteProperties restoredDead = new SatelliteProperties();
        restoredDead.readFromNBT(nbtDead);
        assertEquals(0, restoredDead.getPowerGeneration());
        assertEquals(0, restoredDead.getPowerStorage());
    }
}
