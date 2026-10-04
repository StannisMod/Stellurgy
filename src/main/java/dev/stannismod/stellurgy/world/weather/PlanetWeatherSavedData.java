package dev.stannismod.stellurgy.world.weather;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.storage.WorldSavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Single {@link WorldSavedData} instance living on the overworld's
 * {@code mapStorage} holding weather state for every Stellurgy planet dimension.
 *
 * <p>The decision to centralise (one saved-data, keyed by dimension id) rather
 * than per-world saved-data avoids being entangled with
 * {@code WorldServerMulti}'s per-world storage layout, and avoids depending on
 * any wrapping of the secondary world's {@link net.minecraft.world.storage.WorldInfo}.
 * Overworld is loaded for the entire server lifetime, so this storage is always
 * reachable from anywhere weather state is touched.</p>
 */
public final class PlanetWeatherSavedData extends WorldSavedData {

    public static final String STORAGE_KEY = "stellurgy_planet_weather";

    private final Map<Integer, PlanetWeatherState> statesByDimension = new HashMap<>();

    /**
     * Not persisted: the dimensions this server has already tried to migrate legacy weather for, and
     * already warned about running unwrapped. This object is loaded once per server from that server's
     * save, so both are "once per dimension per server" without anything to release.
     */
    private final Set<Integer> legacyMigrationTried = new HashSet<>();
    private final Set<Integer> unwrappedWarned = new HashSet<>();

    public PlanetWeatherSavedData() {
        super(STORAGE_KEY);
    }

    public PlanetWeatherSavedData(String name) {
        super(name);
    }

    public PlanetWeatherState getOrCreate(int dimensionId) {
        PlanetWeatherState state = statesByDimension.get(dimensionId);
        if (state == null) {
            state = new PlanetWeatherState();
            statesByDimension.put(dimensionId, state);
            markDirty();
        }
        return state;
    }

    public PlanetWeatherState getIfPresent(int dimensionId) {
        return statesByDimension.get(dimensionId);
    }

    public void put(int dimensionId, PlanetWeatherState state) {
        statesByDimension.put(dimensionId, state);
        markDirty();
    }

    /** {@code true} the first time it is asked for {@code dimensionId} on this server. */
    boolean firstLegacyMigration(int dimensionId) {
        return legacyMigrationTried.add(dimensionId);
    }

    /** {@code true} the first time it is asked for {@code dimensionId} on this server. */
    boolean firstUnwrappedWarning(int dimensionId) {
        return unwrappedWarned.add(dimensionId);
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        statesByDimension.clear();
        NBTTagList list = nbt.getTagList("dimensions", 10 /* NBTTagCompound */);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            int dim = entry.getInteger("dim");
            PlanetWeatherState state = new PlanetWeatherState();
            state.readFromNBT(entry);
            statesByDimension.put(dim, state);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<Integer, PlanetWeatherState> e : statesByDimension.entrySet()) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("dim", e.getKey());
            e.getValue().writeToNBT(entry);
            list.appendTag(entry);
        }
        compound.setTag("dimensions", list);
        return compound;
    }
}
