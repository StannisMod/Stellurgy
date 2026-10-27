package dev.stannismod.stellurgy.tile.satellite;

import dev.stannismod.stellurgy.api.satellite.SatelliteOrbit;
import dev.stannismod.stellurgy.environment.BodyRotation;
import dev.stannismod.stellurgy.probe.ProbePack;
import dev.stannismod.stellurgy.probe.ProbeRecord;
import dev.stannismod.stellurgy.satellite.DoseReadings;
import dev.stannismod.stellurgy.satellite.Downlink;
import dev.stannismod.stellurgy.satellite.FailureJournal;
import dev.stannismod.stellurgy.satellite.HeldModel;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * What a ground terminal has downloaded from its satellite: the failure journal's entries and the
 * dosimeter's readings, each a row of time, place on the orbit and value, and what its last contact
 * brought. Readings, never a conclusion.
 *
 * <p>The terminal remembers how far into its satellite's journal and readings it has read, by entry
 * number, so each contact takes only what was recorded since the one before. A satellite that held fewer
 * than were recorded since — its journal is bounded — has lost the rest, and the contact says how many.</p>
 */
public final class TerminalReadout {

    /** Rows kept. A bound on storage, not a physical quantity. */
    public static final int CAPACITY = 256;

    /** What a row records. */
    public enum Kind {
        DATA_LOST,
        PART_FAILED,
        DATA_OVERFLOWED,
        DOSE_RATE
    }

    /** One downloaded row. Immutable. */
    public static final class Row {
        public final long tick;
        /** Angle from the ascending node at {@link #tick}, radians. */
        public final double phaseRad;
        public final Kind kind;
        /** Points for the data kinds; 1 for a part; 0 for a reading. */
        public final int count;
        /** The chassis slot of a failed part; -1 otherwise. */
        public final int slot;
        /** Whether the row is the first probe's, read off its flight recorder, rather than the satellite's. */
        public final boolean fromProbe;
        private final double gyPerS;
        private final double northRad;
        private final double eastRad;

        Row(long tick, double phaseRad, Kind kind, int count, int slot, double gyPerS, double northRad,
            double eastRad, boolean fromProbe) {
            this.tick = tick;
            this.phaseRad = phaseRad;
            this.kind = kind;
            this.count = count;
            this.slot = slot;
            this.gyPerS = gyPerS;
            this.northRad = northRad;
            this.eastRad = eastRad;
            this.fromProbe = fromProbe;
        }

        /** A reading's dose rate, Gy/s; empty for a failure, and for a reading that found none here. */
        public OptionalDouble gyPerS() {
            return Double.isNaN(gyPerS) ? OptionalDouble.empty() : OptionalDouble.of(gyPerS);
        }

        /** The region under the satellite at {@link #tick}. */
        public HeldModel.Region region() {
            return HeldModel.regionOf(new Downlink.GroundPoint(northRad, eastRad));
        }
    }

    /** The place under a satellite on its body at a tick. */
    @FunctionalInterface
    public interface GroundTrack {
        Downlink.GroundPoint at(long tick);

        /** Under a satellite flying {@code orbit} about a body turning by {@code turn} (INV-SAT-41). */
        static GroundTrack under(SatelliteOrbit orbit, BodyRotation turn) {
            return tick -> Downlink.under(orbit.positionAt(tick), turn, tick);
        }
    }

    /** What one contact brought. Immutable. */
    public static final class Contact {
        /** Data points that arrived. */
        public final int received;
        /** Points the satellite recorded as lost aboard since the contact before: upsets and overflow. */
        public final int lostAboard;
        public final int partsFailed;
        public final int readings;
        /** Journal entries and readings recorded since the contact before that the satellite no longer held. */
        public final long missed;
        /** The satellite's telemetry as sent: its power generation, and the points still aboard of its capacity. */
        public final int powerPerTick;
        public final int aboard;
        public final int capacity;

        Contact(int received, int lostAboard, int partsFailed, int readings, long missed,
                int powerPerTick, int aboard, int capacity) {
            this.received = received;
            this.lostAboard = lostAboard;
            this.partsFailed = partsFailed;
            this.readings = readings;
            this.missed = missed;
            this.powerPerTick = powerPerTick;
            this.aboard = aboard;
            this.capacity = capacity;
        }

        /** Whether anything at all came: points, a row, or word that rows were missed. */
        public boolean broughtAnything() {
            return received > 0 || lostAboard > 0 || partsFailed > 0 || readings > 0 || missed > 0;
        }
    }

    private final Deque<Row> rows = new ArrayDeque<>();
    /** The first probe's record, from the flight recorder in the data slot; empty while none is there. */
    private final Deque<Row> probeRows = new ArrayDeque<>();
    private long droppedRows;
    private long satelliteId = -1L;
    private long journalNext;
    private long readingsNext;
    private Contact last = new Contact(0, 0, 0, 0, 0L, 0, 0, 0);

