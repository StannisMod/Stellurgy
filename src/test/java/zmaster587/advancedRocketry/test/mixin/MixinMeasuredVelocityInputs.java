package zmaster587.advancedRocketry.test.mixin;

import java.util.Map;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;

import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The two observations a client-side ship velocity is DERIVED from, and the {@code dt} between them.
 *
 * <h2>The question this exists to answer</h2>
 *
 * <p>On the client there is no physics data, so a ship's velocity at a point is built by differencing
 * two observed transforms. One run returned 597 blocks/s for a ship whose own reported {@code omega}
 * was 0.029, and that value went straight into a body's velocity. The mechanism is clear; what produced
 * the difference is not. Three readings separate the candidates and no reasoning does:</p>
 *
 * <ul>
 *   <li>a genuine STEP in the transform — the observations are far apart, and the derived rate is
 *       arithmetically correct about a movement that never physically happened;</li>
 *   <li>a {@code dt} that is not what the formula assumes — the observations are close and the divisor
 *       is wrong;</li>
 *   <li>neither: both observations are ordinary and the angular term misbehaves, which is what the
 *       quaternion-delta form can do near the antipode — where this scenario deliberately puts the
 *       ship, at a 160° roll.</li>
 * </ul>
 *
 * <p>The previous observation is read at HEAD, before the method overwrites it, and the current one at
 * RETURN. The map is the target's own private field, shadowed rather than recomputed: a reimplementation
 * here could disagree with the code under study and would be measuring itself.</p>
 *
 * <p>Targeted by NAME rather than by class literal: {@code VSBridge} is package-private, which is
 * correct — it is the gate between AR core and the physics mod and nothing outside that package has
 * business holding it. A test observation reaches in without widening it.</p>
 *
 * <p>Test-only: test source set, queued by the harness coremod, absent from a released jar.</p>
 */
@Mixin(targets = "zmaster587.advancedRocketry.integration.vs.VSBridge", remap = false)
public abstract class MixinMeasuredVelocityInputs {

    /** Speed worth reporting, matching the recorder that first saw the 597. */
    private static final double REPORT_ABOVE = 4.0;

    @Shadow
    @Final
    private static Map<PhysicsObject, double[]> OBSERVED_TRANSFORM;

    /** The previous observation, captured before the method replaces it. Single-threaded per side on
     *  the game thread, which is the only place this path runs. */
    private static double[] arTest$previous;

    @Inject(method = "measuredVelocityAtPoint", at = @At("HEAD"))
    private static void arTest$beforeDerive(World world, PhysicsObject physo,
                                            double x, double y, double z,
                                            CallbackInfoReturnable<double[]> cir) {
        TestTrace.instrumentHere("measured_velocity_inputs");
        double[] prev = OBSERVED_TRANSFORM.get(physo);
        // The FIRST observation of a ship, recorded on its own. The derived rate is a difference, so
        // knowing what the pair looked like is only half the story: if the first member is the
        // client's freshly-constructed DEFAULT rather than a state the ship was ever in, then the
        // "rotation" the rate describes never happened, and the trigger is the construction ordering
        // rather than any movement. That distinction cannot be read off the pair.
        if (prev == null) {
            TestTrace.recordHere("measured_velocity_first",
                    "\"qw\":" + TestTrace.fmt(physo.getShipData().getShipTransform()
                            .rotationQuaternion(valkyrienwarfare.api.TransformType
                                    .SUBSPACE_TO_GLOBAL).w)
                            + ",\"posX\":" + TestTrace.fmt(physo.getShipData().getShipTransform()
                                    .getShipPositionVec3d().x)
                            + ",\"posY\":" + TestTrace.fmt(physo.getShipData().getShipTransform()
                                    .getShipPositionVec3d().y)
                            + ",\"by\":\"" + TestTrace.json(TestTrace.callerTrail()) + "\"");
        }
        arTest$previous = prev == null ? null : prev.clone();
    }

    @Inject(method = "measuredVelocityAtPoint", at = @At("RETURN"))
    private static void arTest$afterDerive(World world, PhysicsObject physo,
                                           double x, double y, double z,
                                           CallbackInfoReturnable<double[]> cir) {
        double[] v = cir.getReturnValue();
        double[] prev = arTest$previous;
        double[] cur = OBSERVED_TRANSFORM.get(physo);
        if (v == null || prev == null || cur == null) {
            return;
        }
        if (Math.abs(v[0]) <= REPORT_ABOVE && Math.abs(v[1]) <= REPORT_ABOVE
                && Math.abs(v[2]) <= REPORT_ABOVE) {
            return;
        }
        TestTrace.recordHere("measured_velocity_inputs",
                "\"vy\":" + TestTrace.fmt(v[1])
                        + ",\"dTicks\":" + TestTrace.fmt(cur[0] - prev[0])
                        + ",\"prevPos\":[" + TestTrace.fmt(prev[1]) + "," + TestTrace.fmt(prev[2])
                        + "," + TestTrace.fmt(prev[3]) + "]"
                        + ",\"curPos\":[" + TestTrace.fmt(cur[1]) + "," + TestTrace.fmt(cur[2])
                        + "," + TestTrace.fmt(cur[3]) + "]"
                        + ",\"prevQ\":[" + TestTrace.fmt(prev[4]) + "," + TestTrace.fmt(prev[5])
                        + "," + TestTrace.fmt(prev[6]) + "," + TestTrace.fmt(prev[7]) + "]"
                        + ",\"curQ\":[" + TestTrace.fmt(cur[4]) + "," + TestTrace.fmt(cur[5])
                        + "," + TestTrace.fmt(cur[6]) + "," + TestTrace.fmt(cur[7]) + "]"
                        + ",\"derivedOmega\":[" + TestTrace.fmt(cur[11]) + ","
                        + TestTrace.fmt(cur[12]) + "," + TestTrace.fmt(cur[13]) + "]"
                        + ",\"derivedLinear\":[" + TestTrace.fmt(cur[8]) + ","
                        + TestTrace.fmt(cur[9]) + "," + TestTrace.fmt(cur[10]) + "]");
    }
}
