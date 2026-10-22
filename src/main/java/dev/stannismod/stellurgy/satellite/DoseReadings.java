package dev.stannismod.stellurgy.satellite;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.OptionalDouble;

/**
 * A dosimeter's readings: the dose rate at the satellite's point, each with the tick and the place along
 * the orbit it was read at. Over a body with no field a reading is "absent here" — the quantity is not
 * present there, which is a fact found, not a failed reading.
 *
 * <p>Bounded: past {@link #CAPACITY} readings the oldest are dropped and counted.</p>
 */
public final class DoseReadings {

    /** Readings kept. A bound on storage, not a physical quantity. */
    public static final int CAPACITY = 1024;

    /** One reading. Immutable. */
    public static final class Reading {
        public final long tick;
        /** Angle from the ascending node at {@link #tick}, radians. */
        public final double phaseRad;
        private final double gyPerS;

        Reading(long tick, double phaseRad, double gyPerS) {
            this.tick = tick;
            this.phaseRad = phaseRad;
            this.gyPerS = gyPerS;
        }

        /** The dose rate read, Gy/s in silicon behind the reference shield; empty where it is absent here. */
        public OptionalDouble gyPerS() {
            return Double.isNaN(gyPerS) ? OptionalDouble.empty() : OptionalDouble.of(gyPerS);
        }
    }

    private final Deque<Reading> readings = new ArrayDeque<>();
    private long dropped;

    /** Record a reading; {@code gyPerS} empty records "absent here". */
    void record(long tick, double phaseRad, OptionalDouble gyPerS) {
        if (readings.size() == CAPACITY) {
            readings.removeFirst();
            dropped++;
        }
        readings.addLast(new Reading(tick, phaseRad, gyPerS.isPresent() ? gyPerS.getAsDouble() : Double.NaN));
    }

    /** The readings held, oldest first. */
    public List<Reading> readings() {
        return Collections.unmodifiableList(new ArrayList<>(readings));
    }

    /** How many of the oldest readings were dropped to keep within the capacity. */
    public long dropped() {
        return dropped;
    }

    /** How many readings were ever recorded, the dropped ones included: the number the next one will have. */
    public long recorded() {
        return dropped + readings.size();
    }

    /** The readings held whose number — counting every reading ever recorded from zero — is {@code first} or later, oldest first. */
    public List<Reading> since(long first) {
        List<Reading> held = new ArrayList<>(readings);
        int skip = (int) Math.max(0L, Math.min(held.size(), first - dropped));
        return Collections.unmodifiableList(held.subList(skip, held.size()));
    }

    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (Reading r : readings) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("tick", r.tick);
            tag.setDouble("phase", r.phaseRad);
            if (!Double.isNaN(r.gyPerS)) {
                tag.setDouble("rate", r.gyPerS);
            }
            list.appendTag(tag);
        }
        nbt.setTag("readings", list);
        nbt.setLong("dropped", dropped);
    }

    public void readFromNBT(NBTTagCompound nbt) {
        readings.clear();
        NBTTagList list = nbt.getTagList("readings", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            readings.addLast(new Reading(tag.getLong("tick"), tag.getDouble("phase"),
                    tag.hasKey("rate") ? tag.getDouble("rate") : Double.NaN));
        }
        dropped = nbt.getLong("dropped");
    }
}
