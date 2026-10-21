package dev.stannismod.stellurgy.satellite;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * A satellite's own dated record of what went wrong aboard: each failure with the tick it happened at and
 * where along its orbit it was. Evidence — what the device noticed about itself — never a measurement of
 * the place.
 *
 * <p>Bounded: past {@link #CAPACITY} entries the oldest are dropped and counted, so a journal that has lost
 * its beginning says so.</p>
 */
public final class FailureJournal {

    /** Entries kept. A bound on storage, not a physical quantity. */
    public static final int CAPACITY = 256;

    /** What failed. */
    public enum Kind {
        /** Buffered data points destroyed by memory upsets; {@code count} of them. */
        DATA_LOST,
        /** A part stopped working; {@code slot} is where it sits in the chassis. */
        PART_FAILED,
        /** Data points that no longer fit once a data unit stopped; {@code count} of them. */
        DATA_OVERFLOWED
    }

    /** One failure. Immutable. */
    public static final class Entry {
        public final long tick;
        /** Angle from the ascending node at {@link #tick}, radians. */
        public final double phaseRad;
        public final Kind kind;
        /** Points lost for the data kinds; 1 for a part. */
        public final int count;
        /** The chassis slot of a failed part; -1 for the data kinds. */
        public final int slot;

        Entry(long tick, double phaseRad, Kind kind, int count, int slot) {
            this.tick = tick;
            this.phaseRad = phaseRad;
            this.kind = kind;
            this.count = count;
            this.slot = slot;
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>();
    private long dropped;

    void record(long tick, double phaseRad, Kind kind, int count, int slot) {
        if (entries.size() == CAPACITY) {
            entries.removeFirst();
            dropped++;
        }
        entries.addLast(new Entry(tick, phaseRad, kind, count, slot));
    }

    /** The entries held, oldest first. */
    public List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** How many of the oldest entries were dropped to keep the journal within its capacity. */
    public long dropped() {
        return dropped;
    }

    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (Entry e : entries) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("tick", e.tick);
            tag.setDouble("phase", e.phaseRad);
            tag.setByte("kind", (byte) e.kind.ordinal());
            tag.setInteger("count", e.count);
            tag.setByte("slot", (byte) e.slot);
            list.appendTag(tag);
        }
        nbt.setTag("entries", list);
        nbt.setLong("dropped", dropped);
    }

    public void readFromNBT(NBTTagCompound nbt) {
        entries.clear();
        NBTTagList list = nbt.getTagList("entries", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            entries.addLast(new Entry(tag.getLong("tick"), tag.getDouble("phase"),
                    Kind.values()[tag.getByte("kind")], tag.getInteger("count"), tag.getByte("slot")));
        }
        dropped = nbt.getLong("dropped");
    }
}