    /**
     * Take what satellite {@code satelliteId} recorded since this terminal last read it, with
     * {@code received} data points that arrived and the telemetry it sent; {@code track} places each row
     * under the satellite. Another satellite than the last one read starts afresh: its rows replace the old
     * ones, since a table mixing two orbits correlates nothing.
     */
    public Contact take(long satelliteId, FailureJournal journal, @Nullable DoseReadings readings, int received,
                        int powerPerTick, int aboard, int capacity, GroundTrack track) {
        if (satelliteId != this.satelliteId) {
            this.satelliteId = satelliteId;
            journalNext = 0L;
            readingsNext = 0L;
            rows.clear();
            droppedRows = 0L;
        }
        List<Row> fresh = new ArrayList<>();
        long missed = Math.max(0L, journal.dropped() - journalNext);
        int lost = 0;
        int parts = 0;
        for (FailureJournal.Entry e : journal.since(journalNext)) {
            if (e.kind == FailureJournal.Kind.PART_FAILED) {
                parts++;
            } else {
                lost += e.count;
            }
            fresh.add(placed(track, e.tick, e.phaseRad, kindOf(e.kind), e.count, e.slot, Double.NaN));
        }
        journalNext = journal.recorded();
        int read = 0;
        if (readings != null) {
            missed += Math.max(0L, readings.dropped() - readingsNext);
            for (DoseReadings.Reading r : readings.since(readingsNext)) {
                OptionalDouble rate = r.gyPerS();
                fresh.add(placed(track, r.tick, r.phaseRad, Kind.DOSE_RATE, 0, -1,
                        rate.isPresent() ? rate.getAsDouble() : Double.NaN));
                read++;
            }
            readingsNext = readings.recorded();
        }
        // A contact's failures and readings interleave in time; the table is read by time.
        fresh.sort(Comparator.comparingLong(row -> row.tick));
        for (Row row : fresh) {
            add(row);
        }
        last = new Contact(received, lost, parts, read, missed, powerPerTick, aboard, capacity);
        return last;
    }

    /**
     * Hold the first probe's record beside the satellite's rows — {@code record} read off a flight recorder, or
     * empty when the recorder is taken out — and what {@code pack} adds to it. Its rows replace the last
     * record's and are placed under the probe on its own orbit.
     */
    public void holdRecord(Optional<ProbeRecord> record, ProbePack pack) {
        probeRows.clear();
        if (!record.isPresent() || !record.get().orbit().isPresent()) {
            return;
        }
        SatelliteOrbit orbit = record.get().orbit().get();
        GroundTrack track = GroundTrack.under(orbit, record.get().turn().get());
        List<Row> held = new ArrayList<>();
        // What a pack adds stands beside the core record as more readings of the same probe, never in place of one.
        for (ProbePack.Reading r : pack.readings()) {
            held.add(placed(track, r.tick, orbit.phaseAt(r.tick), Kind.DOSE_RATE, 0, -1, r.gyPerS, true));
        }
        for (FailureJournal.Entry e : record.get().journal().entries()) {
            held.add(placed(track, e.tick, e.phaseRad, kindOf(e.kind), e.count, e.slot, Double.NaN, true));
        }
        for (DoseReadings.Reading r : record.get().readings().readings()) {
            OptionalDouble rate = r.gyPerS();
            held.add(placed(track, r.tick, r.phaseRad, Kind.DOSE_RATE, 0, -1,
                    rate.isPresent() ? rate.getAsDouble() : Double.NaN, true));
        }
        held.sort(Comparator.comparingLong(row -> row.tick));
        // Held whole: the record is already bounded, by its own journal's and readings' capacities.
        probeRows.addAll(held);
    }

    private static Row placed(GroundTrack track, long tick, double phaseRad, Kind kind, int count, int slot,
                              double gyPerS) {
        return placed(track, tick, phaseRad, kind, count, slot, gyPerS, false);
    }

    private static Row placed(GroundTrack track, long tick, double phaseRad, Kind kind, int count, int slot,
                              double gyPerS, boolean fromProbe) {
        Downlink.GroundPoint under = track.at(tick);
        return new Row(tick, phaseRad, kind, count, slot, gyPerS, under.northRad, under.eastRad, fromProbe);
    }

    /**
     * What the readings held expect of {@code row}, a reading held here: from the other readings of its region,
     * or of the whole table while its region holds none ({@link HeldModel#expect}). Empty for a failure, for a
     * reading that found no dose, and when no other reading is held.
     */
    public Optional<HeldModel.Expectation> expectationOf(Row row) {
        if (row.kind != Kind.DOSE_RATE || !row.gyPerS().isPresent()) {
            return Optional.empty();
        }
        List<HeldModel.Sample> others = new ArrayList<>();
        // A reading is expected from its own orbit's readings: the probe's and the satellite's flew different paths.
        for (Row held : row.fromProbe ? probeRows : rows) {
            if (held != row && held.kind == Kind.DOSE_RATE && held.gyPerS().isPresent()) {
                others.add(new HeldModel.Sample(held.region(), held.gyPerS().getAsDouble()));
            }
        }
        return HeldModel.expect(row.region(), others);
    }

