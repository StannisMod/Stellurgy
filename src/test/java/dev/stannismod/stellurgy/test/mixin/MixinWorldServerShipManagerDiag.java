package dev.stannismod.stellurgy.test.mixin;

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

import dev.stannismod.stellurgy.test.trace.SpawnMemory;

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
 * forward, so a ship dropped there is otherwise silent. It records {@code ship_spawn_flood} for every
 * flood — anchor, blocks found, bedrock, and whether VS's own rule refuses the spawn — so a wait for
 * a registration that never comes prints the refusal among the records since its mark.</p>
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
    private void stellurgyTest$noteSpawnEntry(CallbackInfo ci) {
        SpawnMemory.here().noteSpawnEntry(spawnQueue.size());
    }

    /** Named so a reader can tell "no spawn was refused" from "nobody was watching the spawn pass". */
    private static final String SPAWN_INSTRUMENT = "ship_spawn_pass";

    /** Declared on every pass, refusal or not, so an absent {@code ship_spawn_refused} is a reading. */
    @Inject(method = "spawnNewShips", at = @At("HEAD"), require = 1)
    private void stellurgyTest$spawnPassRan(CallbackInfo ci) {
        dev.stannismod.stellurgy.test.trace.TestTrace.instrumentHere(SPAWN_INSTRUMENT);
    }

    /**
     * VS has decided a queued ship will not be built — the decision itself, with VS's own reason, and
     * both of the ship's names so a scenario can ask about its own craft.
     */
    @Inject(method = "refuseSpawn", at = @At("HEAD"), require = 1)
    private void stellurgyTest$spawnRefused(org.valkyrienskies.mod.common.ships.ShipData toSpawn,
                                            BlockPos anchor, String reason, CallbackInfo ci) {
        dev.stannismod.stellurgy.test.trace.TestTrace.instrumentHere(SPAWN_INSTRUMENT);
        dev.stannismod.stellurgy.test.trace.TestTrace.recordHere("ship_spawn_refused",
                "\"vsShip\":\"" + toSpawn.getUuid() + "\",\"stellurgyShip\":\""
                        + toSpawn.getStellurgyDurableId() + "\",\"reason\":\""
                        + dev.stannismod.stellurgy.test.trace.TestTrace.json(reason) + "\",\"x\":"
                        + anchor.getX() + ",\"y\":" + anchor.getY() + ",\"z\":" + anchor.getZ()
                        + ",\"dim\":" + world.provider.getDimension());
    }

    /**
     * At the pass's end, sample the queryable registry. A count that reads &ge;1 here for a ship the
     * later poll sees as 0 means it registered and was then destroyed; a count stuck at 0 while runs
     * climb means {@code addShip} was never reached.
     */
    @Inject(method = "spawnNewShips", at = @At("RETURN"), require = 0)
    private void stellurgyTest$noteSpawnResult(CallbackInfo ci) {
        SpawnMemory.here().noteSpawnReturn();
        SpawnMemory.here().noteQueryableCount(ValkyrienUtils.getQueryableData(world).getShips().size());
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
    private SpatialDetector stellurgyTest$recordFloodResult(BlockFinder.BlockFinderType type, BlockPos pos,
                                                     World floodWorld, int maxSize, boolean corners) {
        SpatialDetector detector = BlockFinder.getBlockFinderFor(type, pos, floodWorld, maxSize, corners);
        if (detector != null) {
            // The flood's INPUTS as a record. Whether VS refuses the spawn on them is VS's decision,
            // recorded where it is taken (`ship_spawn_refused`, below) rather than re-derived here: a
            // copy of the rule in this file had already fallen behind it once.
            dev.stannismod.stellurgy.test.trace.TestTrace.recordHere("ship_spawn_flood",
                    "\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ()
                            + ",\"dim\":" + floodWorld.provider.getDimension()
                            + ",\"found\":" + detector.foundSet.size()
                            + ",\"bedrock\":" + detector.cleanHouse
                            + ",\"reachExceeded\":" + detector.reachExceeded);
            SpawnMemory.here().noteDetector(detector.foundSet.size(), detector.cleanHouse, stellurgyTest$blacklistSize());
            if (detector.foundSet.size() > FLOOD_SHAPE_THRESHOLD) {
                stellurgyTest$recordFloodShape(detector, pos, floodWorld);
            }
        }
        return detector;
    }

    /** Above this many flooded blocks the flood is assumed to have escaped, and its shape is worth
     *  the walk. A craft is far smaller; VS's own abort is at 15000. */
    private static final int FLOOD_SHAPE_THRESHOLD = 500;

    /** For an ESCAPED flood: the found-set bbox and the block at the corner farthest from the
     *  anchor, so the escape direction and what it floods through are both named. */
    private static void stellurgyTest$recordFloodShape(SpatialDetector detector, BlockPos anchor, World floodWorld) {
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
        SpawnMemory.here().noteFloodShape("bbox=[" + minX + ".." + maxX + "," + minY + ".." + maxY + ","
                + minZ + ".." + maxZ + "] anchor=" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ()
                + " farthest=" + farthest.getX() + "," + farthest.getY() + "," + farthest.getZ()
                + "(" + far.getRegistryName() + ")");
    }

    /** VS's {@code ShipSpawnDetector.blacklist}: a private static Set that {@code syncWithConfig}
     *  swaps in whole. Its size at flood time says which configured set the flood ran against
     *  ({@code -2}: not built yet). */
    private static int stellurgyTest$blacklistSize() {
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
