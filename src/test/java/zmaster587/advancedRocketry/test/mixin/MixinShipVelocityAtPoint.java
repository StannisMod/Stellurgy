package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.integration.vs.VSIntegration;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The CARRY — the ship's own velocity at the body's point, which is the second term of every velocity
 * AR's ship-frame travel writes.
 *
 * <h2>Why this is the last candidate standing</h2>
 *
 * <p>A hull-stander's velocity is written as {@code worldMotion[1] + carryY}. The first term is bounded
 * by what the sweep was asked for, and the sweep recorder shows it never exceeded four blocks a tick on
 * the client. So the ~30 is in the carry — and the carry comes from here.</p>
 *
 * <h2>Why it looked excluded and was not</h2>
 *
 * <p>`carryY` was read as `0.00` and the carry dismissed. That reading came from
 * {@code artest vs shipframe-stats}, a SERVER probe returning a SERVER static, while the launch happens
 * on the CLIENT — a different process with its own copy of that field. It is the same mistake that had
 * already been made once with the substrate's added velocity: a server reading used to exclude a
 * client-side writer. The number was never measured on the side that matters, and this measures it
 * there.</p>
 *
 * <p>Announced on entry, so a silence is readable. Test-only: test source set, queued by the harness
 * coremod, absent from a released jar.</p>
 */
@Mixin(value = VSIntegration.class, remap = false)
public abstract class MixinShipVelocityAtPoint {

    /** Speed worth a record, in blocks per second as this seam reports it. A parked hull is ~0. */
    private static final double SHIP_SPEED_REPORT = 4.0;

    @Inject(method = "shipVelocityAtPointFor", at = @At("RETURN"))
    private static void arTest$afterShipVelocityAtPoint(World world, String shipId,
                                                        double x, double y, double z,
                                                        CallbackInfoReturnable<double[]> cir) {
        TestTrace.instrumentHere("ship_velocity_at_point");
        double[] v = cir.getReturnValue();
        if (v == null || v.length < 3) {
            return;
        }
        if (Math.abs(v[0]) <= SHIP_SPEED_REPORT && Math.abs(v[1]) <= SHIP_SPEED_REPORT
                && Math.abs(v[2]) <= SHIP_SPEED_REPORT) {
            return;
        }
        TestTrace.recordHere("ship_velocity_big",
                "\"vx\":" + TestTrace.fmt(v[0])
                        + ",\"vy\":" + TestTrace.fmt(v[1])
                        + ",\"vz\":" + TestTrace.fmt(v[2])
                        + ",\"atX\":" + TestTrace.fmt(x)
                        + ",\"atY\":" + TestTrace.fmt(y)
                        + ",\"atZ\":" + TestTrace.fmt(z)
                        + ",\"by\":\"" + TestTrace.json(TestTrace.callerTrail()) + "\"");
    }
}
