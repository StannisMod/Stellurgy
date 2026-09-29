package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * Names the PHASE that changed an entity's velocity by more than anything ordinary can.
 *
 * <h2>Why the position recorder could not answer this</h2>
 *
 * <p>{@code MixinEntityPositionWriters} watches deliberate PLACEMENTS — a write to the position
 * funnel, above a sixteen-block threshold, measured on the Y axis alone. That is the right shape for
 * a teleport and blind to two things this family of defects also does: a body carried sideways, and
 * a body handed a velocity and left to fly. Measured 2026-08-23 on a body thrown off an inverted
 * hull: the position recorder answered "nothing was written" — truthfully, because the jump was 10.8
 * blocks HORIZONTAL, and then because the launch that followed was a VELOCITY and not a placement at
 * all. An instrument whose silence is true and irrelevant is the expensive kind.</p>
 *
 * <h2>Why a phase, when a caller trail would be better</h2>
 *
 * <p>Velocity lives in public fields ({@code motionX/Y/Z}), so there is no funnel to inject and no
 * caller to name — the write leaves no method to catch it in. What CAN be bounded is WHEN: an
 * entity's motion is read and rewritten inside {@code Entity.move}, where the substrate resolves
 * collision against ship geometry, and it is also written between ticks by everything else. So this
 * records the change across {@code move} and the change since {@code move} last returned, and labels
 * each. That splits the candidates in half without guessing: a launch that happens INSIDE move is
 * the collision path, one that happens BETWEEN ticks is not.</p>
 *
 * <p>Half an answer, named as half. It is what this seam can honestly deliver, and it is delivered
 * with the state that decides the rest — whether the body was on the ground, whether it was riding,
 * and what it last touched.</p>
 *
 * <h2>The threshold</h2>
 *
 * <p>{@link #VELOCITY_JUMP} is set where nothing ordinary reaches: a vanilla jump is 0.42/tick,
 * terminal fall speed is ~3.9/tick, and powered tier-2 flight is a few blocks per tick. Four blocks
 * per tick of CHANGE is already outside all of them, so the ring fills with events worth reading
 * rather than with walking.</p>
 *
 * <p>Test-only, like its sibling: it lives in the test source set, is queued only by the harness
 * coremod, and is absent from a released jar.</p>
 */
@Mixin(Entity.class)
public abstract class MixinEntityVelocityWriters {

    /** Change (blocks/tick) in one motion component that counts as a jump rather than as movement. */
    @Unique
    private static final double VELOCITY_JUMP = 4.0;

    /** Motion at the head of the current {@code move}, so the change ACROSS it can be measured. */
    @Unique
    private double arTest$motionAtMoveHead;

    /** Motion at the return of the last {@code move}, so the change BETWEEN ticks can be measured. */
    @Unique
    private double arTest$motionAtLastMoveReturn;

    /** False until the first move has returned; without it the first tick reports its own start. */
    @Unique
    private boolean arTest$seenAMove;

    @Inject(method = "move", at = @At("HEAD"))
    private void arTest$velocityBeforeMove(MoverType type, double dx, double dy, double dz,
                                           CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (arTest$seenAMove) {
            arTest$recordJump(self, "between-ticks", arTest$motionAtLastMoveReturn, self.motionY);
        }
        arTest$motionAtMoveHead = self.motionY;
    }

    @Inject(method = "move", at = @At("RETURN"))
    private void arTest$velocityAfterMove(MoverType type, double dx, double dy, double dz,
                                          CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        arTest$recordJump(self, "in-move", arTest$motionAtMoveHead, self.motionY);
        arTest$motionAtLastMoveReturn = self.motionY;
        arTest$seenAMove = true;
    }

    @Unique
    private void arTest$recordJump(Entity self, String phase, double before, double after) {
        // Announced before the threshold — see TestTrace.instrument.
        TestTrace.instrument(self, "entity_velocity_writers");
        if (Math.abs(after - before) <= VELOCITY_JUMP) {
            return;
        }
        Entity riding = self.getRidingEntity();
        TestTrace.record(self, "vel_jump",
                "\"e\":" + self.getEntityId()
                        + ",\"who\":\"" + TestTrace.json(self.getName()) + "\""
                        + ",\"phase\":\"" + phase + "\""
                        + ",\"fromY\":" + TestTrace.fmt(before)
                        + ",\"toY\":" + TestTrace.fmt(after)
                        + ",\"atY\":" + TestTrace.fmt(self.posY)
                        + ",\"onGround\":" + self.onGround
                        + ",\"riding\":" + (riding == null ? -1 : riding.getEntityId())
                        + ",\"by\":\"" + TestTrace.json(TestTrace.callerTrail()) + "\"");
    }
}
