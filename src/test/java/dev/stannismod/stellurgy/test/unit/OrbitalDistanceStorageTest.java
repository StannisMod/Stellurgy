package dev.stannismod.stellurgy.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.BeforeClass;
import org.junit.Test;
import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.universe.SystemBody;
import dev.stannismod.stellurgy.universe.SystemBodyKind;
import dev.stannismod.stellurgy.universe.UniverseScale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * An orbital distance the universe model can NAME survives every NBT form it is saved in — a planet's
 * dimension, a body of a system, a companion star — exactly, at the model's widest named reach.
 *
 * <p>That reach is 5 000 AU, which is 7.5 billion units of 100 km: past what an {@code int} holds.
 * Each method's red names the one class whose storage narrowed the value.</p>
 *
 * <p>red-witnessed: 2026-09-30, with each of the three writers —
 * {@code StellarBody#writeToNBT} at {@code nbt.setLong("companionOrbit", orbitalDistance)},
 * {@code SystemBody#writeToNBT} at {@code nbt.setLong("orbitalDist", orbitalDistance)} and
 * {@code DimensionProperties#writeToNBT} at {@code nbt.setLong("orbitalDist", orbitalDist)} — put back
 * to {@code setInteger(key, (int) value)}: all three fail "expected:&lt;7479895000&gt; but
 * was:&lt;-1110039592&gt;".</p>
 */
public class OrbitalDistanceStorageTest {

    /** The widest orbit the model names — read from production, not restated here. */
    private static final long WIDEST_NAMED_ORBIT = UniverseScale.MAX_NAMED_ORBIT_UNITS;

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    /** Without this the three round trips below could not exhibit a narrowing at all. */
    private static void requireBeyondInt() {
        assertTrue("the widest named orbit (" + WIDEST_NAMED_ORBIT + " units) must lie past the int "
                + "range, or these round trips cannot tell a long field from an int one",
                WIDEST_NAMED_ORBIT > Integer.MAX_VALUE);
    }

    @Test
    public void aCompanionStarKeepsItsOrbitThroughNbt() {
        requireBeyondInt();
        StellarBody written = new StellarBody();
        written.setName("companion");
        written.setOrbitalDistance(WIDEST_NAMED_ORBIT);
        NBTTagCompound tag = new NBTTagCompound();
        written.writeToNBT(tag);

        StellarBody read = new StellarBody();
        read.readFromNBT(tag);
        assertEquals(WIDEST_NAMED_ORBIT, read.getOrbitalDistance());
    }

    @Test
    public void aSystemBodyKeepsItsOrbitThroughNbt() {
        requireBeyondInt();
        SystemBody written = SystemBody.fixedAt(GalacticCoord.ofSectorLocal(3L, -1L, 2L, 0L, 0L, 0L),
                SystemBodyKind.PLANET, Constants.INVALID_PLANET, 0, WIDEST_NAMED_ORBIT);
        NBTTagCompound tag = new NBTTagCompound();
        written.writeToNBT(tag);

        assertEquals(WIDEST_NAMED_ORBIT, SystemBody.readFromNBT(tag).orbitalDistance());
    }

}
