package dev.stannismod.stellurgy.probe;

import dev.stannismod.stellurgy.api.satellite.SatelliteOrbit;
import dev.stannismod.stellurgy.environment.BodyRotation;
import dev.stannismod.stellurgy.satellite.DoseReadings;
import dev.stannismod.stellurgy.satellite.FailureJournal;
import dev.stannismod.stellurgy.satellite.SatelliteAccrual;
import dev.stannismod.stellurgy.satellite.SatelliteDosimeter;
import net.minecraft.nbt.NBTTagCompound;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What the first probe recorded on its orbit before it fell: a dosimeter's readings and its failure
 * journal, written by the same law that writes a player's satellite's — {@link SatelliteAccrual} over the
 * probe's own orbit and its body's field — from its release until its electronics' dose reached their
 * budget, or until the world's clock began, whichever came first.
 *
 * <p>So the record breaks off where the dose law puts the probe's death, and nowhere else: the last
 * reading is from the segment its electronics stopped in, and the journal's last entry is that stop.</p>
 */
public final class ProbeRecord {

    /** The probe's dosimeter: its one instrument and its primary function, so its stop is the probe's death. */
    static final int DOSIMETER_SLOT = 0;
    static final int PANEL_SLOT = 1;

    /**
     * The panel's power in full light, per tick: twice what the probe spends while it reads — its upkeep
     * and its dosimeter — so a lit half-orbit charges what the dark half spends. A choice of the probe's
     * design, not a measurement.
     */
    static final double PANEL_POWER_PER_TICK = 2d * (SatelliteAccrual.UPKEEP_PER_TICK + SatelliteDosimeter.DRAW_PER_TICK);

    /**
     * The key a pack's additions sit under beside a record written by {@link #writeToNBT}: kept apart from the
     * core record, which reads nothing under it.
     */
    public static final String PACK_KEY = "pack";

    private final SatelliteOrbit orbit;
    private final BodyRotation turn;
    private final FailureJournal journal;
    private final DoseReadings readings;
    private final long diedAt;

    private ProbeRecord(SatelliteOrbit orbit, BodyRotation turn, FailureJournal journal, DoseReadings readings, long diedAt) {
        this.orbit = orbit;
        this.turn = turn;
        this.journal = journal;
        this.readings = readings;
        this.diedAt = diedAt;
    }

    /**
     * The record of a probe flying {@code about}'s orbit, about a body turning by {@code turn}, from that orbit's
     * epoch until its dosimeter stops or {@code endTick} comes, whichever is first, under {@code rules}.
     */
    public static ProbeRecord lived(SatelliteAccrual.Body about, BodyRotation turn, SatelliteAccrual.Rules rules,
                                    long endTick) {
        SatelliteOrbit orbit = about.orbit();
        long step = SatelliteAccrual.segmentTicks(orbit);
        // The battery is sized to carry the probe through the longest dark its orbit can have: half a period.
        double capacity = (SatelliteAccrual.UPKEEP_PER_TICK + SatelliteDosimeter.DRAW_PER_TICK) * orbit.periodTicks() / 2d;
        SatelliteAccrual.Part dosimeter = new SatelliteAccrual.Part(DOSIMETER_SLOT, SatelliteAccrual.PartKind.ELECTRONICS,
                true, 0d, 0, 0d);
        SatelliteAccrual.Part panel = new SatelliteAccrual.Part(PANEL_SLOT, SatelliteAccrual.PartKind.POWER_GENERATION,
                false, PANEL_POWER_PER_TICK, 0, 0d);
        List<SatelliteAccrual.Part> parts = Arrays.asList(dosimeter, panel);
        SatelliteAccrual.Payload payload = new SatelliteAccrual.Payload(false, true, 0d, SatelliteDosimeter.DRAW_PER_TICK);
        SatelliteAccrual.State state = new SatelliteAccrual.State(0L, capacity, capacity, 0L, 0d,
                new FailureJournal(), new DoseReadings());

        long diedAt = Long.MIN_VALUE;
        // One segment at a time, so the walk stops in the segment the dosimeter stops in and not after it.
        for (long segmentEnd = orbit.epochTick() + step; segmentEnd <= endTick; segmentEnd += step) {
            SatelliteAccrual.advance(about, parts, payload, state, rules, 0L, segmentEnd);
            if (!dosimeter.works(rules)) {
                diedAt = orbit.epochTick() + (state.segmentsDone() - 1L) * step + step / 2L;
                break;
            }
        }
        return new ProbeRecord(orbit, turn, state.journal(), state.readings(), diedAt);
    }

    /** The orbit the probe flew, and how its body turned under it: where each entry of its record was taken. */
    public Optional<SatelliteOrbit> orbit() {
        return Optional.ofNullable(orbit);
    }

    /** How the body under the probe turned; present whenever {@link #orbit()} is. */
    public Optional<BodyRotation> turn() {
        return Optional.ofNullable(turn);
    }

    /** The probe's failure journal: its dosimeter's stop, when it stopped, and nothing else that can happen to it. */
    public FailureJournal journal() {
        return journal;
    }

    /** The dosimeter's readings, oldest first. */
    public DoseReadings readings() {
        return readings;
    }

    /** The middle of the segment the dosimeter stopped in; empty when it was still working when the record ends. */
    public OptionalLong diedAt() {
        return diedAt == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(diedAt);
    }

    public void writeToNBT(NBTTagCompound nbt) {
        if (orbit != null) {
            NBTTagCompound orbitNBT = new NBTTagCompound();
            orbit.writeToNBT(orbitNBT);
            nbt.setTag("orbit", orbitNBT);
            nbt.setDouble("sidereal", turn.siderealTicks());
            nbt.setDouble("turnAtZero", turn.angleAt(0L));
        }
        NBTTagCompound journalNBT = new NBTTagCompound();
        journal.writeToNBT(journalNBT);
        nbt.setTag("journal", journalNBT);
        NBTTagCompound readingsNBT = new NBTTagCompound();
        readings.writeToNBT(readingsNBT);
        nbt.setTag("readings", readingsNBT);
        if (diedAt != Long.MIN_VALUE) {
            nbt.setLong("diedAt", diedAt);
        }
    }

    public static ProbeRecord readFromNBT(NBTTagCompound nbt) {
        FailureJournal journal = new FailureJournal();
        journal.readFromNBT(nbt.getCompoundTag("journal"));
        DoseReadings readings = new DoseReadings();
        readings.readFromNBT(nbt.getCompoundTag("readings"));
        boolean placed = nbt.hasKey("orbit");
        return new ProbeRecord(placed ? SatelliteOrbit.readFromNBT(nbt.getCompoundTag("orbit")) : null,
                placed ? BodyRotation.of(nbt.getDouble("sidereal"), nbt.getDouble("turnAtZero")) : null,
                journal, readings, nbt.hasKey("diedAt") ? nbt.getLong("diedAt") : Long.MIN_VALUE);
    }
}
