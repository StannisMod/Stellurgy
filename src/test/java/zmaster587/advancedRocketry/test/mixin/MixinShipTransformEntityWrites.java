package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.Entity;

import org.joml.Matrix4dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.util.ValkyrienUtils;

import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * Watches the one call that rewrites a body's VELOCITY by a ship's transform.
 *
 * <h2>Why this call and not another</h2>
 *
 * <p>{@code ValkyrienUtils.transformEntity} takes an entity's position, look AND motion, maps all
 * three through a matrix, and assigns them back. The entity drag calls it every tick for every body
 * still associated with a ship, and then restores only the POSITION — the motion it wrote stays. So
 * it is the one place on this path where a velocity can be replaced rather than accumulated, and a
 * body whose motion is mapped through the transform of a steeply inverted hull can come out pointing
 * the other way.</p>
 *
 * <p>Named as a suspect rather than as a conclusion. The phase recorder
 * ({@code MixinEntityVelocityWriters}) can only say a client-side velocity changed BETWEEN ticks,
 * because velocity lives in public fields and there is no funnel to catch a write in — so the
 * candidates in that window have to be instrumented one at a time, and this is the first. If it is
 * not this call, the record simply never appears, and that is an answer too.</p>
 *
 * <h2>Why the exclusion that seemed to rule it out did not</h2>
 *
 * <p>The substrate's added velocity read {@code (0,0,0)} at every sample of the encounter, which was
 * taken as ruling the drag out. That reading came from a SERVER probe, and the writer turned out to be
 * on the CLIENT — where the drag runs too ({@code EventsClient}) and keeps its own association state
 * that no server probe can see. The exclusion was true about the server and silent about the side the
 * defect is on.</p>
 *
 * <p>Test-only: test source set, queued by the harness coremod, absent from a released jar.</p>
 */
@Mixin(value = ValkyrienUtils.class, remap = false)
public abstract class MixinShipTransformEntityWrites {

    /** Motion magnitude worth recording. Deliberately LOW: the first run of this recorder returned an
     *  empty log, and an empty log has two innocent readings — "this call never wrote a large motion"
     *  and "this mixin was never applied". A threshold a falling body crosses within a tick separates
     *  them: records at all mean the instrument is alive, and their absence then means the call does
     *  not run on this body at all. */
    private static final double MOTION_REWRITE_REPORT = 0.1;

    @Inject(method = "transformEntity", at = @At("RETURN"))
    private static void arTest$recordMotionRewrite(Matrix4dc transform, Entity entity,
                                                   boolean transformEntityBoundingBox,
                                                   CallbackInfo ci) {
        if (entity == null) {
            return;
        }
        // Announced BEFORE the threshold, so an empty log below is readable: with this name present a
        // silence means "this call wrote nothing large", and without it, "this call never ran".
        TestTrace.instrument(entity, "ship_transform_entity_writes");
        // The value BEFORE the call is not available at RETURN, so the record carries what the call
        // LEFT and the caller trail that asked for it; a large motion here beside a small one in the
        // sample before it is the pairing that names this call as the writer.
        if (Math.abs(entity.motionY) <= MOTION_REWRITE_REPORT) {
            return;
        }
        TestTrace.record(entity, "ship_transform_motion",
                "\"e\":" + entity.getEntityId()
                        + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\""
                        + ",\"motionY\":" + TestTrace.fmt(entity.motionY)
                        + ",\"atY\":" + TestTrace.fmt(entity.posY)
                        + ",\"by\":\"" + TestTrace.json(TestTrace.callerTrail()) + "\"");
    }

}
