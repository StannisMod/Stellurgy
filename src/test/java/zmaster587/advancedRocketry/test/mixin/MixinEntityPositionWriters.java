package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.entity.EntityDummy;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * Names the writer that moved a player — or the mount he was on — around a ship crossing.
 *
 * <h2>What this replaces, and why it sees more</h2>
 *
 * <p>An arrival un-seat is a multi-writer symptom: the crew re-seat, the rigid pose teleport, the
 * mount's seat-glue, vanilla's passenger snap and the client's own movement authority may all write
 * the same player's position within a few ticks, and fixing any one of them blind only shifts the
 * balance. The timeline must NAME which writers actually fired, and in what order.</p>
 *
 * <p>That used to be done by hand: eleven tagged call sites inside production classes, plus a
 * per-tick sampler that could see a position had CHANGED but never who changed it, so the writer's
 * identity had to be inferred from which tick phase the change straddled. This observes the write
 * itself and records the CALLER, so the writer is named rather than guessed — including writers
 * nobody thought to tag, which is exactly the class of bug the timeline exists for.</p>
 *
 * <h2>Why {@code setPosition} is the right seam</h2>
 *
 * <p>It is the funnel every deliberate placement passes through, and ordinary walking does not:
 * {@code setLocationAndAngles}, {@code setPositionAndRotation}, {@code setPositionAndUpdate} and
 * {@code setPositionAndRotationDirect} all end in it, while {@code move} writes the fields through
 * {@code resetPositionToBB} instead. So a teleport is seen and a footstep is not.</p>
 *
 * <p><b>But the funnel alone cannot see the jump it is carrying.</b> Two of those callers assign
 * {@code posX/posY/posZ} themselves and only THEN call {@code setPosition(this.posX, …)}, so by the
 * time the funnel is entered the old position is already gone and the write measures zero. Measured
 * the first time this ran: a deliberate 140-block placement recorded nothing at all. The three entry
 * points are therefore instrumented individually, each comparing the argument against the field
 * BEFORE its own method touched it; the nested call from the outer two then measures zero and drops
 * out, which is what keeps one placement from being recorded twice.</p>
 *
 * <p>A rider IS re-positioned here every tick by its mount's {@code updatePassenger}, which is why
 * the threshold is not optional: without it a seated pilot alone would fill the ring. {@link
 * #JUMP_THRESHOLD} is the same distance the old sampler used — powered flight moves a few blocks per
 * tick, teleports move hundreds.</p>
 *
 * <h2>Why a mixin against code we compile is allowed here</h2>
 *
 * <p>The distinction is DIRECTION. A production mixin patches a tree into itself, which is
 * indirection where an edit would do. This one reaches from the TESTS into the product and ships
 * with them: it lives in the test source set, is queued only by the harness coremod, and is absent
 * from a released jar. Its predecessor was the opposite — ungated statics on the position-writer
 * path of every crossing in a shipped game, building a formatted string for nobody.</p>
 */
@Mixin(Entity.class)
public abstract class MixinEntityPositionWriters {

    /** How far (blocks) one write may move an entity before it counts as a jump rather than motion. */
    private static final double JUMP_THRESHOLD = 16.0;

    @Inject(method = "setPosition(DDD)V", at = @At("HEAD"))
    private void arTest$recordSetPosition(double x, double y, double z, CallbackInfo ci) {
        arTest$recordJump(y);
    }

    @Inject(method = "setLocationAndAngles(DDDFF)V", at = @At("HEAD"))
    private void arTest$recordSetLocationAndAngles(double x, double y, double z, float yaw,
                                                   float pitch, CallbackInfo ci) {
        arTest$recordJump(y);
    }

    @Inject(method = "setPositionAndRotation(DDDFF)V", at = @At("HEAD"))
    private void arTest$recordSetPositionAndRotation(double x, double y, double z, float yaw,
                                                      float pitch, CallbackInfo ci) {
        arTest$recordJump(y);
    }

    private void arTest$recordJump(double y) {
        Entity self = (Entity) (Object) this;
        if (!arTest$worthWatching(self)) {
            return;
        }
        double from = self.posY;
        if (Math.abs(y - from) <= JUMP_THRESHOLD) {
            return;
        }
        Entity riding = self.getRidingEntity();
        TestTrace.record(self, "pos_jump",
                "\"e\":" + self.getEntityId()
                        + ",\"who\":\"" + TestTrace.json(self.getName()) + "\""
                        + ",\"from\":" + TestTrace.fmt(from)
                        + ",\"to\":" + TestTrace.fmt(y)
                        + ",\"riding\":" + (riding == null ? -1 : riding.getEntityId())
                        + ",\"pass\":\"" + TestTrace.ids(self.getPassengers()) + "\""
                        + ",\"by\":\"" + TestTrace.json(TestTrace.callerTrail()) + "\"");
    }

    /**
     * A mount ATTEMPT, with its verdict. The refusal is the interesting half: a re-seat that gives
     * up silently and a re-seat that was never tried look identical from outside, and telling them
     * apart is what a chain assertion needs.
     */
    @Inject(method = "startRiding(Lnet/minecraft/entity/Entity;Z)Z", at = @At("RETURN"))
    private void arTest$recordMount(Entity mount, boolean force, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof EntityPlayer)) {
            return;
        }
        TestTrace.record(self, "mount",
                "\"e\":" + self.getEntityId()
                        + ",\"who\":\"" + TestTrace.json(self.getName()) + "\""
                        + ",\"mount\":" + mount.getEntityId()
                        + ",\"mountY\":" + TestTrace.fmt(mount.posY)
                        + ",\"y\":" + TestTrace.fmt(self.posY)
                        + ",\"forced\":" + force
                        + ",\"ok\":" + cir.getReturnValue()
                        + ",\"by\":\"" + TestTrace.json(TestTrace.callerTrail()) + "\"");
    }

    /**
     * HEAD on purpose: the mount is still attached, so the record can say what he was thrown off,
     * and the caller trail names the un-seater — which is the whole question an arrival red asks.
     */
    @Inject(method = "dismountRidingEntity", at = @At("HEAD"))
    private void arTest$recordDismount(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof EntityPlayer)) {
            return;
        }
        Entity mount = self.getRidingEntity();
        if (mount == null) {
            return;
        }
        TestTrace.record(self, "dismount",
                "\"e\":" + self.getEntityId()
                        + ",\"who\":\"" + TestTrace.json(self.getName()) + "\""
                        + ",\"mount\":" + mount.getEntityId()
                        + ",\"mountY\":" + TestTrace.fmt(mount.posY)
                        + ",\"y\":" + TestTrace.fmt(self.posY)
                        + ",\"by\":\"" + TestTrace.json(TestTrace.callerTrail()) + "\"");
    }

    /**
     * Players, and anything carrying one. A mount's own jump is half the story of a pilot's arrival
     * — the seat-glue snap that drags him is a write to the MOUNT, not to him.
     */
    private static boolean arTest$worthWatching(Entity entity) {
        return entity instanceof EntityPlayer || entity instanceof EntityDummy || entity.isBeingRidden();
    }
}
