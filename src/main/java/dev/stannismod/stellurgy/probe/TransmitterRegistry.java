package dev.stannismod.stellurgy.probe;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every transmitter in one world: what a radio in that world can hear. Held by the world, saved with it.
 *
 * <p>A transmitter is a found object that is still sending — the first probe's wreck now, and any wreck
 * generated later. A player's navigation beacon is not one: it belongs to the player's own navigation and
 * is never heard here.</p>
 */
public class TransmitterRegistry extends WorldSavedData {

    public static final String DATA_NAME = "stellurgyTransmitters";

    private final Set<BlockPos> transmitters = new LinkedHashSet<>();

    public TransmitterRegistry() {
        super(DATA_NAME);
    }

    public TransmitterRegistry(String name) {
        super(name);
    }

    /** The registry of THIS world. Server side. */
    public static TransmitterRegistry get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        TransmitterRegistry data = (TransmitterRegistry) storage.getOrLoadData(TransmitterRegistry.class, DATA_NAME);
        if (data == null) {
            data = new TransmitterRegistry();
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    /** A transmitter starts sending at {@code pos}. Answers whether it was not already sending. */
    public boolean add(BlockPos pos) {
        boolean added = transmitters.add(pos.toImmutable());
        if (added) {
            markDirty();
        }
        return added;
    }

    /** The transmitter at {@code pos} stops. Answers whether one was sending there. */
    public boolean remove(BlockPos pos) {
        boolean removed = transmitters.remove(pos);
        if (removed) {
            markDirty();
        }
        return removed;
    }

    /** Where the transmitters of this world are, in the order they started. */
    public List<BlockPos> positions() {
        return Collections.unmodifiableList(new ArrayList<>(transmitters));
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        transmitters.clear();
        NBTTagList list = nbt.getTagList("transmitters", 4);
        for (int i = 0; i < list.tagCount(); i++) {
            transmitters.add(BlockPos.fromLong(((NBTTagLong) list.get(i)).getLong()));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (BlockPos pos : transmitters) {
            list.appendTag(new NBTTagLong(pos.toLong()));
        }
        nbt.setTag("transmitters", list);
        return nbt;
    }
}
