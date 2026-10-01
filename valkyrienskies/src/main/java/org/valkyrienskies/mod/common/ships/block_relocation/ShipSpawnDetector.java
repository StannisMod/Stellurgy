package org.valkyrienskies.mod.common.ships.block_relocation;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraft.world.World;
import org.valkyrienskies.mod.common.config.VSConfig;

public class ShipSpawnDetector extends SpatialDetector {

    /**
     * The blocks a ship-spawn flood does not cross. Written whole by {@link #syncWithConfig}, never
     * edited in place, so an assembly reads either the old set or the new one and never a half-built
     * one. {@code null} until the mod's init has built it.
     */
    private static volatile Set<Block> blacklist;

    /**
     * Rebuild the blacklist from the config and swap it in whole. The mod runs this at init, once
     * every block is registered so a modded name resolves; a config reload runs it again, which is
     * the config reload's partial re-initialisation of the mod, the sanctioned exception to statics
     * being written once.
     */
    public static void syncWithConfig() {
        Set<Block> rebuilt = new HashSet<>();
        Arrays.stream(VSConfig.shipSpawnDetectorBlacklist)
            .map(Block::getBlockFromName)
            .forEach(rebuilt::add);
        blacklist = Collections.unmodifiableSet(rebuilt);
    }

    private final MutableBlockPos mutablePos = new MutableBlockPos();

    ShipSpawnDetector(BlockPos start, World worldIn, int maximum, boolean checkCorners) {
        super(start, worldIn, maximum, checkCorners);
        // syncWithConfig();
        startDetection();
    }

    @Override
    public boolean isValidExpansion(int x, int y, int z) {
        mutablePos.setPos(x, y, z);
        IBlockState state = cache.getBlockState(mutablePos);
        if (state.getBlock() == Blocks.BEDROCK) {
            cleanHouse = true;
            return false;
        }
        Set<Block> excluded = blacklist;
        if (excluded == null) {
            throw new IllegalStateException("ship spawn blacklist read before the mod's init built it");
        }
        return !excluded.contains(state.getBlock());
    }

}
