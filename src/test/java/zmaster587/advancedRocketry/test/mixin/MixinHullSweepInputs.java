package zmaster587.advancedRocketry.test.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.integration.vs.HullSweep;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The last unknown in the hull-stand launch: what the sweep was ASKED for, and what it gave back.
 *
 * <h2>Why here and not in the caller</h2>
 *
 * <p>The writer is {@code ShipFrameTravel.hullStandTravel}, and the value that matters is its local
 * {@code vWorld} — but that method takes a {@code private static final class ShipFrameState}, which an
 * injector's signature would have to name, so it cannot be injected into from outside. The sweep it
 * calls is public, static, and receives {@code vWorld[0..2]} as its own arguments. So the number is
 * reachable one frame down, without local capture and without touching production.</p>
 *
 * <h2>What the two readings separate</h2>
 *
 * <p>The launched body's velocity is written as {@code worldMotion[1] + carryY} with a measured
 * {@code carryY = 0}, so the ~30 lives in {@code vWorld[1]}. Either it is already ~30 when the sweep is
 * asked — in which case something upstream of the sweep composed it, and {@code moveFactor} (the one
 * division on that path, {@code SPEED_NORMALISER / friction³}) is the first suspect — or it arrives
 * small and the sweep's own result is what grows, which points at the de-penetration lift. The record
 * carries both ends so the run answers rather than narrows.</p>
 *
 * <p>Announced on entry like every other observation point, so a silence is readable. Test-only:
 * test source set, queued by the harness coremod, absent from a released jar.</p>
 */
@Mixin(value = HullSweep.class, remap = false)
public abstract class MixinHullSweepInputs {

    /** Magnitude worth a record, on either end. Ordinary standing and walking sit far below it. */
    private static final double SWEEP_REPORT = 4.0;

    @Inject(method = "sweep", at = @At("RETURN"))
    private static void arTest$afterSweep(double[] bounds, double wantX, double wantY, double wantZ,
                                          List<double[]> obstacles, double[][] shipAxes,
                                          double[] up, double stepHeight, boolean grounded,
                                          CallbackInfoReturnable<HullSweep.Result> cir) {
        TestTrace.instrumentHere("hull_sweep");
        HullSweep.Result r = cir.getReturnValue();
        if (r == null) {
            return;
        }
        double outY = r.liftY + r.dy;
        if (Math.abs(wantY) <= SWEEP_REPORT && Math.abs(outY) <= SWEEP_REPORT) {
            return;
        }
        TestTrace.recordHere("hull_sweep_big",
                "\"wantY\":" + TestTrace.fmt(wantY)
                        + ",\"dy\":" + TestTrace.fmt(r.dy)
                        + ",\"liftY\":" + TestTrace.fmt(r.liftY)
                        + ",\"collidedY\":" + r.collidedY
                        + ",\"normalY\":" + TestTrace.fmt(r.normalY)
                        + ",\"grounded\":" + grounded
                        + ",\"stepHeight\":" + TestTrace.fmt(stepHeight)
                        + ",\"obstacles\":" + (obstacles == null ? -1 : obstacles.size())
                        + ",\"by\":\"" + TestTrace.json(TestTrace.callerTrail()) + "\"");
    }
}
