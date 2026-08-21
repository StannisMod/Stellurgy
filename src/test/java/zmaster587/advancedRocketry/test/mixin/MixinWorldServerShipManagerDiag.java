package zmaster587.advancedRocketry.test.mixin;

import java.util.LinkedHashSet;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.ships.block_relocation.BlockFinder;
import org.valkyrienskies.mod.common.ships.block_relocation.SpatialDetector;
import org.valkyrienskies.mod.common.ships.ship_world.WorldServerShipManager;
import org.valkyrienskies.mod.common.util.ValkyrienUtils;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import zmaster587.advancedRocketry.command.test.SpawnDiag;

/**
 * Reads WHERE a queued tier-2 ship dies inside Valkyrien Skies' own spawn pass — from the tests,
 * never from production.
 *
 * <h2>Why this class exists separately</h2>
 *
 * <p>These three injections used to sit in the production mixin beside a genuine behaviour fix (the
 * "already loaded" double-load guard), which meant a shipped game ran them and wrote their results
 * into mutable statics nobody there reads. The fix is production's; the observation is the tests'.
 * Splitting them is the whole point: what is left in the production mixin now changes VS's behaviour
 * and nothing else, and this file is absent from a released jar entirely.</p>
 *
 * <p>Behaviour-preserving by construction. The two {@code @Inject}s only read. The {@code @Redirect}
 * calls the SAME factory VS would have called and returns exactly what it returned — it exists
 * because VS's abort gate reports its reason to {@code System.err}, which the harness does not
 * forward, so a ship dropped there is otherwise silent.</p>
 *
 * <p>{@code require = 0} throughout: the targets are VS's, and a version that renames them should
 * cost a test its diagnostics, not stop the client at launch.</p>
 */
// remap = false: the target is a Valkyrien Skies class whose names are identical in dev and reobf
// (they are not vanilla-MC names), so nothing here may be SRG-remapped.
@Mixin(value = WorldServerShipManager.class, remap = false)
public abstract class MixinWorldServerShipManagerDiag {

    /** VS: (anchor, ShipData, finderType) triples queued to SPAWN next physics tick. */
    @Shadow @Final private LinkedHashSet spawnQueue;

    /** VS: the world this manager serves — read for the queryable registry count. */
    @Shadow @Final private WorldServer world;

    /**
     * How many spawns VS is about to process this tick. Separates "never processed" from every
     * other fate a queued ship can meet.
     */
    @Inject(method = "spawnNewShips", at = @At("HEAD"), require = 0)
    private void arTest$noteSpawnEntry(CallbackInfo ci) {
        SpawnDiag.noteSpawnEntry(spawnQueue.size());
    }

    /**
     * At the pass's end, sample the queryable registry. A count that reads &ge;1 here for a ship the
     * later poll sees as 0 means it registered and was then destroyed; a count stuck at 0 while runs
     * climb means {@code addShip} was never reached.
     */
    @Inject(method = "spawnNewShips", at = @At("RETURN"), require = 0)
    private void arTest$noteSpawnResult(CallbackInfo ci) {
        SpawnDiag.noteSpawnReturn();
        SpawnDiag.noteQueryableCount(ValkyrienUtils.getQueryableData(world).getShips().size());
    }

    /**
     * Wrap the flood-detector build so its result is observable: the block count and the bedrock
     * flag are the two inputs to VS's "Ship too big or bedrock detected!" abort. A huge found set
     * means the flood escaped the craft into terrain; a true {@code cleanHouse} means it hit bedrock.
     */
    @Redirect(method = "spawnNewShips",
            at = @At(value = "INVOKE",
                    target = "Lorg/valkyrienskies/mod/common/ships/block_relocation/BlockFinder;"
                            + "getBlockFinderFor(Lorg/valkyrienskies/mod/common/ships/block_relocation/BlockFinder$BlockFinderType;"
                            + "Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/world/World;IZ)"
                            + "Lorg/valkyrienskies/mod/common/ships/block_relocation/SpatialDetector;"),
            require = 0)
    private SpatialDetector arTest$recordFloodResult(BlockFinder.BlockFinderType type, BlockPos pos,
                                                     World floodWorld, int maxSize, boolean corners) {
        SpatialDetector detector = BlockFinder.getBlockFinderFor(type, pos, floodWorld, maxSize, corners);
        if (detector != null) {
            SpawnDiag.noteDetector(detector.foundSet.size(), detector.cleanHouse, arTest$blacklistSize());
            if (detector.foundSet.size() > FLOOD_SHAPE_THRESHOLD) {
                arTest$recordFloodShape(detector, pos, floodWorld);
            }
        }
        return detector;
    }

    /** Above this many flooded blocks the flood is assumed to have escaped, and its shape is worth
     *  the walk. A craft is far smaller; VS's own abort is at 15000. */
    private static final int FLOOD_SHAPE_THRESHOLD = 500;

    /** For an ESCAPED flood: the found-set bbox and the block at the corner farthest from the
     *  anchor, so the escape direction and what it floods through are both named. */
    private static void arTest$recordFloodShape(SpatialDetector detector, BlockPos anchor, World floodWorld) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        BlockPos farthest = anchor;
        double bestD = -1;
        for (BlockPos p : detector.getBlockPosArrayList()) {
            if (p.getX() < minX) minX = p.getX();
            if (p.getY() < minY) minY = p.getY();
            if (p.getZ() < minZ) minZ = p.getZ();
            if (p.getX() > maxX) maxX = p.getX();
            if (p.getY() > maxY) maxY = p.getY();
            if (p.getZ() > maxZ) maxZ = p.getZ();
            double d = p.distanceSq(anchor);
            if (d > bestD) {
                bestD = d;
                farthest = p;
            }
        }
        net.minecraft.block.Block far = floodWorld.getBlockState(farthest).getBlock();
        SpawnDiag.noteFloodShape("bbox=[" + minX + ".." + maxX + "," + minY + ".." + maxY + ","
                + minZ + ".." + maxZ + "] anchor=" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ()
                + " farthest=" + farthest.getX() + "," + farthest.getY() + "," + farthest.getZ()
                + "(" + far.getRegistryName() + ")");
    }

    /** VS's {@code ShipSpawnDetector.blacklist} is a private static Set that {@code syncWithConfig}
     *  rebuilds non-atomically (clear, then repopulate). Its size at flood time catches that window. */
    private static int arTest$blacklistSize() {
        try {
            java.lang.reflect.Field f = Class.forName(
                    "org.valkyrienskies.mod.common.ships.block_relocation.ShipSpawnDetector")
                    .getDeclaredField("blacklist");
            f.setAccessible(true);
            Object set = f.get(null);
            return set instanceof java.util.Collection ? ((java.util.Collection<?>) set).size() : -2;
        } catch (Throwable t) {
            return -3;
        }
    }
}
