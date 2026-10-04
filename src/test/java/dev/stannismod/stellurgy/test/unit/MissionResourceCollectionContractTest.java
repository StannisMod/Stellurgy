package dev.stannismod.stellurgy.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.BeforeClass;
import org.junit.Test;
import dev.stannismod.stellurgy.mission.MissionOreMining;
import dev.stannismod.stellurgy.mission.MissionResourceCollection;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;

/**
 *
 * Targets the part of {@link MissionResourceCollection} that is unit-testable without
 * bootstrapping a real server (rocket spawn, dim lookup, world tick — those belong to
 * server-layer tests): a default-constructed mission serialises without throwing.
 */
public class MissionResourceCollectionContractTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Test
    public void defaultMissionSerialisesToNbtWithoutThrowing() {
        // A default-constructed mission must serialise cleanly: writeToNBT
        // tolerates the not-yet-populated fields rather than rejecting null
        // tags. Pin so a regression that reintroduces the old null-NBT throw
        // (IllegalArgumentException) is caught.
        MissionResourceCollection mission = new MissionOreMining();
        NBTTagCompound tag = new NBTTagCompound();
        mission.writeToNBT(tag);
    }
}
