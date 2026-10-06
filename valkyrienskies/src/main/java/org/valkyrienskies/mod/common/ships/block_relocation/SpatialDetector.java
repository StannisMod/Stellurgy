package org.valkyrienskies.mod.common.ships.block_relocation;

import gnu.trove.iterator.TIntIterator;
import gnu.trove.set.hash.TIntHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.World;

/**
 * Used to efficiently detect a connected set of blocks TODO: Incorporate a scanline technique to
 * improve detection speed
 *
 * @author thebest108
 */
public abstract class SpatialDetector {

    public static final int maxRange = 512;
    public static final int maxRangeHalved = maxRange / 2;
    public static final int maxRangeSquared = maxRange * maxRange;
    /**
     * How far, in blocks along X or Z, a detection may extend from its first block. A ship's chunk
     * claim is sized from this ({@link org.valkyrienskies.mod.common.ships.chunk_claims.VSChunkClaim#RADIUS}),
     * so everything a detection can find has somewhere to be copied to. A structure that continues
     * past it is not truncated: the detection reports {@link #reachExceeded} instead.
     */
    public static final int MAX_REACH = 128;
    public final TIntHashSet foundSet = new TIntHashSet(250);
    public final BlockPos firstBlock;
    public final MutableBlockPos tempPos = new MutableBlockPos();
    public final ChunkCache cache;
    public final World worldObj;
    public final int maxSize;
    public final boolean corners;
    public TIntHashSet nextQueue = new TIntHashSet();
    // public int totalCalls = 0;
    public boolean cleanHouse = false;
    /**
     * Whether the structure continues past {@link #MAX_REACH}. The found set then holds only the part
     * inside it, so it is not the whole structure and must not be taken for one.
     */
    public boolean reachExceeded = false;

    public SpatialDetector(BlockPos start, World worldIn, int maximum, boolean checkCorners) {
        firstBlock = start;
        worldObj = worldIn;
        maxSize = maximum;
        corners = checkCorners;
        // One block wider than the reach, so the block just past it is READ rather than taken for air.
        BlockPos minPos = new BlockPos(start.getX() - MAX_REACH - 1, 0, start.getZ() - MAX_REACH - 1);
        BlockPos maxPos = new BlockPos(start.getX() + MAX_REACH + 1, 255, start.getZ() + MAX_REACH + 1);
        cache = new ChunkCache(worldIn, minPos, maxPos, 0);
    }

    public static int getHashWithRespectTo(int realX, int realY, int realZ, BlockPos start) {
        int x = realX - start.getX() + maxRangeHalved;
        int z = realZ - start.getZ() + maxRangeHalved;
        return realY + maxRange * x + maxRangeSquared * z;
    }

    public static BlockPos getPosWithRespectTo(int hash, BlockPos start) {
        int y = hash % maxRange;
        int x = ((hash - y) / maxRange) % maxRange;
        int z = (hash - (x * maxRange) - y) / (maxRangeSquared);
        x -= maxRangeHalved;
        z -= maxRangeHalved;
        return new BlockPos(x + start.getX(), y, z + start.getZ());
    }

    public static void setPosWithRespectTo(int hash, BlockPos start, MutableBlockPos toSet) {
        int y = hash % maxRange;
        int x = ((hash - y) / maxRange) % maxRange;
        int z = (hash - (x * maxRange) - y) / (maxRangeSquared);
        x -= maxRangeHalved;
        z -= maxRangeHalved;
        toSet.setPos(x + start.getX(), y, z + start.getZ());
    }

    public final void startDetection() {
        calculateSpatialOccupation();
        if (cleanHouse) {
            foundSet.clear();
        }
    }

    public List<BlockPos> getBlockPosArrayList() {
        List<BlockPos> detectedBlockPos = new ArrayList<BlockPos>(foundSet.size());
        TIntIterator intIter = foundSet.iterator();
        while (intIter.hasNext()) {
            int hash = intIter.next();
            BlockPos fromHash = getPosWithRespectTo(hash, firstBlock);
            if (fromHash.getY() + 128 - firstBlock.getY() < 0) {
                System.err.println("I really hope this doesnt happen");
                return new ArrayList<BlockPos>();
            }
            detectedBlockPos.add(fromHash);
        }
        return detectedBlockPos;
    }

