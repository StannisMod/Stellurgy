package dev.stannismod.stellurgy.dimension;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants.NBT;

import java.util.*;

/**
 * What terraforming has done to ONE planet world, saved with that world: the chunks whose terrain is
 * already regenerated, the chunks whose biomes are already changed, and the blocks that protect the
 * ground around them from it.
 *
 * <p>Owned by the world it describes — kept in its per-world storage, loaded with it and written with
 * it — rather than by the planet's properties: these are facts about blocks and chunks, not
 * parameters of a body.</p>
 */
public final class TerraformingRecord extends WorldSavedData {

    private static final String NAME = "stellurgy_terraforming";

    private final Set<ChunkPos> chunksTerraformed = new HashSet<>();
    private final Set<ChunkPos> chunksBiomeChanged = new HashSet<>();
    private final List<BlockPos> protectingBlocks = new ArrayList<>();

    /** Called by the world's storage when it loads the record; {@code name} is always {@link #NAME}. */
    public TerraformingRecord(String name) {
        super(name);
    }

    /** {@code world}'s record, loaded from its storage or begun empty. */
    public static TerraformingRecord of(World world) {
        MapStorage storage = world.getPerWorldStorage();
        TerraformingRecord record = (TerraformingRecord) storage.getOrLoadData(TerraformingRecord.class, NAME);
        if (record == null) {
            record = new TerraformingRecord(NAME);
            storage.setData(NAME, record);
        }
        return record;
    }

    public boolean isTerraformed(ChunkPos chunk) {
        return chunksTerraformed.contains(chunk);
    }

    public void markTerraformed(ChunkPos chunk) {
        if (chunksTerraformed.add(chunk)) {
            markDirty();
        }
    }

    public void markBiomeChanged(ChunkPos chunk) {
        if (chunksBiomeChanged.add(chunk)) {
            markDirty();
        }
    }

    /** The chunks whose terrain is regenerated; a copy. */
    public Set<ChunkPos> terraformedChunks() {
        return new HashSet<>(chunksTerraformed);
    }

    /** The chunks whose biomes are changed; a copy. */
    public Set<ChunkPos> biomeChangedChunks() {
        return new HashSet<>(chunksBiomeChanged);
    }

    /** Forgets all progress, so the planet is worked again from the start under its new climate. */
    public void forgetProgress() {
        chunksTerraformed.clear();
        chunksBiomeChanged.clear();
        markDirty();
    }

    /** The protecting blocks; a read-only view. */
    public List<BlockPos> protectingBlocks() {
        return Collections.unmodifiableList(protectingBlocks);
    }

    /** @return whether {@code pos} was not registered before */
    public boolean addProtectingBlock(BlockPos pos) {
        if (protectingBlocks.contains(pos)) {
            return false;
        }
        protectingBlocks.add(pos);
        markDirty();
        return true;
    }

    /** @return whether {@code pos} was registered */
    public boolean removeProtectingBlock(BlockPos pos) {
        if (!protectingBlocks.remove(pos)) {
            return false;
        }
        markDirty();
        return true;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        readChunks(nbt.getTagList("fullyGeneratedChunks", NBT.TAG_COMPOUND), chunksTerraformed);
        readChunks(nbt.getTagList("fullyBiomeChangedChunks", NBT.TAG_COMPOUND), chunksBiomeChanged);
        for (NBTBase entry : nbt.getTagList("terraformingProtectedBlocks", NBT.TAG_COMPOUND)) {
            NBTTagCompound tag = (NBTTagCompound) entry;
            BlockPos pos = new BlockPos(tag.getInteger("x"), tag.getInteger("y"), tag.getInteger("z"));
            if (!protectingBlocks.contains(pos)) {
                protectingBlocks.add(pos);
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setTag("fullyGeneratedChunks", writeChunks(chunksTerraformed));
        nbt.setTag("fullyBiomeChangedChunks", writeChunks(chunksBiomeChanged));
        NBTTagList blocks = new NBTTagList();
        for (BlockPos pos : protectingBlocks) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("x", pos.getX());
            tag.setInteger("y", pos.getY());
            tag.setInteger("z", pos.getZ());
            blocks.appendTag(tag);
        }
        nbt.setTag("terraformingProtectedBlocks", blocks);
        return nbt;
    }

    private static void readChunks(NBTTagList list, Set<ChunkPos> into) {
        for (NBTBase entry : list) {
            NBTTagCompound tag = (NBTTagCompound) entry;
            into.add(new ChunkPos(tag.getInteger("x"), tag.getInteger("z")));
        }
    }

    private static NBTTagList writeChunks(Set<ChunkPos> chunks) {
        NBTTagList list = new NBTTagList();
        for (ChunkPos pos : chunks) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("x", pos.x);
            tag.setInteger("z", pos.z);
            list.appendTag(tag);
        }
        return list;
    }
}
