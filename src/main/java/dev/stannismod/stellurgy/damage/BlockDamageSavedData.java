package dev.stannismod.stellurgy.damage;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Damage stages of blocks that cannot hold their own, stored per world.
 *
 * <p>A tile entity able to carry a wear stage keeps it, as it always has. Everything else — plain
 * hull, plating, a wall — has nowhere to put a stage, and giving every damaged block a tile entity is
 * not available: vanilla stores a tile only when the block itself declares one, so an injected tile is
 * dropped on the floor. Hence this map.</p>
 *
 * <h3>Shape</h3>
 * <p>Keyed by the packed {@link BlockPos} long, because the access pattern that matters is a turret
 * burst: many cheap reads against blocks that are mostly pristine. A miss must therefore cost nothing,
 * which is why nothing here builds an object to answer "no damage".</p>
 *
 * <h3>Provenance</h3>
 * <p>When a block is destroyed its original state is recorded, so a repair can put back what was
 * there rather than a guess. It is stored as a registry name plus metadata rather than a numeric
 * state id: ids are an install-local encoding and a save that outlives one registry order would
 * otherwise rebuild a hull out of whatever now occupies that number.</p>
 *
 * <h3>This store is per WORLD, and a structure can leave its world</h3>
 * <p>Entries are keyed by position in the world the blocks currently occupy, so they are stable for
 * as long as the blocks are: a ship moves by transform, not by moving its blocks. A structure that is
 * relocated IS re-pasted at fresh coordinates, and there the entries are carried by
 * {@link DamageLayer} — harvested into the capture, expressed as offsets, and replayed at the far
 * end. No block owns the map, so no block can be broken to reset it.</p>
 */
public class BlockDamageSavedData extends WorldSavedData {

    public static final String DATA_NAME = "advancedRocketryBlockDamage";

    private final Map<Long, Entry> entries = new HashMap<>();

    /**
     * Which repairer is working which position right now, by position. TRANSIENT on purpose: it is
     * never written to NBT and dies with this object, which dies with its world. A claim is a fact
     * about machines that are running, and nothing that is running survives a restart, so a saved
     * claim could only ever be a stale one.
     */
    private final Map<Long, Claim> claims = new HashMap<>();

    public BlockDamageSavedData() {
        super(DATA_NAME);
    }

    public BlockDamageSavedData(String name) {
        super(name);
    }

    /** The damage map of THIS world (not a global one — a position means nothing without its world). */
    public static BlockDamageSavedData get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        BlockDamageSavedData data =
                (BlockDamageSavedData) storage.getOrLoadData(BlockDamageSavedData.class, DATA_NAME);
        if (data == null) {
            data = new BlockDamageSavedData();
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    /** Current stage at {@code pos}, or 0 when this position has never been damaged. */
    public int getStage(BlockPos pos) {
        Entry entry = entries.get(pos.toLong());
        return entry == null ? 0 : entry.stage;
    }

    /** Record a new stage. A stage of 0 clears the entry rather than storing "undamaged". */
    public void setStage(BlockPos pos, int stage) {
        long key = pos.toLong();
        if (stage <= 0) {
            if (entries.remove(key) != null) {
                markDirty();
            }
            return;
        }
        Entry entry = entries.get(key);
        if (entry == null) {
            entry = new Entry();
            entries.put(key, entry);
        }
        entry.stage = stage;
        markDirty();
    }

    /**
     * Record what stood at {@code pos} before it was destroyed. Called with the state as it was, at
     * the moment it stops being readable from the world.
     */
    public void recordDestroyed(BlockPos pos, Block block, int meta) {
        if (block == null || block.getRegistryName() == null) {
            return;
        }
        recordDestroyed(pos, block.getRegistryName().toString(), meta);
    }

    /**
     * Record a provenance by NAME, exactly as given — for a record being carried rather than
     * observed. The name is never resolved here: a record naming a block this install does not have
     * is still the only statement of what stood there, and resolving it would turn it into a
     * different statement or into none.
     */
    void recordDestroyed(BlockPos pos, String registryName, int meta) {
        long key = pos.toLong();
        Entry entry = entries.get(key);
        if (entry == null) {
            entry = new Entry();
            entries.put(key, entry);
        }
        entry.originalBlock = registryName;
        entry.originalMeta = meta;
        markDirty();
    }

    /** Registry name of what was destroyed here, or null if nothing was. */
    public String getDestroyedBlockName(BlockPos pos) {
        Entry entry = entries.get(pos.toLong());
        return entry == null ? null : entry.originalBlock;
    }

    /** Metadata of what was destroyed here; meaningless unless {@link #getDestroyedBlockName} is set. */
    public int getDestroyedMeta(BlockPos pos) {
        Entry entry = entries.get(pos.toLong());
        return entry == null ? 0 : entry.originalMeta;
    }

    /** Forget this position entirely — what a completed repair does. */
    public void clear(BlockPos pos) {
        if (entries.remove(pos.toLong()) != null) {
            markDirty();
        }
    }

    /**
     * Give {@code to} exactly what {@code from} had, and leave {@code from} with nothing — for a block
     * that was relocated rather than repaired or rebuilt.
     *
     * <p>"Exactly what it had" includes having had NOTHING: an undamaged block arriving at a position
     * some earlier structure left a record at must read as undamaged, so the absent case clears the
     * destination instead of returning early. Neither argument is retained, so a caller may pass the
     * mutable cursor it is iterating with.</p>
     */
    public void move(BlockPos from, BlockPos to) {
        if (from == null || to == null || from.equals(to)) {
            return;
        }
        Entry entry = entries.remove(from.toLong());
        if (entry == null) {
            clear(to);
            return;
        }
        entries.put(to.toLong(), entry);
        markDirty();
    }

    /** How many positions this world currently holds damage for (diagnostics and tests). */
    public int size() {
        return entries.size();
    }

    /**
     * Every damaged position inside the inclusive box. Walks the entries rather than the volume: a
     * capture box is tens of thousands of positions and almost none of them are damaged, so the cost
     * belongs to what is recorded, not to how big the structure is.
     */
    public List<BlockPos> positionsIn(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        List<BlockPos> found = new ArrayList<>();
        for (Long key : entries.keySet()) {
            BlockPos pos = BlockPos.fromLong(key);
            if (pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ) {
                found.add(pos);
            }
        }
        return found;
    }

    /**
     * Forget every position inside the inclusive box — what a relocation's CUT does to the region it
     * empties. Without it the vacated coordinates keep their damage, and the next structure pasted
     * over them inherits somebody else's holes.
     */
    public void clearBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (BlockPos pos : positionsIn(minX, minY, minZ, maxX, maxY, maxZ)) {
            clear(pos);
        }
    }