    protected void calculateSpatialOccupation() {
        nextQueue
            .add(firstBlock.getY() + maxRange * maxRangeHalved + maxRangeSquared * maxRangeHalved);
        MutableBlockPos inRealWorld = new MutableBlockPos();
        int hash;
        while (!nextQueue.isEmpty() && !cleanHouse) {
            TIntIterator queueIter = nextQueue.iterator();
            foundSet.addAll(nextQueue);
            nextQueue = new TIntHashSet();
            while (queueIter.hasNext()) {
                hash = queueIter.next();
                setPosWithRespectTo(hash, firstBlock, inRealWorld);
                if (corners) {
                    tryExpanding(inRealWorld.getX() - 1, inRealWorld.getY() - 1,
                        inRealWorld.getZ() - 1,
                        hash - maxRange - 1 - maxRangeSquared);
                    tryExpanding(inRealWorld.getX() - 1, inRealWorld.getY() - 1, inRealWorld.getZ(),
                        hash - maxRange - 1);
                    tryExpanding(inRealWorld.getX() - 1, inRealWorld.getY() - 1,
                        inRealWorld.getZ() + 1,
                        hash - maxRange - 1 + maxRangeSquared);
                    tryExpanding(inRealWorld.getX() - 1, inRealWorld.getY(), inRealWorld.getZ() - 1,
                        hash - maxRange - maxRangeSquared);
                    tryExpanding(inRealWorld.getX() - 1, inRealWorld.getY(), inRealWorld.getZ(),
                        hash - maxRange);
                    tryExpanding(inRealWorld.getX() - 1, inRealWorld.getY(), inRealWorld.getZ() + 1,
                        hash - maxRange + maxRangeSquared);
                    tryExpanding(inRealWorld.getX() - 1, inRealWorld.getY() + 1,
                        inRealWorld.getZ() - 1,
                        hash - maxRange + 1 - maxRangeSquared);
                    tryExpanding(inRealWorld.getX() - 1, inRealWorld.getY() + 1, inRealWorld.getZ(),
                        hash - maxRange + 1);
                    tryExpanding(inRealWorld.getX() - 1, inRealWorld.getY() + 1,
                        inRealWorld.getZ() + 1,
                        hash - maxRange + 1 + maxRangeSquared);

                    tryExpanding(inRealWorld.getX(), inRealWorld.getY() - 1, inRealWorld.getZ() - 1,
                        hash - 1 - maxRangeSquared);
                    tryExpanding(inRealWorld.getX(), inRealWorld.getY() - 1, inRealWorld.getZ(),
                        hash - 1);
                    tryExpanding(inRealWorld.getX(), inRealWorld.getY() - 1, inRealWorld.getZ() + 1,
                        hash - 1 + maxRangeSquared);
                    tryExpanding(inRealWorld.getX(), inRealWorld.getY(), inRealWorld.getZ() - 1,
                        hash - maxRangeSquared);
                    tryExpanding(inRealWorld.getX(), inRealWorld.getY(), inRealWorld.getZ() + 1,
                        hash + maxRangeSquared);
                    tryExpanding(inRealWorld.getX(), inRealWorld.getY() + 1, inRealWorld.getZ() - 1,
                        hash + 1 - maxRangeSquared);
                    tryExpanding(inRealWorld.getX(), inRealWorld.getY() + 1, inRealWorld.getZ(),
                        hash + 1);
                    tryExpanding(inRealWorld.getX(), inRealWorld.getY() + 1, inRealWorld.getZ() + 1,
                        hash + 1 + maxRangeSquared);

                    tryExpanding(inRealWorld.getX() + 1, inRealWorld.getY() - 1,
                        inRealWorld.getZ() - 1,
                        hash + maxRange - 1 - maxRangeSquared);
                    tryExpanding(inRealWorld.getX() + 1, inRealWorld.getY() - 1, inRealWorld.getZ(),
                        hash + maxRange - 1);
                    tryExpanding(inRealWorld.getX() + 1, inRealWorld.getY() - 1,
                        inRealWorld.getZ() + 1,
                        hash + maxRange - 1 + maxRangeSquared);
                    tryExpanding(inRealWorld.getX() + 1, inRealWorld.getY(), inRealWorld.getZ() - 1,
                        hash + maxRange - maxRangeSquared);
                    tryExpanding(inRealWorld.getX() + 1, inRealWorld.getY(), inRealWorld.getZ(),
                        hash + maxRange);
                    tryExpanding(inRealWorld.getX() + 1, inRealWorld.getY(), inRealWorld.getZ() + 1,
                        hash + maxRange + maxRangeSquared);
                    tryExpanding(inRealWorld.getX() + 1, inRealWorld.getY() + 1,
                        inRealWorld.getZ() - 1,
                        hash + maxRange + 1 - maxRangeSquared);
                    tryExpanding(inRealWorld.getX() + 1, inRealWorld.getY() + 1, inRealWorld.getZ(),
                        hash + maxRange + 1);
                    tryExpanding(inRealWorld.getX() + 1, inRealWorld.getY() + 1,
                        inRealWorld.getZ() + 1,
                        hash + maxRange + 1 + maxRangeSquared);
                } else {
                    tryExpanding(inRealWorld.getX() + 1, inRealWorld.getY(), inRealWorld.getZ(),
                        hash + maxRange);
                    tryExpanding(inRealWorld.getX() - 1, inRealWorld.getY(), inRealWorld.getZ(),
                        hash - maxRange);
                    tryExpanding(inRealWorld.getX(), inRealWorld.getY() + 1, inRealWorld.getZ(),
                        hash + 1);
                    tryExpanding(inRealWorld.getX(), inRealWorld.getY() - 1, inRealWorld.getZ(),
                        hash - 1);
                    tryExpanding(inRealWorld.getX(), inRealWorld.getY(), inRealWorld.getZ() + 1,
                        hash + maxRangeSquared);
                    tryExpanding(inRealWorld.getX(), inRealWorld.getY(), inRealWorld.getZ() - 1,
                        hash - maxRangeSquared);
                }
            }
        }
    }

    protected void tryExpanding(int x, int y, int z, int hash) {
        if (Math.abs(x - firstBlock.getX()) > MAX_REACH || Math.abs(z - firstBlock.getZ()) > MAX_REACH) {
            if (isValidExpansion(x, y, z)) {
                reachExceeded = true;
            }
            return;
        }
        if (isValidExpansion(x, y, z)) {
            // totalCalls++;
            if (!foundSet.contains(hash) && (foundSet.size() + nextQueue.size() < maxSize)) {
                nextQueue.add(hash);
            }
        }
    }

    public abstract boolean isValidExpansion(int x, int y, int z);
}