    private static Kind kindOf(FailureJournal.Kind kind) {
        switch (kind) {
            case PART_FAILED:
                return Kind.PART_FAILED;
            case DATA_OVERFLOWED:
                return Kind.DATA_OVERFLOWED;
            default:
                return Kind.DATA_LOST;
        }
    }

    private void add(Row row) {
        if (rows.size() == CAPACITY) {
            rows.removeFirst();
            droppedRows++;
        }
        rows.addLast(row);
    }

    /** The rows held — the satellite's and the probe's, on one time axis — oldest first. */
    public List<Row> rows() {
        return Collections.unmodifiableList(merged());
    }

    private List<Row> merged() {
        List<Row> all = new ArrayList<>(rows.size() + probeRows.size());
        all.addAll(probeRows);
        all.addAll(rows);
        all.sort(Comparator.comparingLong(row -> row.tick));
        return all;
    }

    /**
     * Page {@code index} of the table, counted back from the newest, {@code rowsPerPage} rows to a page:
     * newest first, so page 0 opens on the latest contact. Empty past the last page. How many rows fit is the
     * screen's to say, so the caller passes it.
     */
    public List<Row> page(int index, int rowsPerPage) {
        if (index < 0 || rowsPerPage < 1) {
            throw new IllegalArgumentException("page " + index + " of " + rowsPerPage + " rows");
        }
        List<Row> held = merged();
        List<Row> out = new ArrayList<>(rowsPerPage);
        int newest = held.size() - 1 - index * rowsPerPage;
        for (int i = newest; i > newest - rowsPerPage && i >= 0; i--) {
            out.add(held.get(i));
        }
        return out;
    }

    /** How many pages of {@code rowsPerPage} rows the table fills; an empty table is one empty page. */
    public int pages(int rowsPerPage) {
        if (rowsPerPage < 1) {
            throw new IllegalArgumentException(rowsPerPage + " rows to a page");
        }
        // Every row on the table counts: the satellite's and the probe's together.
        List<Row> rows = merged();
        return Math.max(1, (rows.size() + rowsPerPage - 1) / rowsPerPage);
    }

    /** How many of the oldest rows were dropped to keep within the capacity. */
    public long droppedRows() {
        return droppedRows;
    }

    /** What the last contact brought. */
    public Contact last() {
        return last;
    }

    public void writeToNBT(NBTTagCompound nbt) {
        nbt.setLong("satellite", satelliteId);
        nbt.setLong("journalNext", journalNext);
        nbt.setLong("readingsNext", readingsNext);
        nbt.setLong("dropped", droppedRows);
        nbt.setTag("rows", writeRows(rows));
        nbt.setTag("probeRows", writeRows(probeRows));
        NBTTagCompound contact = new NBTTagCompound();
        contact.setInteger("received", last.received);
        contact.setInteger("lost", last.lostAboard);
        contact.setInteger("parts", last.partsFailed);
        contact.setInteger("readings", last.readings);
        contact.setLong("missed", last.missed);
        contact.setInteger("power", last.powerPerTick);
        contact.setInteger("aboard", last.aboard);
        contact.setInteger("capacity", last.capacity);
        nbt.setTag("last", contact);
    }

    private static NBTTagList writeRows(Deque<Row> from) {
        NBTTagList list = new NBTTagList();
        for (Row r : from) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("tick", r.tick);
            tag.setDouble("phase", r.phaseRad);
            tag.setByte("kind", (byte) r.kind.ordinal());
            tag.setInteger("count", r.count);
            tag.setByte("slot", (byte) r.slot);
            if (!Double.isNaN(r.gyPerS)) {
                tag.setDouble("rate", r.gyPerS);
            }
            tag.setDouble("north", r.northRad);
            tag.setDouble("east", r.eastRad);
            list.appendTag(tag);
        }
        return list;
    }

    private static void readRows(NBTTagList list, Deque<Row> into, boolean fromProbe) {
        into.clear();
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            into.addLast(new Row(tag.getLong("tick"), tag.getDouble("phase"), Kind.values()[tag.getByte("kind")],
                    tag.getInteger("count"), tag.getByte("slot"), tag.hasKey("rate") ? tag.getDouble("rate") : Double.NaN,
                    tag.getDouble("north"), tag.getDouble("east"), fromProbe));
        }
    }

    public void readFromNBT(NBTTagCompound nbt) {
        satelliteId = nbt.hasKey("satellite") ? nbt.getLong("satellite") : -1L;
        journalNext = nbt.getLong("journalNext");
        readingsNext = nbt.getLong("readingsNext");
        droppedRows = nbt.getLong("dropped");
        readRows(nbt.getTagList("rows", 10), rows, false);
        readRows(nbt.getTagList("probeRows", 10), probeRows, true);
        NBTTagCompound contact = nbt.getCompoundTag("last");
        last = new Contact(contact.getInteger("received"), contact.getInteger("lost"), contact.getInteger("parts"),
                contact.getInteger("readings"), contact.getLong("missed"), contact.getInteger("power"),
                contact.getInteger("aboard"), contact.getInteger("capacity"));
    }
}