    /**
     * Take {@code pos} for {@code holder}, or renew a claim it already holds, as of world tick
     * {@code now}. False when a DIFFERENT holder has it and is still working it.
     *
     * <p>A claim whose holder has not renewed it since the tick before last is taken over: the only
     * way a working holder fails to renew is by not ticking — its chunk unloaded, the server
     * stopped it, it crashed out of its update — and a claim that outlived its holder would park
     * that position's repair forever. Two ticks and not one, because within one world tick the
     * holders run in no fixed order: one that runs after the asker this tick renewed last tick.</p>
     */
    public boolean claim(BlockPos pos, Object holder, long now) {
        long key = pos.toLong();
        Claim held = claims.get(key);
        if (held != null && held.holder != holder && now - held.renewedAt <= 1) {
            return false;
        }        claims.put(key, new Claim(holder, now));
        return true;
    }

    /** Give {@code pos} back, if {@code holder} is the one holding it. */
    public void release(BlockPos pos, Object holder) {
        long key = pos.toLong();
        Claim held = claims.get(key);
        if (held != null && held.holder == holder) {
            claims.remove(key);
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        entries.clear();
        NBTTagList list = nbt.getTagList("entries", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            Entry entry = new Entry();
            entry.stage = tag.getInteger("stage");
            if (tag.hasKey("block")) {
                entry.originalBlock = tag.getString("block");
                entry.originalMeta = tag.getInteger("meta");
            }
            entries.put(tag.getLong("pos"), entry);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<Long, Entry> mapEntry : entries.entrySet()) {
            Entry entry = mapEntry.getValue();
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("pos", mapEntry.getKey());
            tag.setInteger("stage", entry.stage);
            if (entry.originalBlock != null) {
                tag.setString("block", entry.originalBlock);
                tag.setInteger("meta", entry.originalMeta);
            }
            list.appendTag(tag);
        }
        nbt.setTag("entries", list);
        return nbt;
    }

    /**
     * Resolve a recorded provenance name back to a block, or null if no block of that name is
     * registered. Asked by membership rather than by lookup alone: the block registry is a defaulted
     * one and answers an unknown name with AIR, which a caller would take for "fill with air".
     */
    @Nullable
    public static Block blockFromName(@Nullable String registryName) {
        if (registryName == null) {
            return null;
        }
        ResourceLocation key = new ResourceLocation(registryName);
        return Block.REGISTRY.containsKey(key) ? Block.REGISTRY.getObject(key) : null;
    }

    private static final class Entry {
        private int stage;
        private String originalBlock;
        private int originalMeta;
    }

    private static final class Claim {
        /** Compared by identity: a claim belongs to one running machine, not to an equal one. */
        private final Object holder;
        private final long renewedAt;

        private Claim(Object holder, long renewedAt) {
            this.holder = holder;
            this.renewedAt = renewedAt;
        }
    }
}
