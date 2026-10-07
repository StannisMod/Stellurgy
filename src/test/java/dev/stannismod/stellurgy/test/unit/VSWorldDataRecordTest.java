package dev.stannismod.stellurgy.test.unit;

import net.minecraft.nbt.NBTTagByteArray;
import org.junit.Test;
import org.valkyrienskies.mod.common.capability.VSWorldDataCapability;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * What the physics substrate does with a world's saved ship record that it cannot read.
 *
 * <p>The record is the world's whole ship registry and claim cursor. Read as empty, it is a world with
 * no ships, and the save after that writes the empty record over the one still on disk.</p>
 */
public class VSWorldDataRecordTest {

    /**
     * An unreadable saved record stops the world from loading instead of being read as "no ships".
     *
     * <p>Contract: this fails if {@code VSDefaultCapability#readNBT} stops refusing a record it cannot
     * deserialize.</p>
     *
     * <p>red-witnessed: with {@code VSDefaultCapability#readNBT} at {@code throw unreadable(ex);} preceded by
     * the old {@code this.instance = factory.get()} and a return, this fails with "bytes that are not a
     * ship record must stop the load, not be taken for a world with no ships" (2026-10-07).</p>
     */
    @Test
    public void anUnreadableSavedRecordStopsTheLoadInsteadOfReadingAsNoShips() {
        VSWorldDataCapability written = new VSWorldDataCapability();
        NBTTagByteArray saved = written.writeNBT(null);
        assertNotNull("a record the production writer wrote must read back",
                new VSWorldDataCapability().readNBT(saved, null));

        try {
            new VSWorldDataCapability().readNBT(new NBTTagByteArray(new byte[]{0x7F, 0x00, 0x13}), null);
        } catch (IllegalStateException refused) {
            return;
        }
        fail("bytes that are not a ship record must stop the load, not be taken for a world with no ships");
    }
}
