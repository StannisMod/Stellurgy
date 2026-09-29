package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.test.trace.TestTrace;
import zmaster587.advancedRocketry.world.util.BasicTeleporter;

/**
 * A body was PLACED by one of the mod's own teleporters at the end of a dimension transfer — as an
 * event, with the pose the transfer handed the teleporter and the block it was put on.
 *
 * <h2>What the event is</h2>
 *
 * <p>Every dimension change the mod drives itself — the {@code /goto} family, a station creation,
 * the space-elevator capsule, a rocket landing, the planet handler's fall-back to the overworld —
 * finishes in {@code BasicTeleporter.placeEntity}, which is the single line that decides WHERE in
 * the destination world the body ends up. {@code teleporter_placed} is that line as a record:
 * {@code who} is the body's name, {@code dim} the destination dimension (the {@code world} argument
 * is the destination server world; the origin world is not an argument of this seam), {@code from}
 * the pose the body carried INTO the teleporter, {@code target} the block the teleporter put it on.</p>
 *
 * <p>{@code target} is read back off the entity at RETURN, not off {@code getTargetPos}: the
 * seeking variant ({@code TeleporterSeekBlock}) overrides only that resolver and inherits this
 * {@code placeEntity}, so one injection into the base class covers both, and the record shows where
 * the body was actually put rather than what the base resolver would have answered.</p>
 *
 * <h2>What {@code from} is, exactly — and what it is NOT</h2>
 *
 * <p>{@code from} is the pose the teleporter RECEIVED, nothing stronger. Two rewrites happen above
 * this seam and neither is visible in the record.</p>
 *
 * <p>First, the vanilla transfer rewrites the pose immediately before calling the teleporter: X and
 * Z are scaled by the two worlds' movement factors, clamped to the destination's border and
 * truncated to integers, while Y is left as it was ({@code Entity.changeDimension},
 * {@code PlayerList.transferEntityToWorld}). So even on the plainest path {@code from} carries
 * origin Y exact and origin X/Z only to the block, never the origin coordinate to the metre.</p>
 *
 * <p>Second — and this is the trap — a caller may have already overwritten the pose with the
 * DESTINATION it chose. The two body-carrying paths do exactly that: a rocket and an elevator
 * capsule both call {@code setPosition(destX, destY, destZ)} and only then build the teleporter, so
 * on those paths {@code from} is the destination the caller picked (X/Z then scaled and truncated
 * by vanilla), and reading it as "where the body came from" is simply wrong. The player paths
 * ({@code /goto}, {@code /fetch}, station creation, the planet handler's fall-back) do not
 * pre-write, so there {@code from} is the departure pose to the block. A test that wants the exact
 * departure pose reads it off the departure event, not off this one — on any path.</p>
 *
 * <h2>Side</h2>
 *
 * <p>Server only, by construction: both vanilla entry points that reach a teleporter
 * ({@code Entity.changeDimension} and {@code PlayerList.transferEntityToWorld}) run on the server
 * thread, and the entity form guards on
 * {@code !world.isRemote}. Recorded through {@code recordServer} rather than routed by the
 * entity's world, because at this seam that field is STALE — it still names the world being left
 * (the transfer re-points it after placing), so routing by it would stamp the record with the clock
 * of a world the body is no longer in. The snapshot lives in instance fields on the teleporter: one
 * teleporter object is built per transfer at every call site, so nothing is shared across bodies.</p>
 *
 * <h2>What it is silent about</h2>
 *
 * <p>It does not see a transfer that never reaches a teleporter — one refused by
 * {@code ForgeHooks.onTravelToDimension}, or a dead entity, which the transfer skips. It does not
 * see vanilla's own {@code Teleporter} (a portal). It does not know whether the body was then
 * SPAWNED in the destination world — that is the next line of the transfer, not this one. It does
 * not carry the origin dimension. For a non-player body it records the ORIGINAL object, which the
 * transfer copies into a fresh entity with a new id one line later, so an entity id would not
 * survive this seam and none is recorded.</p>
 *
 * <p>{@code target} is therefore the block the base transfer put the ORIGINAL body on, and for a
 * non-player body that is not necessarily where anything ends up: the copy is moved again by the
 * caller after the transfer returns (a rocket to {@code (posX, y, posZ)}, a capsule to the same
 * pose with its rotation restored), and this seam has already fired by then. Read {@code target} as
 * "what the teleporter resolved", never as "where the body came to rest"; for a player, where the
 * two coincide, the mod moves no copy and the two readings are the same.</p>
 */
@Mixin(BasicTeleporter.class)
public abstract class MixinBasicTeleporterEvents {

    private static final String INSTRUMENT = "teleporter_events";

    /** The pose at the head of {@code placeEntity}, so the RETURN can say where the body came from. */
    @Unique
    private double arTest$fromX;
    @Unique
    private double arTest$fromY;
    @Unique
    private double arTest$fromZ;

    @Inject(method = "placeEntity", at = @At("HEAD"))
    private void arTest$placing(World world, Entity entity, float yaw, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        if (entity == null) {
            return;
        }
        arTest$fromX = entity.posX;
        arTest$fromY = entity.posY;
        arTest$fromZ = entity.posZ;
    }

    // RETURN, not TAIL, per the pin; the target has exactly one return, so the two are the same site.
    @Inject(method = "placeEntity", at = @At("RETURN"))
    private void arTest$placed(World world, Entity entity, float yaw, CallbackInfo ci) {
        if (entity == null) {
            return;
        }
        BlockPos target = entity.getPosition();
        String dim = world == null || world.provider == null ? "null"
                : String.valueOf(world.provider.getDimension());
        TestTrace.recordServer("teleporter_placed", "\"who\":\"" + TestTrace.json(entity.getName())
                + "\",\"dim\":" + dim
                + ",\"from\":\"" + TestTrace.fmt(arTest$fromX) + "," + TestTrace.fmt(arTest$fromY)
                + "," + TestTrace.fmt(arTest$fromZ)
                + "\",\"target\":\"" + target.getX() + "," + target.getY() + "," + target.getZ() + "\"");
    }
}
