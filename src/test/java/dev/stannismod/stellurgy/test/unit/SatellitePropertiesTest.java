package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.api.SatelliteRegistry;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.api.satellite.SatelliteProperties;
import dev.stannismod.stellurgy.api.satellite.SatelliteProperties.Property;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Satellite domain logic — SatelliteProperties id assignment + property flags.
 */
public class SatellitePropertiesTest {

    @Test
    public void satelliteIdChipStoresAndReadsId() {
        SatelliteProperties props = new SatelliteProperties();
        assertEquals(-1, props.getId());

        boolean assigned = props.setId(42L);
        assertTrue("first setId on a fresh property must succeed", assigned);

        // setId is one-shot: subsequent assignments are rejected and the first id is kept.
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
}
