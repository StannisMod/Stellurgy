package dev.stannismod.stellurgy.probe;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.Optional;

/**
 * The first probe's fall, once per save: whether it is still to come, coming down now, or down — and,
 * from its entry on, everything the fall is computed from. Held in the save's global storage, so one
 * probe falls whichever world the player stands in.
 *
 * <p>What is held is the fall's INPUTS, never its samples: the trajectory is computed again from them
 * on a load, as every client computes it from the same numbers.</p>
 */
public class ProbeFall extends WorldSavedData {

    public static final String DATA_NAME = "stellurgyFirstProbe";

    /** Where the probe is in its one fall. */
    public enum Phase {
        /** Nothing has fallen yet. */
        WAITING,
        /** It is coming down. */
        FALLING,
        /** It is down; its crater and wreck are laid once their ground is loaded. */
        DOWN
    }

    private long firstLoginTick = -1L;
    private Phase phase = Phase.WAITING;
    private Fall fall;
    private boolean laid;

    public ProbeFall() {
        super(DATA_NAME);
    }

    public ProbeFall(String name) {
        super(name);
    }

    /** The save's fall, in the global storage every world of the server shares. */
    public static ProbeFall get(MinecraftServer server) {
        MapStorage storage = server.getWorld(0).getMapStorage();
        ProbeFall data = (ProbeFall) storage.getOrLoadData(ProbeFall.class, DATA_NAME);
        if (data == null) {
            data = new ProbeFall();
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    /** Everything one fall is computed from. Immutable but for the placement it computes once. */
    public static final class Fall {
        public final int dimension;
        /** That world's time at the entry. */
        public final long entryTick;
        /** The block it lands on top of: the ground's top block, the landing being on its upper face. */
        public final BlockPos landing;
        public final double headingRad;
        public final double bodyRadiusM;
        public final double surfaceGravityMS2;
        public final double atmospherePercent;
        public final double orbitLineAltitudeM;
        /** The player it fell around. */
        public final String player;
        private final NBTTagCompound record;
        private FallPlacement placement;

        public Fall(int dimension, long entryTick, BlockPos landing, double headingRad, double bodyRadiusM,
                    double surfaceGravityMS2, double atmospherePercent, double orbitLineAltitudeM, String player,
                    NBTTagCompound record) {
            this.dimension = dimension;
            this.entryTick = entryTick;
            this.landing = landing.toImmutable();
            this.headingRad = headingRad;
            this.bodyRadiusM = bodyRadiusM;
            this.surfaceGravityMS2 = surfaceGravityMS2;
            this.atmospherePercent = atmospherePercent;
            this.orbitLineAltitudeM = orbitLineAltitudeM;
            this.player = player;
            this.record = record.copy();
        }

        /** The fall laid over its world, computed on first use. */
        public FallPlacement placement() {
            if (placement == null) {
                placement = new FallPlacement(landing.getX() + 0.5d, landing.getY() + 1d, landing.getZ() + 0.5d,
                        headingRad, EntryTrajectory.fly(FirstProbe.entry(bodyRadiusM, surfaceGravityMS2,
                        atmospherePercent, orbitLineAltitudeM)));
            }
            return placement;
        }

        /** The probe's record as the wreck will hold it. A copy. */
        public NBTTagCompound record() {
            return record.copy();
        }

        NBTTagCompound write() {
            NBTTagCompound nbt = new NBTTagCompound();
            nbt.setInteger("dim", dimension);
            nbt.setLong("entry", entryTick);
            nbt.setLong("landing", landing.toLong());
            nbt.setDouble("heading", headingRad);
            nbt.setDouble("radius", bodyRadiusM);
            nbt.setDouble("gravity", surfaceGravityMS2);
            nbt.setDouble("atmosphere", atmospherePercent);
            nbt.setDouble("line", orbitLineAltitudeM);
            nbt.setString("player", player);
            nbt.setTag("record", record.copy());
            return nbt;
        }

        static Fall read(NBTTagCompound nbt) {
            return new Fall(nbt.getInteger("dim"), nbt.getLong("entry"), BlockPos.fromLong(nbt.getLong("landing")),
                    nbt.getDouble("heading"), nbt.getDouble("radius"), nbt.getDouble("gravity"),
                    nbt.getDouble("atmosphere"), nbt.getDouble("line"), nbt.getString("player"),
                    nbt.getCompoundTag("record"));
        }
    }

    public Phase phase() {
        return phase;
    }

    /** The fall, from its entry on; empty while it is still to come. */
    public Optional<Fall> fall() {
        return Optional.ofNullable(fall);
    }

    /** The overworld's time at the first player's first login; negative while nobody has logged in. */
    public long firstLoginTick() {
        return firstLoginTick;
    }

    /** Whether the crater and wreck are in the world. */
    public boolean laid() {
        return laid;
    }

    /** A player logs in at overworld time {@code now}: the save's first login starts the delay. */
    void loggedIn(long now) {
        if (firstLoginTick < 0L) {
            firstLoginTick = now;
            markDirty();
        }
    }

    /** It enters the air. Refused once anything has fallen: there is one probe. */
    boolean enter(Fall entering) {
        if (phase != Phase.WAITING) {
            return false;
        }
        fall = entering;
        phase = Phase.FALLING;
        markDirty();
        return true;
    }

    /** It reaches the ground. */
    void touchDown() {
        if (phase == Phase.FALLING) {
            phase = Phase.DOWN;
            markDirty();
        }
    }

    /** Its crater and wreck are in the world. */
    void markLaid() {
        laid = true;
        markDirty();
    }

    /** How far into {@code fall} its world is, ticks; negative before the entry. */
    static long elapsed(Fall fall, World world) {
        return world.getTotalWorldTime() - fall.entryTick;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        firstLoginTick = nbt.hasKey("firstLogin") ? nbt.getLong("firstLogin") : -1L;
        phase = Phase.values()[nbt.getByte("phase")];
        fall = nbt.hasKey("fall") ? Fall.read(nbt.getCompoundTag("fall")) : null;
        laid = nbt.getBoolean("laid");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        if (firstLoginTick >= 0L) {
            nbt.setLong("firstLogin", firstLoginTick);
        }
        nbt.setByte("phase", (byte) phase.ordinal());
        if (fall != null) {
            nbt.setTag("fall", fall.write());
        }
        nbt.setBoolean("laid", laid);
        return nbt;
    }
}
