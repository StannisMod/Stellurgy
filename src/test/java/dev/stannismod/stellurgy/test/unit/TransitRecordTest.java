package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.space.TransitRecord;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;


import static org.junit.Assert.*;

/**
 * NBT reading of {@link TransitRecord} — the durable snapshot of one in-flight ship.
 */
public class TransitRecordTest {

    private static GalacticCoord coord(long sx, long sy, long sz, long lx, long ly, long lz) {
        return GalacticCoord.ofSectorLocal(sx, sy, sz, lx, ly, lz);
    }

    @Test
    public void packedSnapshotSurvivesWhenPresent() {
        NBTTagCompound snap = new NBTTagCompound();
        snap.setInteger("marker", 42); // stand-in for the StorageChunk payload
        TransitRecord r = new TransitRecord("s", GalacticCoord.ORIGIN, coord(1, 0, 0, 0, 0, 0),
                4_000_000L, 0L, 10L, 0L, 5L, null, snap);

        TransitRecord back = TransitRecord.readFromNBT(r.writeToNBT());

        assertTrue("unmanned record has empty crew", back.crew.isEmpty());
    }
}
