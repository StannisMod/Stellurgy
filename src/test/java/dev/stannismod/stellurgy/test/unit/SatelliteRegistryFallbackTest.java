package dev.stannismod.stellurgy.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;
import dev.stannismod.stellurgy.api.SatelliteRegistry;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;

import static org.junit.Assert.assertNull;

/**
 * Save/wire compatibility for an unregistered satellite type (C002/C155).
 *
 * <p>{@link SatelliteRegistry#getNewSatellite(String)} is the dispatch point
 * for "load a satellite from NBT" (called by
 * {@link SatelliteRegistry#createFromNBT(NBTTagCompound)}). When a save/packet
 * carries a satellite whose type was registered by a companion mod that's been
 * removed from the modpack, the type can't be reconstructed.</p>
 *
 * <p><b>Corrected contract (C002/C155 fix, Path B — drop)</b>:
 * {@code getNewSatellite} returns {@code null} for an unregistered id (by
 * design — callers such as {@code ItemSatellite} and {@code TileSatelliteHatch}
 * rely on that null), and {@code createFromNBT} also returns {@code null} for an
 * unresolvable type so its callers ({@code DimensionProperties.readFromNBT},
 * {@code PacketSatellite.readClient}, {@code PacketSatellitesUpdate.readClient})
 * drop the satellite. Previously {@code createFromNBT} dereferenced the null →
 * {@code NullPointerException}, which {@code PacketSatellite.readClient} and
 * {@code EntityRocket.readEntityFromNBT} propagated as a client disconnect /
 * entity-load failure. A placeholder ({@code SatelliteDefunct}) was rejected:
 * it re-saved as {@code dataType="poo"} (getKey fallback), permanently
 * destroying the original type, and it ticked while inert.</p>
 *
 * <p>These tests pin the corrected contract. Recorded as a known defect.</p>
 */
public class SatelliteRegistryFallbackTest {

    /** getNewSatellite returns null for an unregistered id — by design.
     *  Callers (ItemSatellite, TileSatelliteHatch, …) rely on the null to
     *  detect an unresolvable type. */
    @Test
    public void getNewSatelliteReturnsNullForUnknownType() {
        SatelliteBase result = SatelliteRegistry.getNewSatellite(
                "stellurgy:nonexistent.satellite.type.for.gap4.test");
        assertNull("getNewSatellite must return null for an unregistered id", result);
    }

    /** createFromNBT returns null for an unknown/unregistered dataType (the
     *  caller drops the satellite) instead of NPEing the load/wire path —
     *  the C002/C155 fix. No SatelliteBase.readFromNBT runs, so this needs no
     *  Bootstrap. */
    @Test
    public void createFromNBTWithUnknownTypeReturnsNull() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setString("dataType",
                "stellurgy:nonexistent.satellite.type.for.gap4.test");
        SatelliteBase result = SatelliteRegistry.createFromNBT(nbt);
        assertNull("createFromNBT must return null for an unresolvable dataType "
                + "(callers drop it) — not NPE, not a placeholder", result);
    }
}
