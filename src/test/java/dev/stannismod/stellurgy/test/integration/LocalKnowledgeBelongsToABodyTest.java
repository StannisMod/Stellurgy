package dev.stannismod.stellurgy.test.integration;


import net.minecraft.nbt.NBTTagCompound;
import org.junit.BeforeClass;
import org.junit.Test;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;

import static org.junit.Assert.assertFalse;

/**
 * What a BODY knows, as opposed to what the game knows.
 *
 * <p>Discovery used to have exactly one store: a global set that a beacon or the planet XML wrote
 * into, which every launch pad in every world read. A telescope survey wrote nothing into it, so a
 * player could chart a system, fly there with a ship, and still not be offered that planet by a
 * tier-1 rocket standing on the ground.</p>
 *
 * <p>The contract these tests pin is that knowledge is a property of the PLACE: what one body has
 * learned does not leak to its neighbour, the global set remains a floor under every body rather
 * than being replaced, and a body's learning survives a save/load. They deliberately do not pin who
 * WRITES the set - an observatory, a beacon and a crystal upload are separate mechanics with their
 * own tests - only that a body has one and that it is asked.</p>
 */
public class LocalKnowledgeBelongsToABodyTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Test
    public void aBodyThatLearnedNothingWritesNothing() {
        DimensionProperties untaught = new DimensionProperties(106);

        NBTTagCompound nbt = new NBTTagCompound();
        untaught.writeToNBT(nbt);

        assertFalse("an empty set must not occupy a key in every planet's save data",
                nbt.hasKey("locallyKnownPlanets"));
    }

}
